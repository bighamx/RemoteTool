import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {timingSafeEqual} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {pathToFileURL} from 'node:url';
import {StateStore, BridgeError, TERMINAL, validId} from './state.mjs';
import {RunManager} from './runs.mjs';
import {SessionService} from './sessions.mjs';

export const PROTOCOL = 2;      // API contract with the C# host; a change requires an idle restart.
export const BRIDGE_VERSION = 3; // Implementation revision; RemoteToolPaths.ClaudeBridgeVersion must match.

function args(argv) {
    const result = {};
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--allow-custom-endpoint') result.allowCustom = true;
        else if (argv[i].startsWith('--')) result[argv[i].slice(2)] = argv[++i];
    }
    return result;
}

export function createApp({root, executable, attachments, sdk, allowCustom = false, exec = promisify(execFile)}) {
    const store = new StateStore(path.join(root, 'journal'));
    const cache = (ttl, work) => { let value, at = 0, pending = null; return async (fresh = false) => {
        if (!fresh && value && Date.now() - at < ttl) return value;
        pending ||= work().then(result => { value = result; at = Date.now(); return result; }).finally(() => { pending = null; });
        return pending;
    }; };
    const auth = cache(30000, async () => {
        try {
            const {stdout} = await exec(executable, ['auth', 'status', '--json'], {timeout: 15000, maxBuffer: 65536, windowsHide: true});
            const status = JSON.parse(stdout);
            const ready = status.loggedIn === true && (allowCustom || status.apiProvider === 'firstParty');
            return {ready, method: status.authMethod || null, provider: status.apiProvider || null,
                message: ready ? 'Claude Code 已登录' : '请在运行 RemoteTool 的系统用户下执行 claude auth login 完成登录'};
        } catch { return {ready: false, message: '无法读取 Claude Code 登录状态，请在服务端用户下执行 claude auth status'}; }
    });
    const holders = cache(3000, async () => {
        const {stdout} = await exec(executable, ['agents', '--json'], {timeout: 15000, maxBuffer: 1048576, windowsHide: true});
        const rows = JSON.parse(stdout);
        if (!Array.isArray(rows)) throw new Error('Invalid agents response');
        return rows;
    });
    const sessions = new SessionService({store, sdk, auth: () => auth(), holders: fresh => holders(fresh), allowCustomEndpoint: allowCustom});
    sessions.attachmentRoot = attachments || null;
    // Exact ownership on Unix: our SDK children have this worker as parent.
    if (process.platform !== 'win32') sessions.isChild = async pid => {
        if (!Number.isSafeInteger(pid) || pid <= 0) return false;
        const {stdout} = await exec('ps', ['-o', 'ppid=', '-p', String(pid)], {timeout: 5000});
        return Number(String(stdout).trim()) === process.pid;
    };
    const runs = new RunManager({store, sdk, executable, prepare: (id, kind) => sessions.prepare(id, kind),
        onModels: list => { if (Array.isArray(list) && list.length) { store.data.models = {list, at: Date.now()}; try { store.save(); } catch {} } }});
    sessions.runs = runs;
    const sweeper = setInterval(() => runs.sweep(), 60000); sweeper.unref?.();
    return {store, sessions, runs, auth};
}

