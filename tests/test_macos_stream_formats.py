"""Exercise Android Annex B and browser MP4 over HTTP using generated NV12 frames.

Runs the real Mac VideoToolbox encoder without requiring screen capture permission.
Build the server first; REMOTETOOL_TEST_DOTNET selects the .NET host.
"""
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

if sys.platform != "darwin":
    raise SystemExit("SKIP: requires macOS VideoToolbox")

root = Path(__file__).resolve().parent.parent
dotnet = os.environ.get("REMOTETOOL_TEST_DOTNET", "dotnet")
server = root / "WebApplication/bin/Release/net8.0/WebApplication.dll"
ffmpeg = shutil.which("ffmpeg")
assert server.is_file() and ffmpeg, "Build Release and install FFmpeg first"

with tempfile.TemporaryDirectory(prefix="remotetool-stream-") as temporary:
    folder = Path(temporary)
    agent = folder / "generated-capture"
    agent.write_text(f"#!{sys.executable}\n" + r'''
import json, struct, sys, time
from pathlib import Path
if '--raw-stream' in sys.argv:
    out=sys.stdout.buffer
    out.write(b'RTV1'+struct.pack('<ii',64,64));out.flush()
    try:
        for i in range(40):
            out.write(bytes([32+i])*4096+bytes([128])*2048);out.flush();time.sleep(.05)
    except BrokenPipeError:pass
else:
    for line in sys.stdin:
        request=json.loads(line)
        with (Path(__file__).parent/'input-events.jsonl').open('a') as events:
            events.write(json.dumps(request)+'\n')
        state=json.loads((Path(__file__).parent/'state.json').read_text())
        if request.get('type')=='status':
            time.sleep(state.pop('statusDelaySeconds',0))
        print(json.dumps(state),flush=True)
''')
    agent.chmod(0o700)
    state = {'displayId': 1, 'displays': [{'id': 1}], 'sessionLocked': False,
             'screenRecording': True, 'accessibility': True, 'width': 64, 'height': 64}
    state_file = folder / 'state.json'
    state_file.write_text(json.dumps(state))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    env = dict(os.environ, Auth__Username="fixture", Auth__Password="fixture-password",
               Auth__JwtSecret="fixture-signing-key-at-least-thirty-two-bytes",
               REMOTETOOL_DATA_DIR=str(folder / "data"),
               Hangfire__Database=str(folder / "jobs.db"),
               RemoteControl__MacAgentExecutable=str(agent),
               REMOTETOOL_FFMPEG_PATH=ffmpeg, ASPNETCORE_URLS=f"http://127.0.0.1:{port}")
    with (folder / "server.log").open("w+") as log:
        process = subprocess.Popen([dotnet, str(server)], cwd=root / "WebApplication",
                                   env=env, stdout=log, stderr=log)
        base = f"http://127.0.0.1:{port}"
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        try:
            for _ in range(100):
                try:
                    client.open(base + "/api/device/identity", timeout=1).close()
                    break
                except OSError:
                    assert process.poll() is None, "Host exited during startup"
                    time.sleep(.1)
            else:
                raise AssertionError("Host startup timed out")
            try:
                client.open(base + "/api/stream?format=h264", timeout=5)
                raise AssertionError("Unauthenticated desktop stream accepted")
            except urllib.error.HTTPError as error:
                assert error.code == 401
            login = urllib.request.Request(base + "/api/auth/login",
                data=json.dumps({"username":"fixture", "password":"fixture-password"}).encode(),
                headers={"Content-Type":"application/json"})
            client.open(login, timeout=5).close()
            with client.open(base + '/api/platform/capabilities', timeout=5) as response:
                capabilities = json.load(response)
                assert capabilities['desktopFormat'] == 'mp4' and capabilities['desktopCollaboration']
            lock = urllib.request.Request(base + '/api/system/lock', data=b'{}', headers={'Content-Type': 'application/json'})
            with client.open(lock, timeout=5) as response:
                assert json.load(response)['success']
            events = [json.loads(line) for line in (folder/'input-events.jsonl').read_text().splitlines()]
            assert [event.get('code') for event in events if event['type']=='key'] == ['ControlLeft','MetaLeft','KeyQ']
            assert events[-1]['type'] == 'release', 'Lock shortcut must release modifiers even after the screen locks'
            print('PASS: macOS capabilities and lock shortcut through isolated agent')
            for query, content_type, extension in [("?format=h264", "video/h264", "h264"), ("", "video/mp4", "mp4")]:
                with client.open(base + "/api/stream" + query, timeout=20) as response:
                    assert response.headers.get_content_type() == content_type
                    media = response.read()
                assert len(media) > 100, "Empty video output"
                if extension == "h264":
                    assert media.startswith((b"\x00\x00\x00\x01", b"\x00\x00\x01")), media[:12]
                    assert b"\x00\x00\x01\x09" in media, "Android frame delimiter missing"
                else:
                    assert media[4:8] == b"ftyp", media[:12]
                video = folder / ("output." + extension)
                video.write_bytes(media)
                result = subprocess.run([ffmpeg, "-v", "error", "-i", str(video), "-f", "null", "-"],
                                        capture_output=True, timeout=20)
                assert result.returncode == 0, result.stderr.decode()
                print("PASS:", content_type, "real VideoToolbox video decoded successfully")
            for update, expected in [({'sessionLocked': True, 'displays': []}, 423),
                                     ({'screenRecording': False}, 403)]:
                state_file.write_text(json.dumps({**state, **update}))
                for route in ['/api/stream?format=h264', '/api/stream/legacy']:
                    started = time.monotonic()
                    try:
                        client.open(base + route, timeout=5)
                        raise AssertionError('Unavailable desktop accepted')
                    except urllib.error.HTTPError as error:
                        assert error.code == expected, (route, error.code)
                        body = json.load(error)
                        assert body['message']
                        if expected == 423:
                            assert body['locked'] and not body['unlockSupported']
                    assert time.monotonic() - started < 3, 'Unavailable desktop must fail before waiting for video'
                with client.open(base + '/api/input/session-state', timeout=5) as response:
                    assert json.load(response)['message']
                print('PASS: desktop preflight', expected, 'for H.264, MJPEG and session state')
            # The real bridge cancels an unresponsive helper after 15 seconds. Both
            # preflight callers must return a usable 503, not an unhandled 500.
            state_file.write_text(json.dumps({**state, 'statusDelaySeconds': 20}))
            for route in ['/api/stream?format=h264', '/api/stream/legacy']:
                try:
                    client.open(base + route, timeout=25)
                    raise AssertionError('Unresponsive desktop accepted')
                except urllib.error.HTTPError as error:
                    assert error.code == 503, (route, error.code)
                    assert '超时' in json.load(error)['message']
                print('PASS: desktop status timeout returns JSON 503', route)
            state_file.write_text(json.dumps(state))
            with client.open(base + '/api/input/session-state', timeout=5) as response:
                assert not json.load(response)['locked'], 'Helper must recover after a timed-out process is stopped'
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
