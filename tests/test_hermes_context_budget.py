"""A stalled optional model probe must not stall token responses or process exit."""
import ast
import sys
import time
import types
from pathlib import Path

tree = ast.parse(Path('tools/HermesManagement/hermes_management.py').read_text(encoding='utf-8'))
function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'bounded_context_limit')
namespace = {}
exec(compile(ast.Module(body=[function], type_ignores=[]), '<context-budget>', 'exec'), namespace)
metadata = types.ModuleType('agent.model_metadata')
config = types.ModuleType('hermes_cli.config')
config.load_config = lambda: {'model': {'default': 'current', 'provider': 'custom', 'base_url': 'http://localhost'}}
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
assert namespace['bounded_context_limit']('current', {}) is None
assert time.monotonic() - start < 4
print('PASS: provider-specific metadata; stalled lookup returns in 3 seconds; daemon permits process exit')
