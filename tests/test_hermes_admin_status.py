"""网关状态里的 active 以带过期的轮次租约为准，避免 active_agents 漏减造成的假忙。"""
import importlib.util
import sqlite3
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path


def stub_gateway_status():
    """status() 只用到 _pid_exists；测试环境没有 hermes-agent 的 gateway 包。"""
    module = types.ModuleType('gateway.status')
    module._pid_exists = lambda pid: False
    package = types.ModuleType('gateway')
    package.status = module
    sys.modules.setdefault('gateway', package)
    sys.modules.setdefault('gateway.status', module)


def load_admin():
    stub_gateway_status()
    source = Path(__file__).resolve().parents[1] / 'WebApplication/Tools/HermesManagement/hermes_admin.py'
    spec = importlib.util.spec_from_file_location('hermes_admin_under_test', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_home(root: Path, leases):
    state = {"gateway_state": "running", "active_agents": 3, "pid": 0}
    (root / 'gateway_state.json').write_text(__import__('json').dumps(state), encoding='utf-8')
    (root / 'state').mkdir(parents=True, exist_ok=True)
    (root / 'state' / 'gateway.heartbeat').write_text(
        __import__('json').dumps({"pid": 0, "updated_at": "2000-01-01T00:00:00Z"}), encoding='utf-8')
    database = root / 'state.db'
    db = sqlite3.connect(database)
    try:
        db.execute("CREATE TABLE session_turn_leases(session_id TEXT, conversation_id TEXT, holder TEXT, acquired_at REAL, expires_at REAL)")
        now = time.time()
        for index, delta in enumerate(leases):
            db.execute("INSERT INTO session_turn_leases VALUES(?,?,?,?,?)",
                       (f's{index}', f'c{index}', 'holder', now, now + delta))
        db.commit()
    finally:
        db.close()
    return root


class ActiveStatusTests(unittest.TestCase):
    def test_expired_leases_never_count_and_stale_counter_is_ignored(self):
        admin = load_admin()
        with tempfile.TemporaryDirectory() as folder:
            home = make_home(Path(folder), [3600, -5, -60])
            leases, known = admin.live_leases(home)
            self.assertTrue(known)
            self.assertEqual(leases, 1)
            state = admin.status(home)
            self.assertEqual(state['active'], 1)
            self.assertEqual(state['active_agents'], 3)

    def test_profile_leases_are_included(self):
        admin = load_admin()
        with tempfile.TemporaryDirectory() as folder:
            home = make_home(Path(folder), [3600])
            profile = home / 'profiles' / 'feiyu'
            profile.mkdir(parents=True)
            db = sqlite3.connect(profile / 'state.db')
            try:
                db.execute("CREATE TABLE session_turn_leases(session_id TEXT, conversation_id TEXT, holder TEXT, acquired_at REAL, expires_at REAL)")
                db.execute("INSERT INTO session_turn_leases VALUES('p','pc','h',?,?)", (time.time(), time.time() + 3600))
                db.commit()
            finally:
                db.close()
            leases, known = admin.live_leases(home)
            self.assertTrue(known)
            self.assertEqual(leases, 2)
            self.assertEqual(admin.status(home)['active'], 2)

    def test_legacy_home_without_lease_table_falls_back_to_counter(self):
        admin = load_admin()
        with tempfile.TemporaryDirectory() as folder:
            home = make_home(Path(folder), [])
            (home / 'state.db').unlink()
            db = sqlite3.connect(home / 'state.db')
            try:
                db.execute("CREATE TABLE messages(id INTEGER)")
                db.commit()
            finally:
                db.close()
            leases, known = admin.live_leases(home)
            self.assertFalse(known)
            self.assertEqual(leases, 0)
            self.assertEqual(admin.status(home)['active'], 3)


if __name__ == '__main__':
    unittest.main()
