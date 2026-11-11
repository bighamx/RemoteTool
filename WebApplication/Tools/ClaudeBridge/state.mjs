import fs from 'node:fs';
import path from 'node:path';

export class BridgeError extends Error {
    constructor(message, status = 400, extra = {}) { super(message); this.status = status; Object.assign(this, extra); }
}

export const TERMINAL = new Set(['completed', 'failed', 'cancelled', 'acceptance_unknown']);
export const validId = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{1,160}$/.test(value);
export const validKey = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{16,120}$/.test(value);
const own = (table, key) => Object.hasOwn(table, key) ? table[key] : null;
const KEEP_RUNS = 300, RECEIPT_DAYS = 14;

/**
 * Durable bridge state. Written only on state transitions (never per stream delta).
 * Shape v2: {version, sessions:{id:meta}, runs:{id:run}, receipts:{key:{run,digest,kind,at}}, models, rate_limits}
 */
export class StateStore {
    constructor(root) {
        this.root = root;
        fs.mkdirSync(root, {recursive: true, mode: 0o700});
        this.file = path.join(root, 'state-v2.json');
        this.data = this.load();
        // Runs that were live when the previous bridge exited cannot be proven finished.
        for (const run of Object.values(this.data.runs)) if (!TERMINAL.has(run.status)) {
            run.status = 'acceptance_unknown'; run.approval = null; run.reviewed = false;
            run.error = '桥接进程已结束，上一轮结果待核对；未自动重发。';
            run.finished_at = Date.now();
        }
        this.prune(); this.save();
    }
    load() {
        if (!fs.existsSync(this.file)) return {version: 2, sessions: {}, runs: {}, receipts: {}, models: null, rate_limits: {}};
        // A corrupt file must fail closed: receipts protect against duplicate sends.
        const value = JSON.parse(fs.readFileSync(this.file, 'utf8'));
        if (value?.version !== 2 || typeof value.runs !== 'object' || typeof value.receipts !== 'object') throw new Error('Unsupported bridge state');
        value.sessions ||= {}; value.rate_limits ||= {};
        return value;
    }
    save() {
        const temp = this.file + '.' + process.pid + '.tmp';
        fs.writeFileSync(temp, JSON.stringify(this.data), {mode: 0o600});
        fs.renameSync(temp, this.file);
    }
    prune(now = Date.now()) {
        const finished = Object.values(this.data.runs).filter(run => TERMINAL.has(run.status) && (run.status !== 'acceptance_unknown' || run.reviewed))
            .sort((a, b) => (b.finished_at || b.created_at) - (a.finished_at || a.created_at));
        const dropped = new Set(finished.slice(KEEP_RUNS).map(run => run.id));
        for (const id of dropped) delete this.data.runs[id];
        for (const [key, receipt] of Object.entries(this.data.receipts)) {
            const run = own(this.data.runs, receipt.run);
            const stale = now - (receipt.at || 0) > RECEIPT_DAYS * 86400000;
            if (!run && (dropped.has(receipt.run) || stale)) delete this.data.receipts[key];
            else if (stale && run && TERMINAL.has(run.status) && (run.status !== 'acceptance_unknown' || run.reviewed)) delete this.data.receipts[key];
        }
    }
    run(id) { const row = validId(id) ? own(this.data.runs, id) : null; if (!row) throw new BridgeError('任务不存在', 404, {code: 'run_not_found'}); return row; }
    receipt(key) { return validKey(key) ? own(this.data.receipts, key) : null; }
    meta(id) { return validId(id) ? own(this.data.sessions, id) : null; }
    ensureMeta(id, fields = {}) {
        if (!validId(id)) throw new BridgeError('无效会话标识');
        const row = own(this.data.sessions, id) || (this.data.sessions[id] = {id, created_at: Date.now()});
        Object.assign(row, fields); return row;
    }
    runsFor(session) { return Object.values(this.data.runs).filter(run => run.session_id === session).sort((a, b) => b.created_at - a.created_at); }
    liveRun(session) { return this.runsFor(session).find(run => !TERMINAL.has(run.status)) || null; }
    unresolvedRun(session) { return this.runsFor(session).find(run => run.status === 'acceptance_unknown' && !run.reviewed) || null; }
}
