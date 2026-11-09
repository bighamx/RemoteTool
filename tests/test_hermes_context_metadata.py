import json
import os
import sys
import tempfile
import threading
import types
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
os.environ['HERMES_HOME'] = tempfile.mkdtemp(prefix='hermes-context-test-')
sys.path.insert(0, str(Path('WebApplication/Tools/HermesManagement').resolve()))
from hermes_context import bounded_context_metadata, endpoint_context, selected_endpoint


class ContextMetadataTests(unittest.TestCase):
    def test_exact_model_and_context_not_input_budget(self):
        row = {'id': 'qoder/dfmodel', 'context_length': 1000000, 'max_input_tokens': 180000}
        self.assertEqual(1000000, endpoint_context({'data': [row]}, row['id']))
        self.assertIsNone(endpoint_context({'data': [row]}, 'dfmodel'))
        self.assertIsNone(endpoint_context({'data': [row, row]}, row['id']))
        self.assertIsNone(endpoint_context({'data': [{'id': 'm', 'max_input_tokens': 180000}]}, 'm'))
        self.assertIsNone(endpoint_context({'data': [{'id': 'm', 'context_length': True}]}, 'm'))
        self.assertEqual(1000000, endpoint_context({'data': [{'id': 'm', 'metadata': {'context_window': '1000000'}}]}, 'm'))

    def test_session_lock_endpoint_and_credentials_are_isolated(self):
        settings = {'model': {'default': 'm', 'provider': 'other', 'base_url': 'http://other', 'api_key': 'other-secret'},
                    'custom_providers': [{'name': 'Local Console', 'base_url': 'http://console/v1', 'api_key': 'console-secret'}]}
        config = {'requested_provider': 'custom:local-console', 'browser_model_lock': {'model': 'm', 'provider': 'custom:local-console'}}
        self.assertEqual(('custom:local-console', 'http://console/v1', 'console-secret', None), selected_endpoint('m', config, settings))
        config['base_url'] = 'http://different/v1'
        self.assertEqual(('custom:local-console', 'http://different/v1', '', None), selected_endpoint('m', config, settings))

    def test_declared_key_uses_native_profile_dotenv_reader(self):
        native_config = types.ModuleType('hermes_cli.config')
        native_config.get_env_value_prefer_dotenv = lambda name: 'profile-key' if name == 'CONSOLE_KEY' else ''
        settings = {'custom_providers': [{'name': 'Console', 'base_url': 'http://console/v1', 'key_env': 'CONSOLE_KEY'}]}
        with patch.dict(sys.modules, {'hermes_cli.config': native_config}):
            self.assertEqual('profile-key', selected_endpoint('m', {'provider': 'custom:console'}, settings)[2])

    def test_real_models_response_wins_over_stale_native_cache(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                seen.append((self.path, self.headers.get('Authorization')))
                data = json.dumps({'data': [{'id': 'qoder/dfmodel', 'context_length': 1000000, 'max_input_tokens': 180000}]}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(data)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        base = 'http://127.0.0.1:' + str(server.server_port) + '/v1'
        metadata = types.ModuleType('agent.model_metadata')
        metadata.get_cached_context_length = lambda *a: 256000
        metadata.get_model_context_length = lambda *a, **k: 256000
        native_config = types.ModuleType('hermes_cli.config')
        native_config.load_config = lambda: {'custom_providers': [{'name': 'Console', 'base_url': base, 'api_key': 'fixture-key'}]}
        try:
            with patch.dict(sys.modules, {'agent.model_metadata': metadata, 'hermes_cli.config': native_config}):
                config = {'browser_model_lock': {'provider': 'custom:console', 'model': 'qoder/dfmodel'}}
                result = bounded_context_metadata('qoder/dfmodel', config)
                self.assertEqual({'limit': 1000000, 'limit_source': 'provider_models'}, result)
                self.assertEqual([('/v1/models', 'Bearer fixture-key')], seen)
                config['context_length'] = 200000
                self.assertEqual({'limit': 200000, 'limit_source': 'configuration'}, bounded_context_metadata('qoder/dfmodel', config))
                self.assertEqual(1, len(seen))
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
