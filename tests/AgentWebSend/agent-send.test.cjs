const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../WebApplication/wwwroot/js/agent-workspace.js'), 'utf8');

function createHarness(responses, agent = 'codex') {
    const storage = new Map();
    const posts = [];
    let history = [];
    let options;
    let nextKey = 0;
    const api = {
        id: encodeURIComponent,
        async post(route) { posts.push(route); return responses.shift(); },
        async get(route) { if (route.includes('/messages?')) return { data: history }; throw new Error(`Unexpected GET: ${route}`); }
    };
    const context = {
        window: { RemoteToolApi: api },
        document: { currentScript: { dataset: { agentRootId: 'test-root' } }, getElementById: () => ({ querySelector: () => ({ dataset: { agent } }) }) },
        Vue: { createApp(value) { options = value; return { use() { return this; }, mount() {} }; } },
        ViewUIPlus: {},
        localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
        crypto: { randomUUID: () => `test-key-${++nextKey}` },
        setTimeout: () => 0,
        Date,
        URLSearchParams,
        console
    };
    vm.runInNewContext(source, context, { filename: 'agent-workspace.js' });
    const state = Object.assign(options.data(), options.methods);
    state.selectedId = 'session-1';
    state.queueFollowLatest = () => {};
    state.scheduleScrollRestore = () => {};
    state.loadStatus = async () => {};
    state.ensureEventStream = () => {};
    state.hasRenderableContent = () => true;
    Object.defineProperty(state, 'displayMessages', { get: () => options.computed.displayMessages.call(state) });
    return { state, posts, storage, setHistory: rows => { history = rows; } };
}

async function testAcceptedAndDeduped() {
    const h = createHarness([{ run_id: 'run-1', status: 'started' }, { accepted: true }]);
    h.state.draft = 'same text';
    await h.state.send();
    assert.equal(h.state.pending, null);
    assert.equal(h.state.displayMessages[0].delivery, '已送达');
    assert.equal(h.state.sending, false);
    h.state.messages = [{ id: 'reply', role: 'assistant', content: 'working', timestamp: Date.now() + 5000 }];
    assert.equal(h.state.displayMessages[0].role, 'user', 'local user message should stay before a later assistant reply');
    h.state.draft = 'same text';
    await h.state.send();
    assert.equal(h.posts.length, 2);
    assert.match(h.posts[1], /\/steer$/);
    assert.equal(h.state.delivered.length, 2);
    const now = Date.now();
    h.setHistory([{ id: 'm1', role: 'user', content: 'same text', timestamp: new Date(now).toISOString() }]);
    await h.state.loadHistory();
    assert.equal(h.state.delivered.filter(row => row.canonicalId).length, 1, 'one canonical message must not consume two local echoes');
    assert.equal(h.state.displayMessages.length, 2);
    await h.state.loadHistory();
    assert.equal(h.state.delivered.filter(row => row.canonicalId).length, 1, 'a repeated history refresh must not consume the second echo');
    h.setHistory([{ id: 'm1', role: 'user', content: 'same text', timestamp: new Date(now).toISOString() },
        { id: 'm2', role: 'user', content: 'same text', timestamp: new Date(now + 1000).toISOString() }]);
    await h.state.loadHistory();
    assert.equal(h.state.delivered.filter(row => row.canonicalId).length, 2);
    assert.equal(h.state.displayMessages.length, 2);
}

async function testUnknownStaysBlocked() {
    const h = createHarness([{ run_id: 'run-2', delivery: 'unknown' }]);
    h.state.draft = 'do not repeat';
    await h.state.send();
    assert.equal(h.state.pending.delivery, '发送状态待核对');
    assert.equal(h.state.delivered.length, 0);
    h.state.draft = 'second message';
    await h.state.send();
    assert.equal(h.posts.length, 1);
}

async function testRestoredEcho() {
    const h = createHarness([{ run_id: 'run-3', status: 'started' }], 'hermes');
    h.state.draft = 'keep on reload';
    await h.state.send();
    assert.equal(h.state.restoreDelivered('session-1').length, 1);
    assert.equal(h.storage.has('remotetool:web:pending:hermes:session-1'), false);
}

async function testSessionSwitchDuringSend() {
    let finish;
    const response = new Promise(resolve => { finish = resolve; });
    const h = createHarness([response]);
    h.state.draft = 'sent from first session';
    const sending = h.state.send();
    h.state.selectedId = 'session-2';
    h.state.pending = null;
    h.state.delivered = [];
    finish({ run_id: 'run-4', status: 'started' });
    await sending;
    assert.equal(h.state.pending, null);
    assert.equal(h.state.delivered.length, 0);
    assert.equal(h.state.restoreDelivered('session-1').length, 1);
    assert.equal(h.storage.has('remotetool:web:pending:codex:session-1'), false);
}

(async () => {
    await testAcceptedAndDeduped();
    await testUnknownStaysBlocked();
    await testRestoredEcho();
    await testSessionSwitchDuringSend();
    console.log('Agent web send: 4 checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
