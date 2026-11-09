"""A stalled optional model probe must not stall token responses or process exit."""
import ast
import os
import sys
import tempfile
import time
import types
from pathlib import Path
# 窗口缓存落在 HERMES_HOME 下，测试用临时目录，别污染本机真实缓存。
os.environ['HERMES_HOME'] = tempfile.mkdtemp(prefix='hermes-context-test-')
sys.path.insert(0, str(Path('WebApplication/Tools/HermesManagement').resolve()))

tree = ast.parse(Path('WebApplication/Tools/HermesManagement/hermes_management.py').read_text(encoding='utf-8'))
function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'bounded_context_limit')
namespace = {}
exec(compile(ast.Module(body=[function], type_ignores=[]), '<context-budget>', 'exec'), namespace)
metadata = types.ModuleType('agent.model_metadata')
metadata.get_cached_context_length = lambda *args: None
config = types.ModuleType('hermes_cli.config')
config.load_config = lambda: {'model': {'default': 'current', 'provider': 'custom', 'base_url': 'http://localhost'}}
import hermes_context
# 只在本用例内禁用端点探测，退出时还原，别污染同进程里的其它用例。
_original_probe = hermes_context.probe_endpoint
hermes_context.probe_endpoint = lambda *args: None
sys.modules['agent.model_metadata'] = metadata
sys.modules['hermes_cli.config'] = config
calls = []
def fast(model, base_url, **kwargs):
    calls.append((model, base_url, kwargs['provider']))
    return 123456
metadata.get_model_context_length = fast
assert namespace['bounded_context_limit']('current', {}) == 123456
assert calls[-1] == ('current', 'http://localhost', 'custom')
assert namespace['bounded_context_limit']('other', {'base_url': 'http://example', 'provider': 'other'}) == 123456
assert calls[-1] == ('other', 'http://example', 'other')
def stalled(*args, **kwargs):
    time.sleep(60)
metadata.get_model_context_length = stalled
start = time.monotonic()
# 未缓存过的模型才会走解析；已缓存的键会立即返回(这是修复后想要的行为)。
assert namespace['bounded_context_limit']('never-resolved', {'base_url': 'http://stalled', 'provider': 'other'}) is None
assert time.monotonic() - start < 4
hermes_context.probe_endpoint = _original_probe
print('PASS: provider-specific metadata; stalled lookup returns in 3 seconds; daemon permits process exit')