function send(res, code, data) {
    res.writeHead(code, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store'});
    res.end(JSON.stringify(data));
}

async function readBody(req) {
    if (req.method !== 'POST' && req.method !== 'PATCH') return {};
    let raw = '';
    for await (const chunk of req) { raw += chunk; if (Buffer.byteLength(raw) > 600000) throw new BridgeError('请求过大', 413, {delivery: 'rejected'}); }
    let body;
    try { body = JSON.parse(raw || '{}'); } catch { throw new BridgeError('无效 JSON', 400, {delivery: 'rejected'}); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new BridgeError('无效请求', 400, {delivery: 'rejected'});
    return body;
}

function stream(app, req, res, runId) {
    const run = app.store.run(runId);
    let last = Math.max(0, Number(req.headers['last-event-id']) || 0);
    res.writeHead(200, {'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no'});
    let closed = false;
    const write = () => {
        if (closed) return;
        let events = app.runs.eventsAfter(runId, last);
        // A finished run whose events were not retained still reports its final state once.
        if (!events.length && last === 0 && TERMINAL.has(run.status) && !app.runs.buffers.has(runId))
            events = [{event: 'run.' + run.status, type: 'run.' + run.status, seq: 1, run_id: runId, status: run.status, error: run.error}];
        for (const event of events) { res.write(`id: ${event.seq}\ndata: ${JSON.stringify(event)}\n\n`); last = Math.max(last, event.seq); }
        if (TERMINAL.has(run.status) && !app.runs.live.has(runId)) { app.runs.flush(run); const tail = app.runs.eventsAfter(runId, last); for (const event of tail) res.write(`id: ${event.seq}\ndata: ${JSON.stringify(event)}\n\n`); finish(); }
    };
    const keepalive = setInterval(() => { if (!closed) res.write(': keepalive\n\n'); }, 15000);
    const unsubscribe = app.runs.subscribe(runId, write);
    const finish = () => { if (closed) return; closed = true; clearInterval(keepalive); unsubscribe(); res.end(); };
    req.on('close', finish);
    write();
}

export function createServer(app, token) {
    const expected = Buffer.from('Bearer ' + token);
    return http.createServer(async (req, res) => {
        const supplied = Buffer.from(req.headers.authorization || '');
        if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return send(res, 401, {message: 'Unauthorized'});
        try {
            const url = new URL(req.url, 'http://localhost'), parts = url.pathname.split('/').filter(Boolean), method = req.method;
            const body = await readBody(req), key = req.headers['idempotency-key'], q = name => url.searchParams.get(name);
            const id = parts[1];
            if (id !== undefined && ['sessions', 'runs'].includes(parts[0]) && !(parts[0] === 'runs' && id === 'lookup') && !validId(id)) throw new BridgeError('无效标识', 400, {delivery: 'rejected'});
            let data;
            const route = `${method} /${parts[0] || ''}${parts.length > 1 ? '/:id' : ''}${parts.slice(2).map(p => '/' + p).join('')}`;
            switch (route) {
                case 'GET /health': data = {protocol: PROTOCOL, version: BRIDGE_VERSION, implementation: 'claude-agent-sdk', pid: process.pid, active_runs: app.runs.active().runs, waiting: app.runs.active().waiting}; break;
                case 'POST /shutdown':
                    if (app.runs.active().runs) throw new BridgeError('仍有任务执行中', 409);
                    send(res, 200, {stopping: true}); setTimeout(() => process.emit('remotetool-shutdown'), 50); return;
                case 'GET /capabilities': data = {protocol: PROTOCOL, version: BRIDGE_VERSION, features: {steer: true, approvals: true, ask_user_question: true, fork: true, rewind: true, rename: true,
                    pin: true, delete: true, model: true, permissions: true, compact: true, attachments: true, external_session_activity: true, acknowledge_unknown: true, usage: true}}; break;
                case 'GET /setup': data = {...await app.auth(q('fresh') === 'true'), protocol: PROTOCOL}; break;
                case 'GET /sessions': data = await app.sessions.list(q('cursor')); break;
                case 'POST /sessions': data = app.sessions.create(body); break;
                case 'GET /sessions/:id': data = {session: await app.sessions.detail(id)}; break;
                case 'PATCH /sessions/:id': data = await app.sessions.rename(id, body.title); break;
                case 'POST /sessions/:id/pin': data = await app.sessions.pin(id, body.pinned); break;
                case 'POST /sessions/:id/delete': data = await app.sessions.remove(id); break;
                case 'POST /sessions/:id/fork': data = await app.sessions.fork(id, body); break;
                case 'POST /sessions/:id/rewind': data = await app.sessions.rewind(id, body); break;
                case 'POST /sessions/:id/takeover': data = await app.sessions.takeover(id, body); break;
                case 'POST /sessions/:id/rewind/cancel': data = await app.sessions.cancelRewind(id); break;
                case 'POST /sessions/:id/model': data = await app.sessions.setModel(id, body); break;
                case 'GET /sessions/:id/permissions': data = await app.sessions.permissions(id); break;
                case 'POST /sessions/:id/permissions': data = await app.sessions.setPermissions(id, body.mode); break;
                case 'POST /sessions/:id/compact': data = await app.runs.start({session_id: id, input: '/compact'}, key, 'compact'); break;
                case 'GET /sessions/:id/messages': data = await app.sessions.messages(id, q('limit'), q('older_before')); break;
                case 'GET /sessions/:id/context': data = await app.sessions.context(id); break;
                case 'GET /sessions/:id/activity': data = await app.sessions.activity(id); break;
                case 'GET /sessions/:id/active-run': data = app.sessions.activeRun(id); break;
                case 'GET /model-options': data = app.sessions.modelOptions(q('session')); break;
                case 'GET /usage': data = app.sessions.usage(); break;
                case 'POST /runs': data = await app.runs.start(body, key); break;
                case 'GET /runs/:id': data = id === 'lookup' ? app.runs.lookup(q('key')) : app.runs.doc(app.store.run(id)); break;
                case 'GET /runs/:id/events': return stream(app, req, res, id);
                case 'POST /runs/:id/approval': data = app.runs.approve(id, body); break;
                case 'POST /runs/:id/steer': data = app.runs.steer(id, body, key); break;
                case 'POST /runs/:id/stop': data = await app.runs.stop(id); break;
                case 'POST /runs/:id/acknowledge': data = app.runs.acknowledge(id); break;
                default:
                    if (method === 'GET' && parts[0] === 'sessions' && parts[2] === 'tools' && parts.length === 4 && validId(parts[3]))
                        data = await app.sessions.tools(id, parts[3], q('offset'));
            }
            if (data === undefined) throw new BridgeError('接口不存在', 404);
            send(res, 200, data);
        } catch (e) {
            if (res.headersSent) { try { res.end(); } catch {} return; }
            const known = e instanceof BridgeError;
            send(res, known ? e.status : 503, known ? {message: e.message, code: e.code, delivery: e.delivery, run_id: e.run_id}
                : {message: 'Claude 本机接口处理失败；任务不会自动重发', delivery: req.method === 'GET' ? 'rejected' : 'unknown'});
        }
    });
}

async function main() {
    process.umask(0o077);
    const options = args(process.argv.slice(2));
    const root = options.state, executable = options.claude;
    if (!root || !path.isAbsolute(root) || !executable) throw new Error('Missing bridge configuration');
    const config = JSON.parse(fs.readFileSync(path.join(root, 'connection.json'), 'utf8'));
    if (typeof config.token !== 'string' || config.token.length < 40) throw new Error('Missing bridge credential');
    // Always use the official default Claude home, never a shell-specific alternate profile.
    // Markers inherited from a Claude terminal would turn our runs into child sessions (no transcript).
    for (const name of ['CLAUDE_CONFIG_DIR', 'CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT', 'CLAUDE_CODE_CHILD_SESSION', 'CLAUDE_CODE_SSE_PORT', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_PID', 'CLAUDE_CODE_MESSAGING_SOCKET', 'CLAUDE_CODE_MESSAGING_TOKEN', 'CLAUDE_CODE_SESSION_ATTENDED', 'CLAUDE_CODE_EXECPATH']) delete process.env[name];
    // The host may disappear (service restart); never let a closed stdio pipe kill running tasks.
    for (const stream of [process.stdout, process.stderr]) stream.on?.('error', () => {});
    process.on('SIGPIPE', () => {});
    const lock = path.join(root, 'worker.lock');
    if (fs.existsSync(lock)) {
        const pid = Number(fs.readFileSync(lock, 'utf8'));
        let alive = false; try { process.kill(pid, 0); alive = pid !== process.pid; } catch {}
        if (alive) process.exit(2);
        fs.unlinkSync(lock);
    }
    const fd = fs.openSync(lock, 'wx', 0o600); fs.writeFileSync(fd, String(process.pid)); fs.closeSync(fd);
    const release = () => { try { if (Number(fs.readFileSync(lock, 'utf8')) === process.pid) fs.unlinkSync(lock); } catch {} };
    const sdk = await import('@anthropic-ai/claude-agent-sdk');
    const app = createApp({root, executable, attachments: options.attachments, sdk, allowCustom: options.allowCustom === true});
    const server = createServer(app, config.token);
    server.requestTimeout = 0; server.headersTimeout = 30000; // SSE streams are long-lived
    process.on('remotetool-shutdown', () => { server.close(); release(); process.exit(0); });
    process.on('exit', release);
    server.listen(0, '127.0.0.1', () => {
        const status = path.join(root, 'status.json');
        fs.writeFileSync(status + '.tmp', JSON.stringify({port: server.address().port, pid: process.pid, protocol: PROTOCOL, version: BRIDGE_VERSION}), {mode: 0o600});
        fs.renameSync(status + '.tmp', status);
    });
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(() => process.exit(1));
