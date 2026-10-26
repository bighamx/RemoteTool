import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class RestartWorkerTests(unittest.TestCase):
    def test_restart_uses_official_lifecycle_and_verifies_replacement(self):
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
            request = dict(home=str(home), source=str(home), key='a' * 32, wait=False, mode='restart', task='test')
            (job / 'request.json').write_text(json.dumps(request))
            module.atomic(job.parent / 'status.json', dict(key=request['key'], phase='queued'))
            commands = []
            def invoke(action, body):
                if action == 'admin_gateway_stop':
                    self.fail('Restart must not use the old 20-second private stop path')
                if action == 'admin_gateway_cancel':
                    return {'ok': True}
                return dict(pid=22 if commands else 11, active=0, state='draining' if action.endswith('drain') else 'running',
                            running=True, fresh=True, code_sha='sha', platforms={'api_server': 'connected'})
            def popen(args, **kwargs):
                commands.append(args)
                return SimpleNamespace(stdout=io.BytesIO(b'official restart output\n'), wait=lambda: 0)
            old_stdout, old_stderr = sys.stdout, sys.stderr
            try:
                with patch.dict(sys.modules, {'hermes_bootstrap': SimpleNamespace(), 'hermes_admin': SimpleNamespace(invoke=invoke),
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
            self.assertEqual(commands, [[str(home / 'bin/hermes.exe'), 'gateway', 'restart', '--detach']])
            self.assertEqual(json.loads((job.parent / 'status.json').read_text())['phase'], 'completed')
            self.assertIn('official restart output', (job / 'maintenance.log').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
