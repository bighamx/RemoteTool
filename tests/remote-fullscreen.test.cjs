const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture({mobile = true, native = true, orientation = true, deferred = false, deferredOrientation = false} = {}) {
    const events = new Map(), elements = new Map(), calls = [];
    const classes = () => {
        const values = new Set();
        return {add: value => values.add(value), remove: value => values.delete(value), contains: value => values.has(value)};
    };
    const emit = name => { for (const callback of events.get(name) || []) callback(); };
    const listen = (name, callback) => events.set(name, [...events.get(name) || [], callback]);
    function element(id) {
        const el = {id, classList: classes(), events: {}, attrs: {}, parent: 'original',
            addEventListener(name, handler) { this.events[name] = handler; },
            setAttribute(name, value) { this.attrs[name] = value; },
            before(anchor) { anchor.original = this.parent; },
            closest: () => null};
        elements.set(id, el);
        return el;
    }
    const container = element('screenshot-container');
    for (const id of ['fullscreen-btn', 'fullscreen-exit-btn', 'fullscreen-exit-hint']) element(id);
    const document = {fullscreenElement: null, activeElement: null,
        getElementById: id => elements.get(id), addEventListener: listen,
        body: {classList: classes(), appendChild(el) { el.parent = 'body'; }},
        createComment() { return {replaceWith(el) { el.parent = this.original; }}; },
        async exitFullscreen() { calls.push('exit'); this.fullscreenElement = null; emit('fullscreenchange'); }};
    let finishRequest, finishLock;
    container.requestFullscreen = async options => {
        calls.push(['request', options.navigationUI]);
        if (!native) throw new Error('Denied');
        if (deferred) await new Promise(resolve => { finishRequest = resolve; });
        document.fullscreenElement = container; emit('fullscreenchange');
    };
    const screen = {orientation: {
        async lock(mode) { assert.equal(document.fullscreenElement, container); calls.push(['lock', mode]); if(deferredOrientation) await new Promise(resolve => { finishLock = resolve; }); if (!orientation) throw new Error('Unsupported'); },
        unlock() { calls.push('unlock'); }
    }};
    const location = {href: 'http://fixture/#/desktop', hash: '#/desktop'};
    const entries = [{route: 'desktop'}];
    const history = {get state() { return entries.at(-1); }, backs: 0,
        pushState(state) { entries.push(state); },
        back() { this.backs++; queueMicrotask(() => { entries.pop(); emit('popstate'); }); }};
    const context = {window: {addEventListener: listen, RemoteToolOnReady() {}}, document, screen, history, location,
        console, setTimeout() {}};
    vm.runInNewContext(fs.readFileSync('WebApplication/wwwroot/js/remote.js', 'utf8') + '\nthis.Control = RemoteControl;', context);
    const state = Object.assign(Object.create(context.Control.prototype), {
        isFullscreen: false, fullscreenEntering: false, fullscreenNativeActive: false, fullscreenHistoryClosing: false,
        fullscreenHistoryId: 'fixture-fullscreen', fullscreenAnchor: null, streamZoom: 1,
        isPhoneFileLayout: () => mobile, setStreamZoom() { calls.push('resize'); }
    });
    state.initFullscreen();
    return {state, document, elements, container, history, entries, calls, emit,
        finishRequest: () => finishRequest(), finishLock: () => finishLock(), settle: () => new Promise(setImmediate)};
}

test('mobile fullscreen uses the native API before landscape locking and Back restores the page', async () => {
    const h = fixture();
    await h.elements.get('fullscreen-btn').events.click();
    assert.deepEqual(h.calls.slice(0, 2), [['request', 'hide'], ['lock', 'landscape']]);
    assert.equal(h.container.parent, 'body');
    assert.equal(h.document.body.classList.contains('remote-desktop-fullscreen'), true);
    assert.equal(h.history.state.route, 'desktop');
    h.history.back(); await h.settle();
    assert.equal(h.state.isFullscreen, false);
    assert.equal(h.document.fullscreenElement, null);
    assert.equal(h.container.parent, 'original');
    assert.equal(h.document.body.classList.contains('remote-desktop-fullscreen'), false);
    assert.equal(h.history.backs, 1);
    assert.equal(h.entries.length, 1);
});

test('the visible exit button and native fullscreen exit consume only their own history entry', async () => {
    for (const nativeExit of [false, true]) {
        const h = fixture(); await h.state.enterFullscreen();
        if (nativeExit) await h.document.exitFullscreen();
        else await h.elements.get('fullscreen-exit-btn').events.click();
        await h.settle();
        assert.equal(h.state.isFullscreen, false);
        assert.equal(h.history.backs, 1);
        assert.equal(h.state.fullscreenHistoryClosing, false);
        assert.equal(h.container.parent, 'original');
    }
});

test('denied native fullscreen stays explicitly labelled and remains escapable', async () => {
    const h = fixture({native: false}); await h.state.enterFullscreen();
    assert.match(h.elements.get('fullscreen-exit-hint').textContent, /网页展开模式/);
    assert.equal(h.calls.some(call => Array.isArray(call) && call[0] === 'lock'), false);
    h.history.back(); await h.settle();
    assert.equal(h.state.isFullscreen, false);
    assert.equal(h.container.parent, 'original');
});

test('failed landscape locking keeps native fullscreen and provides a rotation hint', async () => {
    const h = fixture({orientation: false}); await h.state.enterFullscreen();
    assert.equal(h.document.fullscreenElement, h.container);
    assert.match(h.elements.get('fullscreen-exit-hint').textContent, /旋转手机横屏/);
    await h.state.exitFullscreen(); await h.settle();
    assert.equal(h.document.fullscreenElement, null);
});

test('Back during a pending native request cannot leave a late fullscreen behind', async () => {
    const h = fixture({deferred: true}); const pending = h.state.enterFullscreen();
    h.history.back(); await h.settle();
    assert.equal(h.state.isFullscreen, false);
    h.finishRequest(); await pending; await h.settle();
    assert.equal(h.document.fullscreenElement, null);
    assert.equal(h.state.fullscreenEntering, false);
    assert.equal(h.container.parent, 'original');
    assert.equal(h.history.backs, 1);
});

test('desktop fullscreen skips mobile history and orientation changes', async () => {
    const h = fixture({mobile: false}); await h.state.enterFullscreen();
    assert.equal(h.entries.length, 1);
    assert.equal(h.calls.some(call => Array.isArray(call) && call[0] === 'lock'), false);
    await h.state.exitFullscreen();
    assert.equal(h.history.backs, 0);
});

test('the exit control never forwards its keyboard input to the remote desktop', () => {
    const h = fixture();
    h.document.activeElement = {closest: selector => selector === '.remote-fullscreen-exit'};
    assert.equal(h.state.isStreamAreaFocused(), false);
});

test('a late orientation result cannot restore the fullscreen hint after Back', async () => {
    const h = fixture({deferredOrientation: true}); const pending = h.state.enterFullscreen();
    await h.settle(); h.history.back(); await h.settle();
    h.finishLock(); await pending;
    assert.equal(h.state.isFullscreen, false);
    assert.equal(h.document.fullscreenElement, null);
    assert.equal(h.elements.get('fullscreen-exit-hint').classList.contains('show'), false);
});
