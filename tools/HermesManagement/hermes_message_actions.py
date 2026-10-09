"""Message actions through Hermes SessionDB primitives; never edit its SQLite tables directly."""
import copy
import json
import re

def _public_session(row):
    # Match the native API's client-safe session summary. Never return system prompts,
    # provider configuration or arbitrary stored fields to the mobile UI.
    keys = ("id", "title", "source", "model", "cwd", "started_at", "last_active", "message_count", "parent_session_id")
    result = {key: row.get(key) for key in keys if row.get(key) is not None}
    result["pinned"] = bool(row.get("pinned"))
    return result

def _native_rewind_target(target):
    from agent.context_compressor import split_user_originated_turn
    from agent.memory_manager import sanitize_context
    handoff, live = split_user_originated_turn(target)
    if live is None:
        raise ValueError("Message is not a user-originated turn")
    content = live.get("content")
    if isinstance(content, str):
        content = sanitize_context(content).strip()
    return handoff is not None, content


def message_action(db, body):
    session_id = str(body.get("session_id") or "")
    session_id = db.resolve_resume_session_id(session_id)
    source = db.get_session(session_id)
    if not source:
        raise ValueError("Session not found")
    message_id = body.get("message_id")
    if isinstance(message_id, bool) or not isinstance(message_id, int) or message_id <= 0:
        raise ValueError("Invalid message id")
    # Capture the CAS boundary before reading the projected messages. A later append
    # must fail the native transaction, rather than joining a freshly captured boundary.
    active_ids = db.get_active_message_ids(session_id) if body.get("operation") == "rewind" else None
    messages = db.get_messages(session_id)
    index = next((i for i, row in enumerate(messages) if row.get("id") == message_id), None)
    if index is None:
        raise ValueError("Message is no longer active; refresh the conversation. Compacted history cannot be rewound by this API.")
    target = messages[index]
    if body.get("operation") == "rewind":
        if target.get("role") != "user":
            raise ValueError("Only user messages can be edited")
        latest_user = next((row for row in reversed(messages) if row.get("role") == "user"), {})
        if latest_user.get("id") != body.get("expected_latest_user_id"):
            raise ValueError("Conversation changed; refresh before editing")
        # Native transaction refuses live writers/compression, and rechecks the complete active
        # set and canonical target so concurrent appends cannot be silently discarded.
        preserve, content = _native_rewind_target(target)
        result = db.rewind_to_message(session_id, message_id, preserve_compaction_handoff=preserve,
            expected_active_ids=active_ids, expected_target_content=content)
        return {"rewound": True, "rewound_count": result["rewound_count"]}
    if body.get("operation") != "fork":
        raise ValueError("Unsupported message action")
    fork_id = str(body.get("fork_id") or "")
    if not re.fullmatch(r"mobile_fork_[a-f0-9]{32}", fork_id):
        raise ValueError("Invalid fork id")
    existing = db.get_session(fork_id)
    config = source.get("model_config") or {}
    if isinstance(config, str):
        config = json.loads(config)
    config = copy.deepcopy(config)
    config.update(_branched_from=session_id, _remotetool_branch_message=message_id)
    if existing:
        saved = existing.get("model_config") or {}
        if isinstance(saved, str):
            saved = json.loads(saved)
        if saved.get("_branched_from") != session_id or saved.get("_remotetool_branch_message") != message_id:
            raise ValueError("Fork id already belongs to another operation")
        child_rows = db.get_messages(fork_id)
        return {"session": _public_session(existing), "message_id_map": {str(old["id"]): str(new["id"]) for old, new in zip(messages[:index + 1], child_rows)}}
    history = copy.deepcopy(messages[:index + 1])
    # A branch ending at a narrated tool call must not leave unresolved tool calls.
    if history and history[-1].get("role") == "assistant":
        history[-1].pop("tool_calls", None)
    for row in history:
        for key in ("id", "_row_id", "message_uid", "session_id", "compacted", "active"):
            row.pop(key, None)
    db.create_session(fork_id, "api_server", model=source.get("model"),
        system_prompt=source.get("system_prompt"), parent_session_id=session_id,
        model_config=config, cwd=source.get("cwd"))
    try:
        db.replace_messages(fork_id, history)
        db.set_session_title(fork_id, (source.get("title") or "Hermes")[:130] + " · 分叉 " + fork_id[-6:])
    except Exception:
        db.delete_session(fork_id)
        raise
    child_rows = db.get_messages(fork_id)
    return {"session": _public_session(db.get_session(fork_id)), "message_id_map": {str(old["id"]): str(new["id"]) for old, new in zip(messages[:index + 1], child_rows)}}
