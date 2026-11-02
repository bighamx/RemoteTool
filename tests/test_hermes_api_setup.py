import importlib.util
import importlib.machinery
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

class HermesApiSetupTests(unittest.TestCase):
    def load(self,root):
        bootstrap=types.ModuleType('hermes_bootstrap')
        bootstrap.__spec__=importlib.machinery.ModuleSpec('hermes_bootstrap',None)
        constants=types.ModuleType('hermes_constants');constants.get_hermes_home=lambda:root
        with patch.dict(sys.modules,{'hermes_bootstrap':bootstrap,'hermes_constants':constants}):
            spec=importlib.util.spec_from_file_location('setup_test_module','WebApplication/Tools/HermesManagement/hermes_management.py')
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            return module.api_server_action('enable_api_server',{'restart':False,'api_server_key':'existing-managed-test-key'})
    def test_disabled_flag_is_enabled_without_rotating_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);env=root/'.env'
            env.write_text('API_SERVER_ENABLED=0\nAPI_SERVER_HOST=127.0.0.1\nAPI_SERVER_PORT=8642\nAPI_SERVER_KEY=original-test-key\nMODEL_KEY=keep\n')
            result=self.load(root);text=env.read_text()
            self.assertIn('API_SERVER_ENABLED=1',text);self.assertIn('API_SERVER_KEY=original-test-key',text);self.assertIn('MODEL_KEY=keep',text)
            self.assertTrue(result['data']['enabled']);self.assertNotIn('original-test-key',json.dumps(result))
    def test_missing_native_key_reuses_managed_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'.env').write_text('API_SERVER_ENABLED=false\n')
            self.load(root)
            self.assertIn('API_SERVER_KEY=existing-managed-test-key',(root/'.env').read_text())

if __name__=='__main__':unittest.main()
