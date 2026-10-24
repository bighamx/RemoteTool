"""Narrow gateway lifecycle adapter. Never kills a process or exports configuration secrets."""
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import time


def status(home):
    from gateway.status import _pid_exists
    state = json.loads((home / "gateway_state.json").read_text(encoding="utf-8-sig"))
    pid = int(state.get("pid") or 0)
    from datetime import datetime, timezone
    heartbeat = json.loads((home / "state" / "gateway.heartbeat").read_text(encoding="utf-8-sig"))
    stamp = datetime.fromisoformat(heartbeat["updated_at"].replace("Z", "+00:00"))
    fresh = int(heartbeat.get("pid") or 0) == pid and 0 <= (datetime.now(timezone.utc) - stamp).total_seconds() < 30
    leases = 0
    homes = [home] + [p for p in (home / "profiles").glob("*") if p.is_dir() and not p.name.startswith(".")]
    for directory in homes:
        database = directory / "state.db"
        if not database.exists():
            continue
        with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True, timeout=3) as db:
            leases += db.execute("SELECT count(*) FROM session_turn_leases WHERE expires_at > ?", (time.time(),)).fetchone()[0]
    return {"pid": pid, "running": bool(pid and _pid_exists(pid)), "fresh": fresh,
            "state": state.get("gateway_state"), "active": max(leases, int(state.get("active_agents") or 0)),
            "profiles": state.get("served_profiles") or ["default"], "restart_supported": os.name == "nt",
            "code_sha": state.get('code_sha'), "platforms": {key:value.get('state') for key,value in (state.get('platforms') or {}).items()}}


def invoke(action, body):
    from hermes_constants import get_hermes_home
    from gateway.drain_control import write_drain_request, clear_drain_request, read_drain_request
    home = get_hermes_home()
    if action == 'admin_gateway_log':
        from hermes_ops import read_job, cleanup_logs
        import re
        cleanup_logs(home)
        job = read_job(home)
        key = job.get('key','')
        if not re.fullmatch('[a-f0-9]{32}', key):
            return {'key': '', 'offset': 0, 'text': ''}
        path = home / 'cache' / 'remotetool-maintenance' / key / 'maintenance.log'
        offset = max(0, int(body.get('offset') or 0)) if body.get('key') == key else 0
        if not path.exists():
            return {'key': key, 'offset': 0, 'text': ''}
        with path.open('rb') as stream:
            stream.seek(min(offset, path.stat().st_size))
            chunk = stream.read(32768)
            end = stream.tell()
        text = chunk.decode('utf-8',errors='replace')
        text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
        text = re.sub(r'(?i)(Bearer\s+|(?:api[_-]?key|password|secret|access_token)\s*[:=]\s*)[^\s,]+', r'\1[REDACTED]', text)
        return {'key': key, 'offset': end, 'text': text}
    if action in ('admin_gateway_job', 'admin_gateway_prepare', 'admin_gateway_cancel_job', 'admin_gateway_launch_failed'):
        from hermes_ops import read_job, prepare, atomic
        if action == 'admin_gateway_prepare':
            return prepare(home, body)
        job = read_job(home)
        if action == 'admin_gateway_cancel_job':
            if job.get('phase') not in ('queued','waiting'):
                raise RuntimeError('当前阶段不能取消')
            (home / 'cache' / 'remotetool-maintenance' / job['key'] / 'cancel').touch()
            if job.get('phase') == 'queued':
                job.update(phase='cancelled', message='启动已取消', can_cancel=False)
                atomic(home / 'cache' / 'remotetool-maintenance' / 'status.json', job)
        if action == 'admin_gateway_launch_failed':
            if job.get('key') != body.get('key'):
                return job
            message = str(body.get('error') or '无法启动系统维护任务')
            job.update(phase='failed', message=message, can_cancel=False)
            folder = home / 'cache' / 'remotetool-maintenance'
            atomic(folder / job['key'] / 'status.json',job)
            atomic(folder / 'status.json', job)
            with (folder / job['key'] / 'maintenance.log').open('a',encoding='utf-8') as stream:
                stream.write(message + '\nEXITCODE=1\n')
        return job
    if action == "admin_gateway_status":
        return status(home)
    owner = "remotetool:" + body["key"]
    marker = read_drain_request(home=home)
    if marker is not None and marker.get("principal") != owner:
        raise RuntimeError("网关正由其他客户端维护")
    if action == "admin_gateway_drain":
        state = status(home)
        if not state["restart_supported"] or not state["running"] or not state["fresh"]:
            raise RuntimeError("当前网关不支持安全重启或状态已过期")
        write_drain_request(principal=owner, suppress_notification=True, home=home)
        return state
    if action == "admin_gateway_cancel":
        if marker and marker.get("principal") == owner:
            clear_drain_request(home=home)
        return {"ok": True}
    if action != "admin_gateway_stop":
        raise ValueError("不支持的操作")
    state = status(home)
    if not marker or state["state"] != "draining" or state["active"] or not state["fresh"] or not state["restart_supported"]:
        raise RuntimeError("网关尚未安全排空，已取消重启")
    from hermes_cli.gateway_windows import _drain_gateway_pid
    if not _drain_gateway_pid(state["pid"], 20):
        raise RuntimeError("网关未正常退出；不会强制结束进程")
    clear_drain_request(home=home)
    return {"old_pid": state["pid"]}
