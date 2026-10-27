import importlib.util
import ast
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class RestartWorkerTests(unittest.TestCase):
    def test_legacy_helper_rejects_restart_before_reading_or_writing_config(self):
        source = Path(__file__).resolve().parents[1] / 'tools/HermesManagement/hermes_management.py'
        tree = ast.parse(source.read_text(encoding='utf-8-sig'))
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'api_server_action')
        namespace = {}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(source), 'exec'), namespace)
        # No Hermes modules or filesystem access are available in this namespace.
        with self.assertRaisesRegex(RuntimeError, '交互式'):
            namespace['api_server_action']('enable_api_server', {'restart': True})

    def test_restart_uses_official_lifecycle_and_verifies_replacement(self):
        self.run_worker(1, 1, 'completed', True)

    def test_service_session_worker_cannot_restart(self):
        self.run_worker(0, 1, 'failed', False)

    def test_gateway_in_another_session_is_not_stopped(self):
        self.run_worker(1, 0, 'failed', False)

    def test_replacement_in_session_zero_is_failure(self):
        self.run_worker(1, 1, 'failed', True, replacement_session=0)

    def test_update_stop_error_recovers_after_confirmed_exit(self):
        self.run_worker(1, 1, 'failed', True, update_stop_failure=True)

    def test_recovery_does_not_treat_a_live_pid_as_exited(self):
        source = Path(__file__).resolve().parents[1] / 'tools/HermesManagement/hermes_ops.py'
        spec = importlib.util.spec_from_file_location('restart_worker', source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with patch.dict(sys.modules, {'gateway.status': SimpleNamespace(_pid_exists=lambda _: True)}), \
             patch.object(module.time, 'monotonic', side_effect=[0, 91]):
            self.assertFalse(module.wait_for_gateway_exit(11))

    def run_worker(self, worker_session, gateway_session, expected_phase, expected_command, replacement_session=None, update_stop_failure=False):
        source = Path(__file__).resolve().parents[1] / 'tools/HermesManagement/hermes_ops.py'
        spec = importlib.util.spec_from_file_location('restart_worker', source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            job = home / 'cache/remotetool-maintenance' / ('a' * 32)
            job.mkdir(parents=True)
            (home / 'bin').mkdir()
            (home / 'bin/hermes.exe').touch()
            request = dict(home=str(home), source=str(home), key='a' * 32, wait=False, mode='update' if update_stop_failure else 'restart', task='test')
            (job / 'request.json').write_text(json.dumps(request))
            module.atomic(job.parent / 'status.json', dict(key=request['key'], phase='queued'))
            commands = []
            def invoke(action, body):
                if action == 'admin_gateway_stop':
                    if update_stop_failure:
                        raise RuntimeError('Stop timed out before process exit was observed')
                    self.fail('Restart must not use the old 20-second private stop path')
                if action == 'admin_gateway_cancel':
                    return {'ok': True}
                return dict(pid=22 if commands else 11, active=0, state='draining' if action.endswith('drain') else 'running',
                            running=True, session_id=replacement_session if commands and replacement_session is not None else gateway_session,
                            fresh=True, code_sha='sha', platforms={'api_server': 'connected'})
            def require_interactive_session():
                if worker_session == 0:
                    raise RuntimeError('Session 0 rejected')
                return worker_session
            def popen(args, **kwargs):
                commands.append(args)
                return SimpleNamespace(stdout=io.BytesIO(b'official restart output\n'), wait=lambda: 0)
            old_stdout, old_stderr = sys.stdout, sys.stderr
            try:
                with patch.dict(sys.modules, {'hermes_bootstrap': SimpleNamespace(), 'hermes_admin': SimpleNamespace(invoke=invoke, require_interactive_session=require_interactive_session),
                                              'gateway.status': SimpleNamespace(_pid_exists=lambda _: False),
                                              'dotenv': SimpleNamespace(dotenv_values=lambda _: {'API_SERVER_KEY': 'test'})}), \
                     patch.object(sys, 'argv', ['worker', 'run', str(job / 'request.json')]), \
                     patch.object(module.subprocess, 'Popen', side_effect=popen), \
                     patch.object(module.subprocess, 'run', return_value=SimpleNamespace(stdout='sha\n')), \
                     patch.object(module.time, 'sleep'), \
                     patch('urllib.request.urlopen') as urlopen:
                    urlopen.return_value.__enter__.return_value.status = 200
                    module.main()
            finally:
                if sys.stdout is not old_stdout:
                    sys.stdout.close()
                sys.stdout, sys.stderr = old_stdout, old_stderr
            expected_args = ['gateway', 'start'] if update_stop_failure else ['gateway', 'restart', '--detach']
            self.assertEqual(commands, [[str(home / 'bin/hermes.exe'), *expected_args]] if expected_command else [])
            self.assertEqual(json.loads((job.parent / 'status.json').read_text())['phase'], expected_phase)
            if expected_command:
                self.assertIn('official restart output', (job / 'maintenance.log').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
