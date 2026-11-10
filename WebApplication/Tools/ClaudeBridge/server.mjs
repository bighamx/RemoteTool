import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {timingSafeEqual} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {ClaudeStore, BridgeError, officialEnvironment} from './core.mjs';

process.umask(0o077);
const root = process.env.REMOTETOOL_CLAUDE_STATE, executable = process.env.REMOTETOOL_CLAUDE_EXECUTABLE;
if (!root || !path.isAbsolute(root) || !executable) throw new Error('Missing bridge configuration');
const config = JSON.parse(fs.readFileSync(path.join(root, 'connection.json'), 'utf8'));
if (typeof config.token !== 'string' || config.token.length < 40) throw new Error('Missing bridge credential');
// Always use the official default Claude home, never a shell-specific alternate profile.
delete process.env.CLAUDE_CONFIG_DIR;
delete process.env.CLAUDECODE;
const sdk = await import('@anthropic-ai/claude-agent-sdk');
let cached, authAt = 0;
async function auth(fresh = false) {
    if (!fresh && cached && Date.now() - authAt < 10000) return cached;
    try {
        const {stdout} = await promisify(execFile)(executable, ['auth', 'status', '--json'], {timeout: 15000, maxBuffer: 65536, windowsHide: true});
        const status = JSON.parse(stdout);
        const ready = status.loggedIn === true && status.apiProvider === 'firstParty';
        cached = {ready, method: status.authMethod, message: ready ? '官方 Claude Code 已登录' : '请在运行服务的本机用户下配置官方 Claude Code 登录（claude auth login）'};
    } catch {cached = {ready: false, message: '无法读取官方 Claude Code 登录状态；请在运行服务的用户下执行 claude auth status'};}
    authAt = Date.now(); return cached;
}
const lock = path.join(root, 'worker.lock');
if (fs.existsSync(lock)) {
    const pid = Number(fs.readFileSync(lock, 'utf8'));
    let alive = false; try {process.kill(pid, 0); alive = true;} catch {}
    if (alive) process.exit(2);
    fs.unlinkSync(lock);
}
const lockFd = fs.openSync(lock, 'wx', 0o600); fs.writeFileSync(lockFd, String(process.pid)); fs.closeSync(lockFd);
const store = new ClaudeStore(path.join(root, 'journal'), sdk, executable, async session => {
    const status = await auth(true); if (!status.ready) return status;
    const resolved = await sdk.resolveSettings({cwd: session.cwd, settingSources: ['user', 'project', 'local']});
    if (!officialEnvironment(process.env) || !officialEnvironment(resolved.effective.env || {}))
        return {ready: false, message: '当前项目配置了非官方模型端点，本入口仅支持官方 Claude Code'};
    try {
        const {stdout} = await promisify(execFile)(executable, ['agents', '--json'], {timeout: 15000, maxBuffer: 1048576, windowsHide: true});
        const sessions = JSON.parse(stdout);
        if (!Array.isArray(sessions)) throw new Error('Invalid active-session response');
        if (sessions.some(row => row.sessionId === (session.native_id || session.id))) return {ready: false, message: '电脑进程仍持有此会话，请先在电脑结束该会话进程，再远程继续'};
    } catch {return {ready: false, message: '无法确认电脑会话是否空闲，请更新官方 Claude Code 后重试'};}
    return status;
});
const response = (res, code, data) => {res.writeHead(code, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store'}); res.end(JSON.stringify(data));};
const server = http.createServer(async (req, res) => {
    const supplied = Buffer.from(req.headers.authorization || ''), expected = Buffer.from('Bearer ' + config.token);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return response(res, 401, {message: 'Unauthorized'});
    try {
        const url = new URL(req.url, 'http://localhost'), parts = url.pathname.split('/').filter(Boolean);
        let body = {};
        if (req.method === 'POST') {
            let raw = ''; for await (const chunk of req) {raw += chunk; if (Buffer.byteLength(raw) > 600000) throw new BridgeError('请求过大', 413);}
            try {body = JSON.parse(raw || '{}');} catch {throw new BridgeError('无效 JSON');}
            if (!body || typeof body !== 'object' || Array.isArray(body)) throw new BridgeError('无效请求');
        }
        let data;
        if (req.method === 'GET' && url.pathname === '/health') data = {protocol: 1, pid: process.pid, active_runs: store.live.size};
        else if (req.method === 'GET' && url.pathname === '/setup') data = {...await auth(), protocol: 1};
        else if (req.method === 'GET' && url.pathname === '/sessions') data = await store.sessions();
        else if (req.method === 'POST' && url.pathname === '/sessions') data = store.create(body);
        else if (req.method === 'GET' && parts.length === 3 && parts[0] === 'sessions' && parts[2] === 'messages') data = await store.messages(parts[1]);
        else if (req.method === 'GET' && parts.length === 2 && parts[0] === 'receipts') {const receipt=Object.hasOwn(store.state.receipts,parts[1])?store.state.receipts[parts[1]]:null; data=receipt?store.run(receipt.run):{pending:true};}
        else if (req.method === 'POST' && url.pathname === '/runs') data = await store.start(body, req.headers['idempotency-key']);
        else if (parts[0] === 'runs' && parts.length >= 2) {
            if (parts.length === 2 && req.method === 'GET') data = store.run(parts[1]);
            else if (parts.length === 3 && parts[2] === 'events' && req.method === 'GET') {
                const after = Number(url.searchParams.get('after') || 0);
                if (!Number.isSafeInteger(after) || after < 0) throw new BridgeError('无效事件游标');
                data = store.events(parts[1], after);
            } else if (parts.length === 3 && parts[2] === 'reply' && req.method === 'POST') data = store.reply(parts[1], body);
            else if (parts.length === 3 && parts[2] === 'stop' && req.method === 'POST') data = store.stop(parts[1]);
        } else if (req.method === 'GET' && parts.length === 3 && parts[0] === 'sessions' && parts[2] === 'active-run') {
            data = Object.values(store.state.runs).filter(r => r.session_id === parts[1]).sort((a,b) => b.created_at - a.created_at)[0] || {};
        }
        if (data === undefined) throw new BridgeError('接口不存在', 404);
        response(res, 200, data);
    } catch (e) {
        response(res, e instanceof BridgeError ? e.status : 503, {message: e instanceof BridgeError ? e.message : 'Claude 本机接口暂不可用，请检查安装和项目路径', delivery: e instanceof BridgeError ? 'rejected' : 'unknown'});
    }
});
server.requestTimeout = 20000;
server.listen(0, '127.0.0.1', () => {
    const status = path.join(root, 'status.json');
    fs.writeFileSync(status + '.tmp', JSON.stringify({port: server.address().port, pid: process.pid, protocol: 1}), {mode: 0o600});
    fs.renameSync(status + '.tmp', status);
});
