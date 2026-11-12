const test = require('node:test');
const assert = require('node:assert/strict');
const {createHarness} = require('./AgentWebSend/agent-send.test.cjs');

function menuHarness(agent, mobile = true) {
    const h = createHarness([], agent);
    const {state, context} = h;
    const history = {state: {remoteToolSession: 'session-1'}, backs: 0,
        pushState(value) { this.previous = this.state; this.state = value; },
        back() { this.backs++; },
        finishBack() { this.state = this.previous; state.onContextMenuPopState(); }
    };
    context.document.querySelectorAll = () => [];
    context.history = history;
    context.location.href = `http://localhost/#/${agent}`;
    context.innerWidth = 390;
    context.innerHeight = 640;
    context.window.matchMedia = () => ({matches: mobile});
    state.$nextTick = callback => callback?.();
    state.$refs = {contextMenu: {offsetWidth: 240, offsetHeight: 440}, contextMenuClose: {focus() {}}};
    const event = {clientX: 380, clientY: 630, currentTarget: {focus() {}}};
    return {...h, history, event};
}

for (const agent of ['codex', 'hermes']) {
    test(`${agent} mobile menu closes on Back without changing the session`, () => {
        const {state, history, event} = menuHarness(agent);
        state.openSessionMenu(event, {id: 'target'});
        assert.equal(history.state.remoteToolSession, 'session-1');
        assert.ok(history.state.remoteToolAgentMenu);
        history.finishBack();
        assert.equal(state.sessionMenu, null);
        assert.equal(state.selectedId, 'session-1');
        state.openMessageMenu(event, {id: 'message'});
        assert.ok(state.messageMenu);
        history.finishBack();
        assert.equal(state.messageMenu, null);
    });

    test(`${agent} menu action waits for Back before opening another panel`, async () => {
        const {state, history, event} = menuHarness(agent);
        const session = {id: 'target'};
        state.openSessionMenu(event, session);
        let opened = null;
        state.showSessionDetails = value => { opened = value; };
        const action = state.runContextMenuAction('showSessionDetails', session);
        assert.equal(state.sessionMenu, null);
        assert.equal(opened, null);
        state.closeContextMenu();
        assert.equal(history.backs, 1, 'repeated dismissal must not go back twice');
        history.finishBack();
        await action;
        assert.equal(opened, session);
        assert.equal(history.state.remoteToolSession, 'session-1');
    });
}

test('desktop menu remains inside the viewport without adding history', () => {
    const {state, history, event} = menuHarness('codex', false);
    state.openSessionMenu(event, {id: 'target'});
    assert.equal(state.sessionMenu.x, 142);
    assert.equal(state.sessionMenu.y, 192);
    assert.equal(history.state.remoteToolAgentMenu, undefined);
    state.escapeMessageMenu({key: 'Escape'});
    assert.equal(state.sessionMenu, null);
    assert.equal(history.backs, 0);
});
