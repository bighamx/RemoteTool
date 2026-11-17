const aliases = [
    ['opus', 'Opus', 'ANTHROPIC_DEFAULT_OPUS_MODEL'],
    ['sonnet', 'Sonnet', 'ANTHROPIC_DEFAULT_SONNET_MODEL'],
    ['haiku', 'Haiku', 'ANTHROPIC_DEFAULT_HAIKU_MODEL'],
    ['fable', 'Fable', 'ANTHROPIC_DEFAULT_FABLE_MODEL'],
];
const modelId = value => typeof value === 'string' && value.length <= 200 && !/[\x00-\x1f\x7f]/.test(value) ? value.trim() : '';

/** Reflect native Claude settings without copying credentials into the model catalog. */
export function nativeModelCatalog({settings = {}, env = {}, current = '', cached = [], official = true}) {
    const configured = modelId(env.ANTHROPIC_MODEL) || modelId(settings.model);
    const mappings = aliases.map(([alias, label, key]) => ({alias, label, model: modelId(env[key])}));
    const mapped = mappings.some(row => row.model);
    const rows = new Map();
    const add = (id, label, source = {}) => {
        if (!id || rows.has(id)) return;
        rows.set(id, {model: id, id, displayName: label || id, description: source.description || '',
            supportedReasoningEfforts: (source.supportedEffortLevels || []).map(level => ({reasoningEffort: level}))});
    };
    if (official && !mapped) {
        for (const row of cached) add(modelId(row.value), row.displayName, row);
    } else {
        add('default', configured ? `默认（${configured}）` : '默认（本机 Claude 配置）');
        for (const row of mappings) {
            if (row.model) add(row.model, `${row.model}（${row.label}）`);
            else if (official) add(row.alias, row.label);
        }
    }
    add(configured, configured);
    add(modelId(env.ANTHROPIC_CUSTOM_MODEL_OPTION), modelId(env.ANTHROPIC_CUSTOM_MODEL_OPTION_NAME) || modelId(env.ANTHROPIC_CUSTOM_MODEL_OPTION));
    // A selected alias is still passed verbatim to Claude, which owns its routing semantics.
    const selected = mappings.find(row => row.alias === current && row.model);
    add(modelId(current), selected ? `${selected.model}（${selected.label}）` : current);
    return {models: [...rows.values()], model: current || configured, catalog_cached: official && !mapped && cached.length > 0};
}
import {createHash} from 'node:crypto';


/** Credential-scoped discovery. Failures retain configured models and never expose response bodies. */
export class ClaudeModelDiscovery {
    constructor(fetcher = fetch) { this.fetcher = fetcher; this.cache = new Map(); this.pending = new Map(); }
    async read(base, env, refresh = false) {
        if (!base) return {models: []};
        let url;
        try {
            url = new URL(base.replace(/\/+$/, '') + (/\/v1\/?$/.test(base) ? '/models' : '/v1/models'));
            if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) return {models: []};
        } catch { return {models: []}; }
        const headers = {'anthropic-version': '2023-06-01'};
        const key = env.ANTHROPIC_API_KEY || env.ANTHROPIC_AUTH_TOKEN;
        if (key) { headers.Authorization = 'Bearer ' + (env.ANTHROPIC_AUTH_TOKEN || key); headers['x-api-key'] = key; }
        for (const line of String(env.ANTHROPIC_CUSTOM_HEADERS || '').split('\n')) {
            const at = line.indexOf(':');
            if (at > 0 && /^[!#$%&'*+.^_`|~\w-]+$/.test(line.slice(0, at))) headers[line.slice(0, at)] = line.slice(at + 1).trim();
        }
        const cacheKey = createHash('sha256').update(JSON.stringify([url.href, headers])).digest('hex');
        const cached = this.cache.get(cacheKey);
        if (!refresh && cached && cached.until > Date.now()) return cached.value;
        if (this.pending.has(cacheKey)) return this.pending.get(cacheKey);
        const work = (async () => {
            let value;
            try {
                const seen = new Set(), cursors = new Set(), models = [], page = new URL(url), signal = AbortSignal.timeout(4000);
                for (let index = 0; index < 10 && models.length < 2000; index++) {
                    const response = await this.fetcher(page, {headers, redirect: 'error', signal});
                    if (!response.ok) throw new Error('model discovery failed');
                    const doc = await response.json();
                    if (!Array.isArray(doc.data)) throw new Error('invalid model catalog');
                    for (const row of doc.data.slice(0, 2000 - models.length)) {
                        const id = modelId(row?.id || row?.model);
                        if (!id || seen.has(id)) continue;
                        seen.add(id); models.push({model: id, id, displayName: modelId(row.display_name || row.displayName || row.name) || id});
                    }
                    const cursor = modelId(doc.last_id);
                    if (!doc.has_more || !cursor || cursors.has(cursor)) break;
                    cursors.add(cursor); page.searchParams.set('after_id', cursor);
                }
                value = {models};
                this.cache.set(cacheKey, {value, until: Date.now() + 180000});
            } catch {
                value = {models: cached?.value.models || [], warning: '模型目录暂不可用，显示已配置或上次读取的模型，可点击刷新重试'};
                this.cache.set(cacheKey, {value, until: Date.now() + 15000});
            }
            if (this.cache.size > 100) this.cache.delete(this.cache.keys().next().value);
            return value;
        })().finally(() => this.pending.delete(cacheKey));
        this.pending.set(cacheKey, work);
        return work;
    }
}

export function mergeModelCatalog(configured, discovered) {
    const models = new Map(configured.map(row => [row.model, row]));
    for (const row of discovered) if (!models.has(row.model)) models.set(row.model, row);
    return [...models.values()];
}
