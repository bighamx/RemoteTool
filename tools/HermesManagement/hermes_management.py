"""Bounded JSON adapter to installed Hermes' official model-settings handlers.
Credentials are input-only; no arbitrary command or configuration-file API is exposed.
"""
import asyncio
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import sys
import sqlite3
import uuid
from datetime import datetime, timezone
# Select Hermes' managed runtime before consuming stdin; a runtime relaunch
# must leave the request available to the replacement interpreter.
import hermes_bootstrap

def main():
    request = json.load(sys.stdin)
    action = request.get("action")
    if action not in {"providers", "save_provider", "default_model", "model_info", "session_context", "compress_session"}:
        raise ValueError("Unsupported settings operation")
    with contextlib.redirect_stdout(io.StringIO()):
        from hermes_cli.web_routers.config_env import list_custom_endpoints, upsert_custom_endpoint
        from hermes_cli.web_routers.models import get_model_info, set_model_assignment
        from hermes_cli.web_models import CustomEndpointUpdate, ModelAssignment
        if action in {"save_provider", "default_model"}:
            from hermes_constants import get_hermes_home
            home = get_hermes_home()
            backup = home / "backups" / "chuckie-helper" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            backup.mkdir(parents=True, exist_ok=False)
            for name in ("config.yaml", ".env"):
                source = home / name
                if source.exists(): shutil.copy2(source, backup / name)
        body = request.get("body") or {}
        if action == "default_model" and "reasoning_effort" in body:
            effort = body.get("reasoning_effort") or ""
            if effort and effort not in {"none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"}:
                raise ValueError("Invalid reasoning effort")
        if action == "compress_session":
            result = compress_session(body)
        elif action == "session_context":
            from hermes_state import SessionDB
            from agent.usage_anchor import persisted_anchor_tokens
            from agent.model_metadata import estimate_messages_tokens_rough, get_cached_context_length
            session_id = str(body.get("session_id") or "")
            db = SessionDB()
            try:
                row = db.get_session(session_id)
                if not row: raise ValueError("Session not found")
                messages = db.get_messages_as_conversation(session_id)
                config = row.get("model_config") or {}
                if isinstance(config, str): config = json.loads(config)
                tokens = persisted_anchor_tokens(db, session_id, messages)
                estimated = tokens is None
                if estimated: tokens = estimate_messages_tokens_rough(messages)
                model = row.get("model") or ""
                limit = config.get("context_length") or get_cached_context_length(model, config.get("base_url") or "")
                if not limit:
                    info = get_model_info()
                    if info.get("model") == model: limit = info.get("effective_context_length")
                if not limit:
                    # Gateway's actual resolution chain (models.dev catalog, local /models probe,
                    # provider profiles) — the persistent cache above only has entries probed live
                    # before, so sessions on models never probed (e.g. mobile-picked ones) showed
                    # no limit. Same resolution the running agent uses for its compressor.
                    try:
                        from agent.model_metadata import get_model_context_length
                        limit = get_model_context_length(model, config.get("base_url") or "")
                    except Exception:
                        limit = None
                result = {"available": bool(messages) or not estimated, "tokens": tokens, "limit": limit, "estimated": estimated, "model": row.get("model")}
            finally: db.close()
        elif action == "providers": result = list_custom_endpoints()
        elif action == "model_info":
            result = get_model_info()
            from hermes_cli.config import load_config
            result["reasoning_effort"] = (load_config().get("agent") or {}).get("reasoning_effort") or ""
        elif action == "save_provider": result = upsert_custom_endpoint(CustomEndpointUpdate.model_validate(body))
        else:
            body["scope"] = "main"
            result = asyncio.run(set_model_assignment(ModelAssignment.model_validate(body)))
            # Hermes' main ModelAssignment currently ignores reasoning_effort (only auxiliary
            # assignments apply it). Use the official partial config handler after model acceptance.
            if not result.get("confirm_required") and result.get("ok") and "reasoning_effort" in body:
                from hermes_cli.web_routers.config_env import update_config
                from hermes_cli.web_models import ConfigUpdate
                effort = body.get("reasoning_effort") or ""
                asyncio.run(update_config(ConfigUpdate(config={"agent": {"reasoning_effort": effort}})))
    def scrub(value):
        if isinstance(value, dict):
            return {k: scrub(v) for k, v in value.items() if k.lower() not in {"api_key", "api_key_preview", "password", "secret", "access_token", "token"}}
        if isinstance(value, list): return [scrub(v) for v in value]
        return value
    print(json.dumps(scrub(result), ensure_ascii=True))

