"""Validate phone dynamic-tool registration against a real app-server, without inference."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("executable"); args = parser.parse_args()
    catalog = json.loads((Path(__file__).resolve().parents[1] / "hermes_plugin" / "tools.json").read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="remotetool-phone-codex-") as directory:
        env = dict(os.environ, CODEX_HOME=directory)
        process = subprocess.Popen([args.executable, "app-server", "--stdio"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, encoding="utf-8", env=env)
        messages = queue.Queue()
        def read():
            for line in process.stdout:
                try:
                    messages.put(json.loads(line))
                except ValueError:
                    pass
        threading.Thread(target=read, daemon=True).start()
        def send(message):
            process.stdin.write(json.dumps(message) + "\n"); process.stdin.flush()
        def result(identifier):
            while True:
                message = messages.get(timeout=15)
                if message.get("id") == identifier:
                    if "error" in message:
                        raise RuntimeError(message["error"])
                    return message["result"]
        try:
            send({"id": 1, "method": "initialize", "params": {"clientInfo": {"name": "remotetool_phone_test", "version": "1.0.0"}, "capabilities": {"experimentalApi": True}}})
            result(1); send({"method": "initialized"})
            tools = [{"type": "function", "name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"]} for t in catalog]
            send({"id": 2, "method": "thread/start", "params": {"cwd": directory, "ephemeral": True, "dynamicTools": tools}})
            thread = result(2)["thread"]
            assert thread.get("id"), "No thread ID returned"
            print(f"Real Codex app-server accepted {len(tools)} phone dynamic tools; no inference started.")
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate(); process.wait(timeout=5)
            process.stdout.close()


if __name__ == "__main__":
    main()
