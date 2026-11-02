"""Task Scheduler worker. Durable state is independent of the Web/gateway process tree."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

ACTIVE = {"queued", "checking", "waiting", "restarting", "updating"}


class GatewaySessionMismatch(RuntimeError):
    pass


def verify_gateway_session(live, expected):
    if live.get('running') and live.get('session_id') != expected:
        raise GatewaySessionMismatch('网关运行在错误的 Windows 会话中，不能确认重启成功')


def wait_for_gateway_exit(pid, timeout=90):
    """Recovery may start a gateway only after the old PID is conclusively gone."""
    from gateway.status import _pid_exists
    deadline = time.monotonic() + timeout
    while _pid_exists(pid):
        if time.monotonic() >= deadline:
            return False
        time.sleep(3)
    return True


def cleanup_logs(home):
    """Only this feature's terminal task logs; never remove requests, patches or directories."""
    import re
    folder = home / 'cache' / 'remotetool-maintenance'
    if not folder.exists(): return
    checked = folder / 'log-cleanup.checked'
    if checked.exists() and time.time() - checked.stat().st_mtime < 86400: return
    current = read_job(home).get('key')
    for job in folder.iterdir():
        if not re.fullmatch('[a-f0-9]{32}',job.name) or job.name == current: continue
        try:
            if not job.is_dir() or getattr(job.lstat(),'st_file_attributes',0) & 0x400: continue
            record = json.loads((job/'status.json').read_text(encoding='utf-8'))
            if record.get('phase') not in ('completed','cancelled','failed'): continue
            log = job / 'maintenance.log'
            if log.is_file() and not getattr(log.lstat(),'st_file_attributes',0) & 0x400 and time.time()-log.stat().st_mtime > 30*86400:
                log.unlink()
        except (OSError,ValueError): continue
    checked.touch()


def atomic(path, value):
    import uuid
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=True), encoding='utf-8')
        os.replace(temporary, path)
    finally:
        if temporary.exists(): temporary.unlink()


def read_job(home):
    path = home / 'cache' / 'remotetool-maintenance' / 'status.json'
    record = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {"phase": "idle", "message": ""}
    if record.get('phase') in ACTIVE and record.get('worker_pid'):
        import ctypes
        kernel = ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_uint32,ctypes.c_int,ctypes.c_uint32]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.GetExitCodeProcess.argtypes = [ctypes.c_void_p,ctypes.POINTER(ctypes.c_uint32)]
        handle = kernel.OpenProcess(0x1000,False,int(record['worker_pid']))
        alive = True
        if handle:
            try:
                code = ctypes.c_uint32()
                if kernel.GetExitCodeProcess(handle,ctypes.byref(code)): alive = code.value == 259
            finally: kernel.CloseHandle(handle)
        elif ctypes.get_last_error() == 87: alive = False
        if not alive:
            # Presentation only: retain the active journal so an uncertain command cannot be replayed.
            record.update(phase='unknown',message='维护进程已退出，结果待核对；请查看日志，不会自动重试',can_cancel=False)
    return record


def prepare(home, body):
    import msvcrt
    import uuid
    folder = home / 'cache' / 'remotetool-maintenance'
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / 'operation.lock').open('a+b') as lock:
        if not lock.tell():
            lock.write(b'0'); lock.flush()
        lock.seek(0)
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            previous = read_job(home)
            if previous.get('phase') in ACTIVE or previous.get('phase') == 'unknown':
                raise RuntimeError('已有维护任务，请先核对或取消；不会自动清理锁或重复执行')
            cleanup_logs(home)
            key = uuid.uuid4().hex
            job = folder / key
            job.mkdir()
            for name in ('hermes_ops.py', 'hermes_admin.py'):
                shutil.copy2(Path(__file__).with_name(name), job / name)
            import hermes_bootstrap
            source = Path(hermes_bootstrap.__file__).resolve().parent
            request = {'home': str(home), 'source': str(source), 'python': sys.executable,
                       'key': key, 'wait': bool(body.get('wait')), 'mode': body.get('mode','restart'), 'task': 'RemoteTool_Hermes_' + key}
            atomic(job / 'request.json', request)
            atomic(folder / 'status.json', {'key': key, 'phase': 'queued', 'message': '等待系统维护任务启动', 'can_cancel': True})
            return {'python': sys.executable, 'script': str(job / 'hermes_ops.py'), 'request': str(job / 'request.json')}
        finally:
            lock.seek(0); msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


