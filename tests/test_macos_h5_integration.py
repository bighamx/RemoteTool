"""Read-only integration fixtures: real ASP.NET host, fake local Codex, no model/real credentials."""
import http.cookiejar
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
DOTNET = os.environ.get("REMOTETOOL_TEST_DOTNET", "dotnet")
MOCK = r'''
import json,os,sys,time,uuid
from pathlib import Path
store=Path(os.environ['CODEX_HOME'])/'fixture-threads.json'
def read():return json.loads(store.read_text()) if store.exists() else {}
def write(rows):store.write_text(json.dumps(rows))
def out(obj):print(json.dumps(obj),flush=True)
pending=None
def finish(thread,turn,text):
    rows=read();item={'id':'assistant-'+turn,'type':'agentMessage','text':text};rows[thread]['turns'][-1]['items'].append(item);rows[thread]['turns'][-1]['status']='completed';rows[thread]['status']={'type':'idle'};write(rows)
    out({'method':'item/agentMessage/delta','params':{'threadId':thread,'turnId':turn,'itemId':item['id'],'delta':text}})
    out({'method':'item/completed','params':{'threadId':thread,'turnId':turn,'item':item}})
    out({'method':'turn/completed','params':{'threadId':thread,'turn':{'id':turn,'status':'completed'}}})
for line in sys.stdin:
    msg=json.loads(line);method=msg.get('method');p=msg.get('params',{});req=msg.get('id')
    if method is None:
        if pending:finish(*pending);pending=None
        continue
    if req is None:continue
    rows=read();result={}
    if method=='initialize':result={'userAgent':'fixture'}
    elif method=='thread/list':result={'data':list(rows.values()),'nextCursor':None}
    elif method=='project/list':result={'data':[]}
    elif method=='thread/start':
        tid='fixture_'+uuid.uuid4().hex;thread={'id':tid,'name':'Fixture','cwd':p.get('cwd',''),'path':'','status':{'type':'idle'},'turns':[],'modelProvider':'openai'};rows[tid]=thread;write(rows);result={'thread':thread,'model':'fixture','modelProvider':'openai'}
    elif method=='thread/name/set':rows[p['threadId']]['name']=p['name'];write(rows)
    elif method in ['thread/read','thread/resume']:result={'thread':rows[p['threadId']],'model':'fixture','modelProvider':'openai'}
    elif method=='turn/start':
        tid=p['threadId'];turn='turn_'+uuid.uuid4().hex;text=p['input'][0].get('text','');rows[tid]['turns'].append({'id':turn,'status':'inProgress','items':[{'id':'user-'+turn,'type':'userMessage','content':[{'type':'text','text':text}]}]});rows[tid]['status']={'type':'active'};write(rows)
        out({'id':req,'result':{'turn':{'id':turn}}});out({'method':'turn/started','params':{'threadId':tid,'turn':{'id':turn}}})
        if 'approval-test' in text:
            pending=(tid,turn,'Approved fixture response');out({'id':9001,'method':'item/commandExecution/requestApproval','params':{'threadId':tid,'turnId':turn,'command':'fixture read-only command'}})
        else:finish(tid,turn,'Fixture 中文回复')
        continue
    elif method=='turn/interrupt':out({'method':'turn/completed','params':{'threadId':p['threadId'],'turn':{'id':p['turnId'],'status':'interrupted'}}});pending=None
    out({'id':req,'result':result})
'''

