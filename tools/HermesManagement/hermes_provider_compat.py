"""Provider config operations for core-only Hermes installs (no web UI dependency)."""
import copy
import hashlib
import os
import re
from urllib.parse import urlsplit

class ProviderConfigError(ValueError):
    """Only fixed user-facing validation messages, never raw credentials."""

def _identifier(name):
    return re.sub(r'[^a-z0-9_-]+','-',str(name).lower()).strip('-') or 'provider_'+hashlib.sha256(str(name).encode()).hexdigest()[:12]


def _rows(config):
    rows=[]
    for key,entry in (config.get('providers') or {}).items():
        if not isinstance(entry,dict) or not (entry.get('base_url') or entry.get('api')): continue
        rows.append((str(key),entry,'providers',str(key)))
    for entry in config.get('custom_providers') or []:
        if not isinstance(entry,dict) or not entry.get('name'): continue
        name=str(entry['name']);key=_identifier(name)
        rows.append((key,entry,'custom_providers','custom:'+name.lower()))
    return rows


def _listing(config):
    main=config.get('model') or {};main=main if isinstance(main,dict) else {'default':main}
    rows=[]
    for key,e,source,slug in _rows(config):
        raw=e.get('models') or [];models=list(raw) if isinstance(raw,list) else []
        model=e.get('model') or e.get('default_model') or (models[0] if models else '')
        rows.append({'id':key,'name':e.get('name') or key,'base_url':e.get('base_url') or e.get('api') or '',
            'model':model,'models':models,'api_mode':e.get('api_mode') or '',
            'has_api_key':bool(e.get('api_key') or e.get('key_env')),'source':source,'provider_slug':slug,
            'is_current':main.get('provider') in {key,slug}})
    return {'endpoints':rows,'current':main.get('provider') or ''}


