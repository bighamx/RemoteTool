import importlib.util,json,sys,tempfile,types,unittest
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('hermes_provider_compat',Path(__file__).parents[1]/'WebApplication/Tools/HermesManagement/hermes_provider_compat.py')
compat=importlib.util.module_from_spec(spec);spec.loader.exec_module(compat)

class CoreProviderTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.home=Path(self.temp.name);self.path=self.home/'config.yaml'
  self.path.write_text(json.dumps({'model':{'provider':'openai-codex','default':'official'},'unrelated':'${KEEP_ME}','custom_providers':[{'name':'Existing','base_url':'http://localhost:9/v1','model':'old','api_key':'fixture-secret'}]}),encoding='utf-8')
  package=types.ModuleType('hermes_cli');package.__path__=[]
  config=types.ModuleType('hermes_cli.config');config.load_config=lambda:json.loads(self.path.read_text())
  config.save_config=lambda value,**kwargs:self.path.write_text(json.dumps(value),encoding='utf-8')
  constants=types.ModuleType('hermes_constants');constants.get_hermes_home=lambda:self.home
  yaml=types.ModuleType('yaml');yaml.safe_load=json.loads
  self.patch=patch.dict(sys.modules,{'hermes_cli':package,'hermes_cli.config':config,'hermes_constants':constants,'yaml':yaml});self.patch.start()
 def tearDown(self):self.patch.stop();self.temp.cleanup()
 def test_core_add_edit_delete_without_web_dependency(self):
  result=compat.operate('save_provider',{'name':'测试本地','base_url':'http://localhost:9/v1','model':'fixture','api_mode':'codex_responses'})
  row=next(r for r in result['endpoints'] if r['name']=='测试本地');self.assertEqual(row['provider_slug'],'custom:测试本地')
  self.assertNotIn('fixture-secret',json.dumps(result))
  compat.operate('save_provider',{'id':'existing','name':'Existing','base_url':'http://localhost:10/v1','model':'new','api_key':''})
  raw=json.loads(self.path.read_text());self.assertEqual(raw['custom_providers'][0]['api_key'],'fixture-secret');self.assertEqual(raw['unrelated'],'${KEEP_ME}')
  compat.operate('delete_provider',{'id':row['id']});self.assertEqual(len(json.loads(self.path.read_text())['custom_providers']),1)
 def test_current_provider_and_running_gateway_cannot_be_deleted(self):
  raw=json.loads(self.path.read_text());raw['model']['provider']='custom:existing';self.path.write_text(json.dumps(raw))
  before=self.path.read_bytes()
  with self.assertRaises(compat.ProviderConfigError):compat.operate('delete_provider',{'id':'existing'})
  self.assertEqual(before,self.path.read_bytes())
  (self.home/'gateway_state.json').write_text('{"active_agents":1}')
  with self.assertRaises(compat.ProviderConfigError):compat.operate('delete_provider',{'id':'existing'})
 def test_invalid_endpoint_preserves_all_config(self):
  before=self.path.read_bytes()
  with self.assertRaises(compat.ProviderConfigError):compat.operate('save_provider',{'name':'bad','base_url':'http://secret:key@localhost/v1','model':'m'})
  self.assertEqual(before,self.path.read_bytes())

if __name__=='__main__':unittest.main()