def main():
    mode, request_path = sys.argv[1:]
    request_path = Path(request_path)
    request = json.loads(request_path.read_text(encoding='utf-8'))
    home, job = Path(request['home']), request_path.parent
    os.environ.update(HERMES_HOME=str(home), HERMES_DISABLE_LAZY_INSTALLS='1', PYTHONPATH=request['source'], PYTHONUNBUFFERED='1', PYTHONIOENCODING='utf-8', NO_COLOR='1')
    state = home / 'cache' / 'remotetool-maintenance' / 'status.json'
    log = (job / 'maintenance.log').open('a', encoding='utf-8', buffering=1)
    sys.stdout = sys.stderr = log
    def phase(value, message):
        record = {'key': request['key'], 'phase': value, 'message': message, 'can_cancel': value in ('queued','waiting'), 'worker_pid': os.getpid()}
        atomic(job/'status.json',record)
        if read_job(home).get('key') == request['key']:
            atomic(state, record)
        print(datetime.now(timezone.utc).isoformat(), value, message, flush=True)
    if mode != 'run':
        raise ValueError('Unknown operation')
    body = {'key': request['key']}
    exitcode = 1
    invoke = None
    stopped_pid = None
    run_lock = (job / 'run.lock').open('a+b')
    import msvcrt
    if not run_lock.tell(): run_lock.write(b'0'); run_lock.flush()
    run_lock.seek(0)
    try: msvcrt.locking(run_lock.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError: return
    if read_job(home).get('key') != request['key'] or read_job(home).get('phase') != 'queued': return
    def command(args, cwd=None):
        print('> ' + subprocess.list2cmdline(args), flush=True)
        process = subprocess.Popen(args, cwd=cwd or str(home), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, creationflags=0x08000000)
        while True:
            chunk = process.stdout.read1(4096)
            if not chunk: break
            log.buffer.write(chunk); log.buffer.flush()
        result = process.wait()
        print('\nCOMMAND_EXITCODE=' + str(result), flush=True)
        if result: raise RuntimeError('命令失败，请查看以上原始输出；不会强制更新或自动重试')
    try:
        sys.path.insert(0, request['source'])
        import hermes_bootstrap
        from hermes_admin import invoke, require_interactive_session
        session_id = require_interactive_session()
        print('Maintenance Windows session=' + str(session_id), flush=True)
        subprocess.run(['schtasks.exe','/Delete','/TN',request['task'],'/F'], capture_output=True, timeout=15)
        phase('checking', '核对全部 Profile 的任务')
        launcher = home / 'bin' / 'hermes.exe'
        if not launcher.is_file(): raise RuntimeError('未找到 Hermes 启动程序')
        live = invoke('admin_gateway_status', {})
        if live.get('session_id') != session_id:
            raise RuntimeError('网关不在维护用户的交互式会话中，请先修复网关启动任务；已取消操作')
        if live['active'] and not request['wait']:
            raise RuntimeError('有任务正在运行，请选择等待空闲后重启')
        deadline, stable = time.monotonic() + 1800, 0
        phase('waiting', '暂停接收新任务，等待全部 Profile 空闲')
        while time.monotonic() < deadline:
            if (job / 'cancel').exists():
                phase('cancelled','等待已取消'); exitcode = 0; return
            live = invoke('admin_gateway_drain', body)
            stable = stable + 1 if live['state'] == 'draining' and live['active'] == 0 else 0
            if stable >= 2:
                break
            time.sleep(3)
        else:
            raise RuntimeError('等待超过 30 分钟，已取消重启')
        if (job / 'cancel').exists():
            phase('cancelled','等待已取消'); exitcode = 0; return
        phase('restarting', '已安全排空，正在正常重启')
        previous = invoke('admin_gateway_status', {})
        previous = {'old_pid': previous['pid']}
        if request['mode'] == 'update':
            stopped_pid = previous['old_pid']
            previous = invoke('admin_gateway_stop', body)
            phase('updating','正在运行 hermes update')
            print('使用官方 --yes：自动确认配置迁移和恢复本地暂存修改；冲突由官方更新器报告并保留恢复依据。',flush=True)
            command([str(launcher),'update','--yes','--no-gateway-restart'])
        phase('restarting','正在启动网关')
        if request['mode'] == 'update':
            command([str(launcher),'gateway','start'])
        else:
            # Official restart bounds Windows shutdown, fingerprints the old PID,
            # handles a wedged shutdown, and refuses duplicate gateway instances.
            command([str(launcher),'gateway','restart','--detach'])
        expected_sha = subprocess.run(['git','rev-parse','HEAD'],cwd=request['source'],capture_output=True,text=True,check=True).stdout.strip()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            time.sleep(3)
            try:
                live = invoke('admin_gateway_status', {})
                verify_gateway_session(live, session_id)
                if live['running'] and live['fresh'] and live['state'] == 'running' and live['pid'] != previous['old_pid'] and live['code_sha'] == expected_sha and all(value == 'connected' for value in live['platforms'].values()):
                    from urllib.request import Request, urlopen
                    from dotenv import dotenv_values
                    env = dotenv_values(home / '.env')
                    url = 'http://127.0.0.1:' + str(env.get('API_SERVER_PORT') or '8642') + '/v1/models'
                    with urlopen(Request(url, headers={'Authorization': 'Bearer ' + env.get('API_SERVER_KEY','')}), timeout=5) as response:
                        if response.status == 200:
                            phase('completed','Hermes 已更新，网关与 API 已恢复' if request['mode']=='update' else '网关已重启，API 已恢复'); exitcode = 0; return
            except GatewaySessionMismatch:
                raise
            except Exception:
                continue
        raise RuntimeError('未确认网关恢复；请检查日志，不会重复重启')
    except Exception as error:
        if stopped_pid is not None and not isinstance(error, GatewaySessionMismatch):
            try:
                print('更新维护失败，核对旧网关是否已退出；不重复停止、不启动第二个实例', flush=True)
                if wait_for_gateway_exit(stopped_pid):
                    command([str(launcher), 'gateway', 'start'])
                    print('已提交网关恢复启动；维护仍记为失败，需核对网关状态', flush=True)
                else:
                    print('旧网关 PID 仍存在，未启动额外实例；需人工核对退出状态', flush=True)
            except Exception as recovery_error:
                print('网关恢复未确认：' + str(recovery_error), flush=True)
        phase('failed', str(error))
    finally:
        try:
            if invoke: invoke('admin_gateway_cancel', body)
        except Exception:
            pass
        print('EXITCODE=' + str(exitcode), flush=True)


if __name__ == '__main__':
    main()