def compress_session(body):
    from hermes_state import SessionDB
    from hermes_constants import get_hermes_home
    from hermes_cli.config import load_config
    from gateway.run import _resolve_runtime_agent_kwargs_for_provider, _resolve_runtime_agent_kwargs, _seed_hygiene_system_prompt, _GATEWAY_HYGIENE_PLATFORM
    from agent.conversation_compression_manual import compress_now, CompressRequest
    from agent.conversation_compression import finalize_context_engine_compression_notification
    from run_agent import AIAgent
    db = SessionDB()
    agent = None
    session_id = str(body.get("session_id") or "")
    holder = f"chuckie-mobile:pid={os.getpid()}:id={uuid.uuid4().hex}"
    leased = False
    try:
        session_id = db.resolve_resume_session_id(session_id)
        row = db.get_session(session_id)
        if not row: raise ValueError("Session not found")
        if not db.try_acquire_session_turn_lease(session_id, holder, ttl_seconds=960, patience_s=0.5):
            return {"success": False, "message": "会话正在运行，请结束任务后再压缩"}
        leased = True
        messages = [m for m in db.get_messages_as_conversation(session_id) if m.get("role") in {"user", "assistant", "tool"}]
        if len(messages) < 4: return {"success": True, "status": "nothing_to_do", "session_id": session_id, "message": "当前上下文较短，无需压缩"}
        config = row.get("model_config") or {}
        if isinstance(config, str): config = json.loads(config)
        provider = config.get("requested_provider") or config.get("provider")
        runtime = _resolve_runtime_agent_kwargs_for_provider(provider, row.get("model")) if provider and provider not in {"custom", "auto"} else _resolve_runtime_agent_kwargs()
        model = row.get("model") or runtime.pop("model", None) or (load_config().get("model") or {}).get("default")
        runtime.pop("model", None); runtime.pop("_fallback_notice", None)
        if runtime.get("api_mode") == "codex_app_server":
            return {"success": False, "message": "该 Hermes 会话由 Codex 管理，请在 Codex 会话中执行压缩"}
        # A WAL-aware SQLite snapshot protects durable history, without copying provider secrets.
        backup = get_hermes_home() / "backups" / "chuckie-helper" / datetime.now(timezone.utc).strftime("compress-%Y%m%dT%H%M%S%fZ")
        backup.mkdir(parents=True, exist_ok=False)
        with sqlite3.connect(Path(db.db_path).as_uri()+"?mode=ro", uri=True) as source, sqlite3.connect(backup / "state.db") as target: source.backup(target)
        from utils import is_truthy_value
        checkpoint = is_truthy_value((load_config().get("compression") or {}).get("checkpoint_required"), default=False)
        agent = AIAgent(**runtime, model=model, max_iterations=4, quiet_mode=True, skip_memory=not bool(checkpoint),
                        enabled_toolsets=["memory"], session_id=session_id, session_db=db)
        _seed_hygiene_system_prompt(agent, row)
        agent.platform = _GATEWAY_HYGIENE_PLATFORM
        agent._print_fn = lambda *a, **kw: None
        agent._end_session_on_close = False
        agent._active_session_turn_lease_holder = holder
        # Use Hermes' transactional archive-and-compact path, keeping the same conversation ID.
        agent.compression_in_place = True
        result = compress_now(agent, messages, CompressRequest(), system_message="", skip_without_window=True, task_id=session_id)
        if result.status == "nothing_to_do": return {"success": True, "status": result.status, "session_id": session_id, "message": "当前上下文无需压缩"}
        if result.status != "compressed" or not getattr(agent, "_last_compaction_in_place", False):
            return {"success": False, "message": "压缩未提交，原始会话记录已保留，请稍后重试"}
        finalize_context_engine_compression_notification(agent, committed=True)
        return {"success": True, "status": "compressed", "session_id": session_id,
                "before_tokens": result.before_tokens, "after_tokens": result.after_tokens,
                "message": f"上下文已压缩：约 {result.before_tokens:,} → {result.after_tokens:,} tokens"}
    finally:
        if agent:
            with contextlib.suppress(Exception): finalize_context_engine_compression_notification(agent, committed=False)
            with contextlib.suppress(Exception): agent.close()
        if leased: db.release_session_turn_lease(session_id, holder)
        db.close()

if __name__ == "__main__":
    try: main()
    except Exception:
        # Never export provider keys, raw config, command arguments or tracebacks.
        print(json.dumps({"success": False, "message": "Hermes 模型设置操作失败，请检查安装版本与配置"}, ensure_ascii=True))
        sys.exit(1)
