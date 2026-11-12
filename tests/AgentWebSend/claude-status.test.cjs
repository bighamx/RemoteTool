const test = require('node:test');
const assert = require('node:assert/strict');
const {createHarness} = require('./agent-send.test.cjs');

test('a Claude unknown terminal status refreshes the acknowledgement banner', async () => {
    const h = createHarness([], 'claude'), state = h.state;
    state.activeRun = 'unknown-run';
    state.selected = {id: state.selectedId};
    state.loadHistory = async () => {};
    state.finishReasoning = () => {};
    let details = 0;
    state.loadSessionDetail = async () => {
        details++;
        state.selected = {...state.selected, unresolved_run: {run_id: 'unknown-run'}};
    };
    await h.options.methods.loadStatus.call(state, state.epoch, {run_id: null},
        {status: 'acceptance_unknown'}, {available: true, running: false});
    assert.equal(details, 1);
    assert.equal(state.selected.unresolved_run.run_id, 'unknown-run');
    assert.equal(state.activeRun, null);
});


test('an accepted Claude receipt with an unknown result reaches review without losing admission', async () => {
    const h = createHarness([{run_id: 'unknown-run', delivery: 'accepted', status: 'acceptance_unknown'}], 'claude');
    const state = h.state;
    state.selected = {id: state.selectedId};
    state.draft = 'one operation';
    state.loadHistory = async () => {};
    state.finishReasoning = () => {};
    state.loadSessionDetail = async () => { state.selected = {...state.selected, unresolved_run: {run_id: 'unknown-run'}}; };
    h.api.get = async route => route.endsWith('/active-run') ? {run_id: null}
        : route.includes('/runs/') ? {status: 'acceptance_unknown'} : {available: true, running: false};
    state.loadStatus = h.options.methods.loadStatus.bind(state);
    await state.send(); await new Promise(setImmediate);
    assert.equal(state.pending, null, 'a durable accepted receipt confirms delivery independently of execution');
    assert.equal(state.selected.unresolved_run.run_id, 'unknown-run');
    assert.equal(state.sendBlocked, true);
    state.draft = 'another operation'; await state.send();
    assert.equal(h.posts.length, 1, 'the review state blocks another submission');
    assert.equal(state.draft, 'another operation');
});


test('acknowledgement requires confirmation and refreshes the selected session', async () => {
    const h = createHarness([{}], 'claude'), state = h.state;
    state.selected = {id: state.selectedId, unresolved_run: {run_id: 'unknown-run'}};
    state.loadHistory = async () => {};
    state.loadSessionDetail = async () => { state.selected = {...state.selected, unresolved_run: null}; };
    h.context.confirm = () => false;
    await state.acknowledgeUnresolved();
    assert.equal(h.posts.length, 0);
    assert.equal(state.sendBlocked, true);
    h.context.confirm = () => true;
    await state.acknowledgeUnresolved();
    assert.equal(h.posts.length, 1);
    assert.match(h.posts[0], /\/runs\/unknown-run\/acknowledge$/);
    assert.equal(state.sendBlocked, false);
    assert.equal(state.acknowledging, false);
});

test('a late accepted lookup reaches the same review state without replaying the message', async () => {
    const h = createHarness([], 'claude'), state = h.state;
    state.selected = {id: state.selectedId};
    state.pending = {key: 'pending-key-1234567890', input: 'one operation', attachments: [], createdAt: Date.now()};
    state.loadHistory = async () => {};
    state.loadSessionDetail = async () => { state.selected = {...state.selected, unresolved_run: {run_id: 'unknown-run'}}; };
    h.api.get = async route => route.includes('/lookup?') ? {found: true, run_id: 'unknown-run', delivery: 'accepted', status: 'acceptance_unknown'}
        : route.endsWith('/active-run') ? {run_id: null} : {available: true, running: false};
    state.loadStatus = h.options.methods.loadStatus.bind(state);
    await state.verifyPending(); await new Promise(setImmediate);
    assert.equal(state.pending, null);
    assert.equal(state.sendBlocked, true);
    assert.equal(h.posts.length, 0);
});
