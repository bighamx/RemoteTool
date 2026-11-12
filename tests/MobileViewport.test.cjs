const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function viewportHarness(hash = '') {
    const events = {}, frames = [], polls = new Map(), timeouts = [];
    const classes = new Set(), styles = new Map();
    let now = 1000, pollId = 0;
    const navigation = {active: false, dataset: {nav: 'claude'}, addEventListener() {}, setAttribute() {}, removeAttribute() {}};
    navigation.classList = {toggle: (_, active) => { navigation.active = active; }};
    const viewport = {height: 800, offsetTop: 0, addEventListener(name, handler) { events['viewport:'+name] = handler; }};
    const composer = {matches: () => true, closest: () => composer, getClientRects: () => [{}]};
    const document = {
        activeElement: null,
        addEventListener(name, handler) { events[name] = handler; },
        getElementById: () => null, querySelector: () => null, querySelectorAll: selector => selector === '.side-nav a' ? [navigation] : [],
        documentElement: {clientHeight: 800, setAttribute() {}, style: {setProperty: (key, value) => styles.set(key, value), removeProperty: key => styles.delete(key)}},
        body: {classList: {contains: key => classes.has(key), remove: (...keys) => keys.forEach(key => classes.delete(key)), toggle: (key, on) => on ? classes.add(key) : classes.delete(key)}}
    };
    const matchMedia = () => ({matches: true, addEventListener() {}});
    vm.runInNewContext(fs.readFileSync('WebApplication/wwwroot/js/site.js', 'utf8'), {
        document, window: {visualViewport: viewport, matchMedia, addEventListener() {}},
        navigator: {}, location: {pathname: '/', hash}, localStorage: {getItem: () => null},
        innerWidth: 390, innerHeight: 800, matchMedia,
        Date: {now: () => now}, URLSearchParams,
        requestAnimationFrame(handler) { frames.push(handler); return frames.length; },
        setInterval(handler) { polls.set(++pollId, handler); return pollId; }, clearInterval: id => polls.delete(id),
        setTimeout: handler => timeouts.push(handler)
    });
    events.DOMContentLoaded();
    const flush = () => { while (frames.length) frames.shift()(); };
    return {document, events, viewport, classes, styles, polls, navigation,
        focus() { document.activeElement = composer; events.focusin(); flush(); },
        pollAfter(ms) { now += ms; for (const poll of polls.values()) poll(); flush(); },
        resize(height) { viewport.height = height; events['viewport:resize'](); flush(); },
        blur() { document.activeElement = null; events.focusout(); while(timeouts.length) timeouts.shift()(); flush(); }
    };
}

test('keyboard fallback still activates after focus without diagnostic sampling', () => {
    const h = viewportHarness();
    h.focus();
    assert.equal(h.classes.has('mobile-keyboard-unreported'), false);
    h.pollAfter(600);
    assert.equal(h.classes.has('mobile-keyboard-unreported'), true);
    assert.equal(h.classes.has('mobile-keyboard-open'), true);
    h.blur();
    assert.equal(h.classes.has('mobile-keyboard-open'), false);
    assert.equal(h.polls.size, 0);
});

test('measured keyboard resizing still adapts the viewport without a false fallback', () => {
    const h = viewportHarness();
    h.focus(); h.resize(430);
    assert.equal(h.styles.get('--mobile-height'), '430px');
    assert.equal(h.styles.get('--mobile-bottom-offset'), '370px');
    assert.equal(h.classes.has('mobile-keyboard-open'), true);
    assert.equal(h.classes.has('mobile-keyboard-unreported'), false);
    h.resize(800); h.pollAfter(600);
    assert.equal(h.classes.has('mobile-keyboard-open'), false);
    assert.equal(h.styles.get('--mobile-height'), '800px');
});

test('the Claude route highlights its own sidebar entry', () => {
    assert.equal(viewportHarness('#/claude?session=fixture').navigation.active, true);
    assert.equal(viewportHarness('#/hermes').navigation.active, false);
});
