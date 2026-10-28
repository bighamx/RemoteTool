const assert = require('node:assert/strict');
const { createHarness } = require('./agent-send.test.cjs');

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
}

(async () => {
    const h = createHarness([], 'hermes');
    const history = deferred(), context = deferred(), detail = deferred();
    const calls = [];
    h.state.loadHistory = () => { calls.push('history'); return history.promise; };
    h.state.loadStatus = async () => { calls.push('status'); };
    h.state.loadContext = () => { calls.push('context'); return context.promise; };
    h.state.loadSessionDetail = () => { calls.push('detail'); return detail.promise; };
    h.api.get = async route => { throw new Error(`Unexpected extra request: ${route}`); };
    let displayed = false;
    const loading = h.state.refreshAll().then(() => { displayed = true; });
    assert.deepEqual(calls, ['history', 'status', 'context', 'detail']);
    await Promise.resolve();
    assert.equal(displayed, false, 'history must finish before opening completes');
    history.resolve();
    await loading;
    assert.equal(displayed, true, 'slow metadata must not delay the message pane');
    context.resolve(); detail.resolve();
    await Promise.resolve();

    const failing = createHarness([], 'hermes');
    failing.state.loadHistory = async () => {};
    failing.state.loadContext = async () => { throw new Error('context unavailable'); };
    failing.state.loadSessionDetail = async () => { throw new Error('detail unavailable'); };
    await failing.state.refreshAll();
    await new Promise(resolve => setImmediate(resolve));
    console.log('Session loading: history priority and metadata failure isolation passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
