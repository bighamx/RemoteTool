const USAGE_METHOD = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET';
const SOURCE = '官方 Claude Code 订阅额度';

export function usageSnapshot(value, now = Date.now()) {
    const limits = value?.rate_limits_available === true ? value.rate_limits : null;
    const windows = {};
    for (const [name, key, minutes] of [['five_hour', 'primary', 300], ['seven_day', 'secondary', 10080]]) {
        const info = limits?.[name];
        if (!info) continue;
        const used = info.utilization;
        const reset = typeof info.resets_at === 'string' ? Date.parse(info.resets_at) : NaN;
        windows[key] = {windowDurationMins: minutes,
            // The structured API reports percentages (including 0 and 1), not fractions.
            usedPercent: typeof used === 'number' && Number.isFinite(used) && used >= 0 && used <= 100 ? used : null,
            resetsAt: Number.isFinite(reset) ? Math.floor(reset / 1000) : null, observedAt: now};
    }
    const available = Object.values(windows).some(window => window.usedPercent != null);
    return {available, rateLimitsByLimitId: Object.keys(windows).length ? {claude: {limitName: 'Claude', ...windows}} : {},
        source: SOURCE, observed_at: now, stale: false,
        message: available ? '' : value?.rate_limits_available === false ? '当前 Claude 登录方式不提供订阅额度' : 'Claude 暂未返回可读取的额度数据'};
}

/** A control-only, non-persistent SDK session. No prompt, tools, project or model request. */
export class ClaudeUsage {
    constructor({sdk, executable, cwd, auth, officialOnly = true, now = Date.now, ttl = 60000, retryDelay = 15000, timeout = 20000}) {
        Object.assign(this, {sdk, executable, cwd, auth, officialOnly, now, ttl, retryDelay, timeout});
        this.snapshot = null; this.refreshAt = 0; this.pending = null;
    }
    read() {
        if (this.pending) return this.pending;
        if (this.snapshot && this.now() < this.refreshAt) return Promise.resolve(this.snapshot);
        this.pending = this.fetch().then(value => {
            this.snapshot = value; this.refreshAt = this.now() + this.ttl; return value;
        }).catch(() => {
            const previous = this.snapshot;
            const value = previous?.available ? {...previous, stale: true, message: '额度刷新失败，当前显示上次读取结果'}
                : {available: false, rateLimitsByLimitId: {}, source: SOURCE, stale: false, message: 'Claude 额度暂时无法读取，请稍后重试'};
            this.snapshot = value; this.refreshAt = this.now() + this.retryDelay; return value;
        }).finally(() => { this.pending = null; });
        return this.pending;
    }
    async fetch() {
        const abort = new AbortController(); let query, timer;
        const deadline = new Promise((_, reject) => { timer = setTimeout(() => { abort.abort(); reject(new Error('usage_timeout')); }, this.timeout); });
        try {
            const auth = await Promise.race([this.auth(), deadline]);
            if (!auth.ready) return {available: false, rateLimitsByLimitId: {}, source: SOURCE, stale: false, message: auth.message || '请先登录官方 Claude Code'};
            // Keep stdin open without ever yielding a user message. Closing/aborting releases it.
            async function* input() { if (!abort.signal.aborted) await new Promise(resolve => abort.signal.addEventListener('abort', resolve, {once: true})); }
            query = this.sdk.query({prompt: input(), options: {
                pathToClaudeCodeExecutable: this.executable, cwd: this.cwd, abortController: abort,
                persistSession: false, settingSources: [], settings: {disableAllHooks: true},
                strictMcpConfig: true, mcpServers: {}, tools: [], plugins: [], skills: [], stderr: () => {},
                ...(this.officialOnly ? {env: {...process.env, ANTHROPIC_BASE_URL: 'https://api.anthropic.com'}} : {}),
            }});
            // Pinned SDK 0.3.322 exposes this experimental control API; fail safely on older versions.
            if (typeof query[USAGE_METHOD] !== 'function') throw new Error('usage_not_supported');
            const value = await Promise.race([query[USAGE_METHOD]({skipBehaviors: true}), deadline]);
            return usageSnapshot(value, this.now());
        } finally { clearTimeout(timer); try { query?.close(); } finally { abort.abort(); } }
    }
}