def operate(action,body):
    if action=='delete_provider':
        from hermes_constants import get_hermes_home
        from hermes_cli.config import load_config
        import json,sqlite3,time
        home=get_hermes_home();state=home/'gateway_state.json'
        if state.exists():
            try:busy=int(json.loads(state.read_text(encoding='utf-8-sig')).get('active_agents') or 0)>0
            except (ValueError,OSError):raise ProviderConfigError('无法确认网关状态，已取消删除，请刷新后重试') from None
            if busy:raise ProviderConfigError('Hermes 正在执行任务，请等待结束后再删除供应商')
        database=home/'state.db'
        if database.exists():
            with sqlite3.connect(database.as_uri()+'?mode=ro',uri=True) as db:
                if db.execute("SELECT 1 FROM sqlite_master WHERE name='session_turn_leases'").fetchone() and db.execute('SELECT 1 FROM session_turn_leases WHERE expires_at>? LIMIT 1',(time.time(),)).fetchone():
                    raise ProviderConfigError('Hermes 正在执行任务，请等待结束后再删除供应商')
        config=load_config();main=config.get('model') or {};main=main if isinstance(main,dict) else {}
        for key,entry,source,slug in _rows(config):
            if key==body.get('id') and (main.get('provider') in {key,slug} or
                main.get('provider')=='custom' and main.get('base_url')==(entry.get('base_url') or entry.get('api'))):
                raise ProviderConfigError('此供应商是全局默认，请先切换默认供应商后再删除')
    # Use the native dashboard adapter where it is installed. The gateway/PyPI
    # core-only install deliberately need not ship hermes_cli.web_routers.
    try:
        from hermes_cli.web_routers.config_env import list_custom_endpoints,upsert_custom_endpoint,delete_custom_endpoint
        from hermes_cli.web_routers.models import get_model_info,set_model_assignment
        from hermes_cli.web_models import CustomEndpointUpdate,ModelAssignment
    except ModuleNotFoundError as error:
        if not (error.name or '').startswith(('hermes_cli.web_','fastapi')): raise
    else:
        if action=='providers':return list_custom_endpoints()
        if action=='save_provider':
            try:validated=CustomEndpointUpdate.model_validate(body)
            except ValueError:raise ProviderConfigError('Provider 配置无效，请检查名称、API 地址、默认模型和接口类型') from None
            return upsert_custom_endpoint(validated)
        if action=='delete_provider':return delete_custom_endpoint(body['id'])
        if action=='model_info':return get_model_info()
        if action=='default_model':
            import asyncio
            return asyncio.run(set_model_assignment(ModelAssignment.model_validate({**body,'scope':'main'})))

    from hermes_cli.config import load_config,save_config
    from hermes_constants import get_hermes_home
    import yaml
    path=get_hermes_home()/'config.yaml'
    # Edit raw config so ${ENV} references and unrelated credentials survive.
    raw=yaml.safe_load(path.read_text(encoding='utf-8')) if path.exists() else {}
    if raw is None:raw={}
    if not isinstance(raw,dict):raise ProviderConfigError('Hermes config.yaml 必须是配置对象')
    resolved=load_config()
    if action=='providers':return _listing(resolved)
    main=raw.get('model') or {};main=copy.deepcopy(main) if isinstance(main,dict) else {'default':main}
    if action=='model_info':return {'provider':main.get('provider',''),'model':main.get('default') or main.get('name') or '',
        'reasoning_effort':(raw.get('agent') or {}).get('reasoning_effort','')}
    if action=='default_model':
        if not str(body.get('model') or '').strip() or not str(body.get('provider') or '').strip():raise ProviderConfigError('请选择供应商并填写模型')
        main.update(provider=body['provider'],default=body['model']);raw['model']=main
        if 'reasoning_effort' in body:
            if not body['reasoning_effort']:raw.setdefault('agent',{}).pop('reasoning_effort',None)
            else:raw.setdefault('agent',{})['reasoning_effort']=False if body['reasoning_effort']=='none' else body['reasoning_effort']
    else:
        key=str(body.get('id') or _identifier(body.get('name') or ''))
        if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}',key):raise ProviderConfigError('供应商名称需要包含可用于标识的字母或数字')
        matches=[r for r in _rows(raw) if r[0]==key]
        if len(matches)>1:raise ProviderConfigError('供应商标识重复，请在原生配置中先解决冲突')
        previous=matches[0] if matches else None
        if action=='delete_provider':
            if previous is None:raise ProviderConfigError('供应商不存在，请刷新列表')
            _,entry,source,slug=previous
            if main.get('provider') in {key,slug} or main.get('provider')=='custom' and main.get('base_url')==(entry.get('base_url') or entry.get('api')):
                raise ProviderConfigError('此供应商是全局默认，请先切换默认供应商后再删除')
            if source=='providers':raw['providers'].pop(key)
            else:raw['custom_providers']=[x for x in raw['custom_providers'] if x is not entry]
        elif action=='save_provider':
            url=str(body.get('base_url') or '').strip();u=urlsplit(url)
            if u.scheme not in ('http','https') or not u.hostname or u.username or u.password:raise ProviderConfigError('API Base URL 必须是有效的 HTTP(S) 地址，不能内嵌凭据')
            if not str(body.get('name') or '').strip() or not str(body.get('model') or '').strip():raise ProviderConfigError('名称和默认模型不能为空')
            mode=body.get('api_mode') or ''
            if mode not in ('','chat_completions','codex_responses','anthropic_messages'):raise ProviderConfigError('接口类型不支持')
            entry=copy.deepcopy(previous[1]) if previous else {}
            if previous and previous[2]=='custom_providers' and entry.get('name')!=body['name']:
                raise ProviderConfigError('此版本 Hermes 以名称作为标识，编辑时请保留原名称；如需改名可新增另一个供应商')
            entry.update(name=body['name'],base_url=url,model=body['model'],api_mode=mode)
            if body.get('api_key'):entry['api_key']=body['api_key']
            if previous and previous[2]=='providers':raw['providers'][key]=entry
            else:
                entries=raw.setdefault('custom_providers',[])
                if previous:entries[entries.index(previous[1])]=entry
                else:entries.append(entry)
        else:raise ProviderConfigError('不支持的供应商操作')
    save_config(raw,strip_defaults=False)
    return {**_listing(load_config()),'ok':True,**({'id':key} if action=='save_provider' else {})}
