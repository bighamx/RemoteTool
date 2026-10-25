const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../WebApplication/wwwroot/js/agent-workspace.js'), 'utf8');
let options;
const root = { dataset: { agent: 'hermes' } };
const sandbox = {
    URLSearchParams, location: { search: '' }, localStorage: { getItem() { return null; } },
    document: { currentScript: null, getElementById: id => id === 'agent-app' ? root : null },
    window: { RemoteToolApi: {} },
    Vue: { createApp: value => { options = value; return { use() {}, mount() {} }; } },
    ViewUIPlus: {}
};
sandbox.window.RemoteToolApi.id = value => encodeURIComponent(value);
vm.runInNewContext(source, sandbox, { filename: 'agent-workspace.js' });
assert.ok(options?.computed?.visibleLiveSegments, 'Hermes workspace computed must exist');
for (const name of ['projectRootsOf', 'asText', 'narrationNormalized', 'narrationCovers', 'sameNarration', 'narrationOverlap', 'pushLiveSegment']) {
    assert.equal(typeof options.methods[name], 'function', `${name} must be a method, not a computed getter`);
    assert.equal(options.computed[name], undefined);
}

const original = { id: 'first', text: 'Starting work', createdAt: 1000 };
const guarded = new Proxy(original, {
    set() { throw new Error('computed mutated a reactive live segment'); }
});
const context = {
    messages: [],
    liveSegments: [guarded, { id: 'second', text: 'Starting work with files', createdAt: 1001 }],
    asText: options.methods.asText,
    narrationNormalized: options.methods.narrationNormalized,
    narrationCovers: options.methods.narrationCovers,
    sameNarration: options.methods.sameNarration
};
const visible = options.computed.visibleLiveSegments.call(context);
assert.equal(visible.length, 1);
assert.equal(visible[0].text, 'Starting work with files');
assert.equal(original.text, 'Starting work');
assert.notEqual(visible[0], guarded);
assert.equal(options.methods.asText.call(context, null), '');
console.log('Hermes live narration: duplicate merge leaves reactive source untouched');

const view = fs.readFileSync(path.join(__dirname, '../../WebApplication/Views/Agents/Index.cshtml'), 'utf8');
assert.match(view, /v-if="sessionRunning\(item\)" class="agent-session-running"/);
const selected = { id: 'selected', status: { type: 'active' } };
const other = { id: 'other', status: 'idle' };
const list = {
    sessions: [selected, other], selectedId: 'selected', selectedStatusKnown: true,
    activeRun: null, externalRunning: false, loadingList: false,
    sessionStatusRefreshing: false, sessionStatusRefreshAt: 0
};
assert.equal(options.methods.sessionRunning.call(list, selected), false, 'selected session uses checked live state');
assert.equal(options.methods.sessionRunning.call({ ...list, selectedStatusKnown: false }, selected), true, 'Codex active status shows badge before detail loads');
assert.equal(options.methods.sessionRunning.call({ ...list, activeRun: 'run-1' }, selected), true, 'web run shows badge immediately');
sandbox.window.RemoteToolApi.get = async () => ({ data: [
    { id: 'selected', status: { type: 'active' } }, { id: 'other', status: 'running' }
] });
options.methods.refreshSessionStatuses.call(list).then(() => {
    assert.equal(list.sessions[0], selected, 'background refresh preserves session selection and order');
    assert.equal(list.sessions[1].status, 'running');
    assert.equal(options.methods.sessionRunning.call(list, other), true, 'Hermes running status shows badge');
    console.log('Session running badges: Codex, Hermes, selected live state and background refresh passed');
}).catch(error => { console.error(error); process.exitCode = 1; });

const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function checkReadOrdering() {
    const older = deferred(), newer = deferred(); let reads = 0;
    sandbox.window.RemoteToolApi.get = async () => (++reads === 1 ? older.promise : newer.promise);
    const status = {
        epoch: 1, selectedId: 'same', activeRun: null, externalRunning: false,
        startRead: options.methods.startRead, stopEventStream() {}, liveTools: []
    };
    const first = options.methods.loadStatus.call(status, 1, null, null, { available: true, running: false });
    const second = options.methods.loadStatus.call(status, 1, null, null, { available: true, running: true });
    newer.resolve({ run_id: null }); await second;
    assert.equal(status.externalRunning, true, `latest status failed before the older response: ${status.error || 'no error'}`);
    older.resolve({ run_id: null }); await first;
    assert.equal(status.externalRunning, true, 'older status response must not clear a newer running observation');

    const oldHistory = deferred(), newHistory = deferred(); reads = 0;
    sandbox.window.RemoteToolApi.get = async () => (++reads === 1 ? oldHistory.promise : newHistory.promise);
    const history = {
        epoch: 2, selectedId: 'same', messages: [], mediaSignature: '', filesRefreshAt: 0,
        startRead: options.methods.startRead, reconcileDelivered: () => new Set(),
        reconcilePending() {}, messageText: m => m.content, queueFollowLatest() {}, scheduleScrollRestore() {}
    };
    const oldRead = options.methods.loadHistory.call(history, 2);
    const newRead = options.methods.loadHistory.call(history, 2);
    newHistory.resolve({ data: [{ id: 2, role: 'user', content: 'new' }], has_more: false }); await newRead;
    oldHistory.resolve({ data: [{ id: 1, role: 'user', content: 'old' }], has_more: false }); await oldRead;
    assert.equal(history.messages[0].id, 2, 'older history response must not replace newer messages');
    console.log('Agent workspace concurrent reads: latest status and history win');
}
checkReadOrdering().catch(error => { console.error(error); process.exitCode = 1; });