def main():
    with tempfile.TemporaryDirectory(prefix="remotetool-h5-") as temp:
        folder = Path(temp); home = folder / "codex-home"; home.mkdir()
        mock = folder / "codex"; mock.write_text(f"#!{sys.executable}\n" + MOCK); mock.chmod(0o700)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0)); port = probe.getsockname()[1]
        env = dict(os.environ, Auth__Username="fixture", Auth__Password="fixture-only-password",
                   Auth__JwtSecret="fixture-only-signing-key-at-least-thirty-two-characters",
                   Codex__Executable=str(mock), Codex__Home=str(home), Codex__AccountStore=str(folder / "accounts"),
                   Codex__WorkingDirectory=str(folder / "work"), REMOTETOOL_DATA_DIR=str(folder / "data"),
                   DOTNET_HOST_PATH=DOTNET, Hangfire__Database=str(folder / "hangfire.db"), ASPNETCORE_URLS=f"http://127.0.0.1:{port}")
        mac_agent = ROOT / "tools/RemoteTool.MacAgent/build/RemoteToolMacAgent.app/Contents/MacOS/RemoteToolMacAgent"
        if sys.platform == "darwin" and mac_agent.exists():
            env["RemoteControl__MacAgentExecutable"] = str(mac_agent)
        log = open(folder / "server.log", "w+")
        process = subprocess.Popen([DOTNET, str(ROOT / "WebApplication/bin/Debug/net8.0/WebApplication.dll")],
                                   cwd=ROOT / "WebApplication", env=env, stdout=log, stderr=log, start_new_session=True)
        base = f"http://127.0.0.1:{port}"
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        def request(path, data=None, method=None, key=None):
            headers = {"Content-Type":"application/json"}
            if key: headers["Idempotency-Key"] = key
            req = urllib.request.Request(base+path, data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
            with client.open(req, timeout=35) as response:
                raw = response.read().decode(); return json.loads(raw) if "json" in response.headers.get("Content-Type", "") else raw
        try:
            for _ in range(100):
                try: request("/api/device/identity"); break
                except (OSError, urllib.error.URLError):
                    if process.poll() is not None: raise RuntimeError("Host exited during startup")
                    time.sleep(.15)
            else: raise RuntimeError("Host startup timeout")
            try: request("/api/platform/capabilities"); raise AssertionError("Unauthenticated API accepted")
            except urllib.error.HTTPError as error: assert error.code == 401
            request("/api/auth/login", {"username":"fixture","password":"fixture-only-password"})
            assert request("/api/auth/me")["authenticated"]
            assert "app-shell" in request("/mobile/")
            assert "app-shell" in request("/remote/index")
            assert "app-shell" in request("/agents/codex")
            assert "app-shell" in request("/hangfire")
            expected_platform = "macOS" if sys.platform == "darwin" else "Windows" if sys.platform == "win32" else "Linux"
            assert request("/api/platform/capabilities")["platform"] == expected_platform
            if sys.platform == "darwin" and mac_agent.exists():
                info = request("/api/system/info")["data"]
                assert info["platform"] == "macOS" and info["totalMemoryMB"] > 0, info
                assert request("/api/system/processes")["data"]
                state = request("/api/input/session-state")
                assert state["available"] and not state["locked"] and state["width"] > 0, state
                assert request("/api/input/mouse-relative", {"dx":0,"dy":0})["message"] == "Input sent"
                try: request("/api/input/unlock", {"password":"unused"}); raise AssertionError("Mac accepted Windows unlock")
                except urllib.error.HTTPError as error: assert error.code == 501
                with client.open(base + "/api/stream/legacy", timeout=15) as stream:
                    assert stream.headers.get_content_type() == "multipart/x-mixed-replace"
                    assert b"\xff\xd8" in stream.read(1024)
            assert request("/api/codex/capabilities")["sessions"]
            created = request("/api/codex/sessions", {"project_mode":"none","title":"H5 fixture"})
            session = created["session"]["id"]
            assert any(row["id"] == session for row in request("/api/codex/sessions")["data"])
            body = {"session_id":session,"input":"read-only fixture task"}; key="fixture-request-0000000001"
            run = request("/api/codex/runs", body, key=key)["run_id"]
            assert request("/api/codex/runs", body, key=key)["run_id"] == run
            assert request("/api/codex/runs/lookup?key="+key)["run_id"] == run
            for _ in range(50):
                status = request("/api/codex/runs/"+run)
                if status["status"] == "completed": break
                time.sleep(.1)
            assert status["status"] == "completed", status
            assert "中文" in status["output"], status
            assert "message.delta" in request("/api/codex/runs/"+run+"/events")
            messages = request(f"/api/codex/sessions/{session}/messages")["data"]
            assert len([row for row in messages if row["role"] == "user"]) == 1
            approval = request("/api/codex/runs", {"session_id":session,"input":"approval-test"},key="fixture-approval-0000000002")["run_id"]
            for _ in range(50):
                status = request("/api/codex/runs/"+approval)
                if status.get("approval"): break
                time.sleep(.1)
            request(f"/api/codex/runs/{approval}/approval", {"request_id":status["approval"]["request_id"],"choice":"once"})
            for _ in range(50):
                status = request("/api/codex/runs/"+approval)
                if status["status"] == "completed": break
                time.sleep(.1)
            assert status["status"] == "completed"
            request("/api/auth/logout", {})
            try: request("/api/auth/me"); raise AssertionError("Logout retained authentication")
            except urllib.error.HTTPError as error: assert error.code == 401
            print("PASS: HTTP login/logout, H5 entry, native stdio bridge, sessions, task deduplication, SSE, approval")
        except Exception:
            log.flush(); log.seek(0); print(log.read()[-8000:],file=sys.stderr); raise
        finally:
            os.killpg(process.pid, signal.SIGTERM)
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: os.killpg(process.pid,signal.SIGKILL); process.wait()
            log.close()

if __name__ == "__main__": main()
