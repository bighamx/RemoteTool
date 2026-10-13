"""Exercise the packaged macOS PTY helper with a real interactive zsh."""
import os
from pathlib import Path
import select
import signal
import struct
import subprocess
import sys
import time

if sys.platform != "darwin":
    raise SystemExit("SKIP: macOS only")

root = Path(__file__).resolve().parent.parent
helper = Path(os.environ.get("REMOTETOOL_TEST_PTY_HELPER", root / "tools/RemoteTool.MacAgent/build/RemoteToolMacAgent.app/Contents/MacOS/RemoteToolPtyAgent"))
if not helper.is_file():
    raise SystemExit(f"Build the Mac agent first: {helper}")

process = subprocess.Popen([str(helper)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, start_new_session=True)

def frame(opcode, payload):
    process.stdin.write(bytes([opcode]) + struct.pack("<I", len(payload)) + payload)
    process.stdin.flush()

def until(marker, timeout=5):
    output = b""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if select.select([process.stdout], [], [], 0.1)[0]:
            output += os.read(process.stdout.fileno(), 8192)
            if marker in output:
                return output
    raise AssertionError(f"PTY output never contained {marker!r}: {output[-300:]!r}")

try:
    frame(2, struct.pack("<HH", 92, 31))
    frame(1, b"stty size\r")
    assert b"31 92" in until(b"31 92")
    frame(1, b"sleep 10; echo PTY_NOT_INTERRUPTED\r")
    until(b"sleep 10")
    frame(1, b"\x03")
    frame(1, b"printf 'PTY_AFTER_CTRL_C_%s\\n' $((6*7))\r")
    output = until(b"PTY_AFTER_CTRL_C_42", timeout=4)
    assert b"PTY_NOT_INTERRUPTED" not in output.split(b"PTY_AFTER_CTRL_C_42")[-1]
    print("PASS: PTY resize, interactive zsh, Ctrl+C and continued input")
finally:
    process.stdin.close()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=3)
