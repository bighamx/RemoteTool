"""Read context metadata from the selected endpoint without changing native config."""
import json
import os
import threading
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


def positive_limit(value):
    if isinstance(value, bool): return None
    if isinstance(value, str) and value.isdecimal(): value = int(value)
    return value if isinstance(value, int) and 0 < value <= 100_000_000 else None


def endpoint_context(payload, model):
    rows = payload.get('data') if isinstance(payload, dict) else None
    if not isinstance(rows, list): return None
    matches = [r for r in rows if isinstance(r, dict) and r.get('id') == model]
    if len(matches) != 1: return None
    row = matches[0]
    for data in (row, row.get('metadata')):
        if not isinstance(data, dict): continue
        for field in ('context_length', 'context_window', 'max_context_tokens', 'max_position_embeddings'):
            limit = positive_limit(data.get(field))
            if limit: return limit
    return None


def selected_endpoint(model, config, settings):
    from hermes_provider_compat import _rows
    lock = config.get('browser_model_lock') or {}
    if not isinstance(lock, dict) or lock.get('model', model) != model: lock = {}
    provider = lock.get('provider') or config.get('requested_provider') or config.get('provider') or ''
    if provider == 'custom' and config.get('requested_provider'): provider = config['requested_provider']
    base = lock.get('base_url') or config.get('base_url') or ''
    key = configured_key(lock) or configured_key(config)
    explicit = positive_limit(lock.get('context_length')) or positive_limit(config.get('context_length'))
    main = settings.get('model') or {}
    if not isinstance(main, dict): main = {}
    # Only inherit global settings when this session identifies the same provider.
    same = provider == main.get('provider') if provider else main.get('default', main.get('name')) == model
    if same:
        provider = provider or main.get('provider') or ''
        base = base or main.get('base_url') or ''
        if not key and base.rstrip('/') == str(main.get('base_url') or '').rstrip('/'):
            key = configured_key(main)
    matches = []
    for ident, entry, source, slug in _rows(settings):
        url = entry.get('base_url') or entry.get('api') or ''
        aliases = {ident, slug}
        if source == 'custom_providers': aliases.add('custom:' + ident)
        if provider in aliases or provider in ('', 'custom', 'auto') and base and url.rstrip('/') == base.rstrip('/'):
            matches.append(entry)
    if len(matches) == 1:
        entry = matches[0]; url = entry.get('base_url') or entry.get('api') or ''
        if not base or base.rstrip('/') == url.rstrip('/'):
            base = base or url
            key = key or configured_key(entry)
            explicit = explicit or positive_limit(entry.get('context_length'))
    if not explicit and same: explicit = positive_limit(main.get('context_length'))
    return provider, base, key, explicit


def configured_key(entry):
    key = entry.get('api_key')
    if isinstance(key, str) and key: return key
    name = entry.get('key_env') or entry.get('api_key_env')
    if not isinstance(name, str) or not name: return ''
    try:
        from hermes_cli.config import get_env_value_prefer_dotenv
        return get_env_value_prefer_dotenv(name) or ''
    except ImportError:
        return os.environ.get(name, '')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None


def probe_endpoint(model, base, key):
    parsed = urlsplit(base)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        return None
    path = parsed.path.rstrip('/')
    for suffix in ('/chat/completions', '/responses', '/messages'):
        if path.endswith(suffix): path = path[:-len(suffix)]
    url = urlunsplit((parsed.scheme, parsed.netloc, path + '/models', '', ''))
    headers = {'Accept': 'application/json'}
    if isinstance(key, str) and key: headers['Authorization'] = 'Bearer ' + key
    # Never send provider credentials to a redirect destination.
    with build_opener(NoRedirect()).open(Request(url, headers=headers), timeout=1.5) as response:
        data = response.read(2_000_001)
    if len(data) > 2_000_000: return None
    return endpoint_context(json.loads(data), model)


def bounded_context_metadata(model, config):
    result = []
    def resolve():
        try:
            from hermes_cli.config import load_config
            from agent.model_metadata import get_model_context_length, get_cached_context_length
            settings = load_config()
            provider, base, key, explicit = selected_endpoint(model, config, settings)
            if explicit:
                result.append({'limit': explicit, 'limit_source': 'configuration'}); return
            if base:
                try: limit = probe_endpoint(model, base, key)
                except Exception: limit = None
                if limit:
                    result.append({'limit': limit, 'limit_source': 'provider_models'}); return
            cached = positive_limit(get_cached_context_length(model, base))
            if cached:
                result.append({'limit': cached, 'limit_source': 'hermes_cache'}); return
            limit = get_model_context_length(model, base, api_key=key, provider=provider)
            if positive_limit(limit): result.append({'limit': limit, 'limit_source': 'hermes'})
        except Exception:
            pass
    worker = threading.Thread(target=resolve, daemon=True)
    worker.start(); worker.join(3)
    return result[0] if result else {'limit': None, 'limit_source': 'unavailable'}
