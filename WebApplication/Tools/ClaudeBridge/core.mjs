import fs from 'node:fs';
import path from 'node:path';
import {randomUUID, createHash} from 'node:crypto';

export class BridgeError extends Error {
    constructor(message, status = 400) {super(message); this.status = status;}
}
const own = (table, key) => Object.hasOwn(table, key) ? table[key] : null;
const terminal = new Set(['completed', 'failed', 'cancelled', 'acceptance_unknown']);
const validId = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{1,160}$/.test(value);
export function officialEnvironment(env) {
    const base = env.ANTHROPIC_BASE_URL;
    if (base && String(base).replace(/\/+$/, '') !== 'https://api.anthropic.com') return false;
    return !['CLAUDE_CODE_USE_BEDROCK', 'CLAUDE_CODE_USE_VERTEX', 'CLAUDE_CODE_USE_FOUNDRY'].some(key => env[key] && !['0', 'false'].includes(String(env[key]).toLowerCase()));
}
export class ClaudeStore {
    constructor(root, sdk, executable, auth) {
        this.root = root; this.sdk = sdk; this.executable = executable; this.auth = auth;
        fs.mkdirSync(root, {recursive: true, mode: 0o700});
        this.file = path.join(root, 'state.json');
        this.state = fs.existsSync(this.file) ? JSON.parse(fs.readFileSync(this.file, 'utf8')) : {sessions: {}, runs: {}, receipts: {}};
        this.live = new Map(); this.waiters = new Map();
        for (const run of Object.values(this.state.runs)) if (!terminal.has(run.status)) {
            run.status = 'acceptance_unknown'; run.request = null;
            run.error = '桥接进程已结束，原任务结果待核对；未自动重发。';
        }
        this.save();
    }
    save() {
        const temp = this.file + '.tmp';
        fs.writeFileSync(temp, JSON.stringify(this.state), {mode: 0o600}); fs.renameSync(temp, this.file);
    }
    run(id) {const row = own(this.state.runs, id); if (!validId(id) || !row) throw new BridgeError('任务不存在', 404); return row;}
    session(id) {const row = own(this.state.sessions, id); if (!validId(id) || !row) throw new BridgeError('该会话仅供查看，请在 RemoteTool 新建会话', 409); return row;}
    event(run, type, data) {
        const entry = {seq: run.sequence + 1, type, data};
        fs.appendFileSync(path.join(this.root, run.id + '.jsonl'), JSON.stringify(entry) + '\n', {mode: 0o600});
        run.sequence = entry.seq; this.save();
    }
    events(id, after = 0) {
        const run = this.run(id); const file = path.join(this.root, run.id + '.jsonl');
        const data = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)).filter(row => row.seq > after).slice(0, 300) : [];
        return {data, cursor: data.at(-1)?.seq ?? after, status: run.status};
    }
    async sessions() {
        const native = await this.sdk.listSessions({limit: 300});
        const rows = new Map(native.map(row => [row.sessionId, {id: row.sessionId, title: row.customTitle || row.summary || 'Claude Code', cwd: row.cwd, last_active: row.lastModified, managed: false}]));
        for (const row of Object.values(this.state.sessions)) rows.set(row.id, {...rows.get(row.id), ...row, managed: true});
        return {data: [...rows.values()].sort((a, b) => b.last_active - a.last_active)};
    }
    async messages(id) {
        if (!validId(id)) throw new BridgeError('无效会话标识');
        const managed = own(this.state.sessions, id);
        const nativeId = managed?.native_id || (!managed ? id : null);
        const native = nativeId ? await this.sdk.getSessionMessages(nativeId) : [];
        const data = native.filter(m => !m.parent_tool_use_id).map(m => ({id: m.uuid, role: m.type, blocks: m.message?.content || [], content: typeof m.message?.content === 'string' ? m.message.content : ''}));
        return {data};
    }
    create(body) {
        if (typeof body.cwd !== 'string' || !path.isAbsolute(body.cwd) || !fs.statSync(body.cwd, {throwIfNoEntry: false})?.isDirectory()) throw new BridgeError('请选择本机已有的绝对项目路径');
        if (body.title != null && (typeof body.title !== 'string' || body.title.length > 200)) throw new BridgeError('标题过长');
        if (!['default', 'acceptEdits', 'plan'].includes(body.permission_mode || 'default')) throw new BridgeError('不支持的权限模式');
        const id = randomUUID();
        const row = {id, title: body.title?.trim() || 'Claude Code', cwd: fs.realpathSync(body.cwd), permission_mode: body.permission_mode || 'default', managed: true, native_id: null, last_active: Date.now()};
        this.state.sessions[id] = row; this.save(); return row;
    }
    async start(body, key) {
        if (typeof key !== 'string' || !/^[a-zA-Z0-9_-]{16,120}$/.test(key)) throw new BridgeError('需要唯一发送标识');
        if (typeof body.input !== 'string' || !body.input.trim() || body.input.length > 100000) throw new BridgeError('消息不能为空或超过限制');
        const digest = createHash('sha256').update(JSON.stringify([body.session_id, body.input])).digest('hex');
        const receipt = own(this.state.receipts, key);
        if (receipt) {if (receipt.digest !== digest) throw new BridgeError('发送标识已被另一条消息使用', 409); return this.run(receipt.run);}
        const session = this.session(body.session_id);
        if (Object.values(this.state.runs).some(row => row.session_id === session.id && !terminal.has(row.status))) throw new BridgeError('此会话已有任务执行中', 409);
        if (Object.values(this.state.runs).some(row => row.session_id === session.id && row.status === 'acceptance_unknown')) throw new BridgeError('原任务结果待核对，请查看历史；可新建会话继续', 409);
        // Auth check happens before acceptance. No prompt is ever sent by a health probe.
        const status = await this.auth(session);
        if (!status.ready) throw new BridgeError(status.message, 409);
        // Another request may have completed the async preflight.
        if (own(this.state.receipts, key)) return this.start(body, key);
        if (Object.values(this.state.runs).some(row => row.session_id === session.id && !terminal.has(row.status))) throw new BridgeError('此会话已有任务执行中', 409);
        const run = {id: randomUUID(), session_id: session.id, status: 'started', sequence: 0, input: body.input, created_at: Date.now(), request: null, usage: null};
        this.state.runs[run.id] = run; this.state.receipts[key] = {run: run.id, digest}; session.last_active = Date.now();
        try {this.save(); this.event(run, 'user', {text: body.input});}
        catch {run.status = 'acceptance_unknown'; run.error = '发送回执保存失败，未自动重发。'; try {this.save();} catch {} throw new Error('Receipt storage failed');}
        // Detached from HTTP lifetime: losing the page never cancels the task.
        void this.execute(run, session).catch(() => {
            run.status = 'acceptance_unknown'; run.error = '任务状态保存失败，请核对历史；未自动重发。';
            try {this.save();} catch {}
        });
        return run;
    }
    async execute(run, session) {
        const abort = new AbortController();
        const runtime = {abort, query: null}; this.live.set(run.id, runtime);
        try {
            const options = {
                cwd: session.cwd, pathToClaudeCodeExecutable: this.executable,
                settingSources: ['user', 'project', 'local'], systemPrompt: {type: 'preset', preset: 'claude_code'},
                permissionMode: session.permission_mode, includePartialMessages: true, abortController: abort,
                ...(session.native_id ? {resume: session.native_id} : {sessionId: session.id}),
                canUseTool: (tool, input, context) => {
                    const pending = (runtime.permissions || Promise.resolve()).then(() => abort.signal.aborted
                        ? {behavior: 'deny', message: '任务已停止'} : this.ask(run, tool, input, context.signal));
                    runtime.permissions = pending.catch(() => {}); return pending;
                },
            };
            runtime.query = this.sdk.query({prompt: run.input, options});
            let result = false;
            for await (const message of runtime.query) {
                if (message.session_id && !message.parent_tool_use_id) {
                    if ((session.native_id || session.id) !== message.session_id) throw new Error('会话身份不匹配');
                    session.native_id = message.session_id;
                }
                if (message.type === 'stream_event' && !message.parent_tool_use_id) this.event(run, 'stream', message.event);
                if (message.type === 'assistant' && !message.parent_tool_use_id) this.event(run, 'assistant', {id: message.uuid, blocks: message.message.content});
                if (message.type === 'user' && !message.parent_tool_use_id) this.event(run, 'tool_result', {blocks: message.message.content});
                if (message.type === 'result') {
                    result = true; run.usage = {tokens: message.usage, cost_usd: message.total_cost_usd};
                    run.status = message.is_error ? 'failed' : 'completed';
                    if (message.is_error) run.error = 'Claude 执行未完成，请查看历史与本机登录状态';
                    this.event(run, 'result', {status: run.status, usage: run.usage, error: run.error});
                }
                this.save();
            }
            if (!result) throw new Error('未收到结束回执');
        } catch {
            // Exception messages can contain headers or credentials from user plugins.
            run.status = abort.signal.aborted ? 'cancelled' : 'acceptance_unknown';
            run.error = abort.signal.aborted ? '已请求停止，请核对工具实际结果' : '连接结束，任务结果待核对；请查看会话历史，未自动重发。';
            this.event(run, 'result', {status: run.status, error: run.error});
        } finally {
            this.cancelQuestion(run); run.request = null; this.live.delete(run.id);
            try {runtime.query?.close();} catch {}
            this.save();
        }
    }
    ask(run, tool, input, signal) {
        if (run.request) return Promise.resolve({behavior: 'deny', message: '已有待处理请求'});
        const request = {id: randomUUID(), tool, input}; run.request = request; run.status = 'waiting'; this.event(run, 'request', request);
        return new Promise(resolve => {
            const finish = answer => {signal?.removeEventListener('abort', cancel); this.waiters.delete(request.id); resolve(answer);};
            const cancel = () => {if (this.waiters.has(request.id)) {run.request = null; finish({behavior: 'deny', message: '已取消'});}};
            this.waiters.set(request.id, finish); signal?.addEventListener('abort', cancel, {once: true});
            if (signal?.aborted) cancel();
        });
    }
    reply(id, body) {
        const run = this.run(id), request = run.request;
        if (!request || body.request_id !== request.id || !this.waiters.has(request.id)) throw new BridgeError('请求已过期，请刷新', 409);
        if (typeof body.allow !== 'boolean') throw new BridgeError('需要明确允许或拒绝');
        const decision = body.allow === true;
        let updatedInput = request.input;
        if (decision && request.tool === 'AskUserQuestion') {
            const questions = request.input.questions;
            if (!Array.isArray(questions) || !body.answers || typeof body.answers !== 'object' || questions.some(q => typeof body.answers[q.question] !== 'string' || !body.answers[q.question].trim() || body.answers[q.question].length > 5000)) throw new BridgeError('请回答所有问题');
            updatedInput = {...request.input, answers: Object.fromEntries(questions.map(q => [q.question, body.answers[q.question]]))};
        }
        const finish = this.waiters.get(request.id); run.request = null; run.status = 'started'; this.save();
        finish(decision ? {behavior: 'allow', updatedInput} : {behavior: 'deny', message: '用户拒绝了本次请求'});
        this.event(run, 'request_resolved', {id: request.id, allowed: decision}); return {accepted: true};
    }
    cancelQuestion(run) {if (run.request) this.waiters.get(run.request.id)?.({behavior: 'deny', message: '任务已结束'});}
    stop(id) {
        const run = this.run(id); if (!this.live.has(id)) return run;
        this.live.get(id).abort.abort(); this.cancelQuestion(run); return {...run, stop_requested: true};
    }
}
