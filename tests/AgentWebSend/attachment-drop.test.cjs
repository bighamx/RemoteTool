const test = require('node:test');
const assert = require('node:assert/strict');
const {createHarness} = require('./agent-send.test.cjs');

const file = (name, size = 12) => ({name, size, type: 'text/plain'});
function drag(files = [], overrides = {}) {
    return {
        prevented: false, stopped: false,
        preventDefault() { this.prevented = true; },
        stopPropagation() { this.stopped = true; },
        dataTransfer: {types: ['Files'], files, items: [], ...overrides},
    };
}
function harness(agent = 'codex') {
    const h = createHarness([], agent);
    h.uploads = [];
    h.context.FormData = class {
        append(name, value) { this[name] = value; }
    };
    h.api.request = async (route, {body}) => {
        h.uploads.push({route, file: body.file});
        return {attachment: {id: `file-${h.uploads.length}`, name: body.file.name}};
    };
    return h;
}

for (const agent of ['codex', 'hermes', 'claude']) {
    test(`${agent} drops multiple files through the existing session upload path`, async () => {
        const h = harness(agent), event = drag([file('one.txt'), file('two.txt')]);
        h.state.draft = 'preserve the draft';
        h.state.onFileDragEnter(event);
        assert.equal(h.state.fileDragDepth, 1);
        h.state.onFileDragOver(event);
        assert.equal(event.dataTransfer.dropEffect, 'copy');
        await h.state.onFileDrop(event);
        assert.ok(event.prevented && event.stopped);
        assert.equal(h.state.fileDragDepth, 0);
        assert.equal(h.state.uploading, false);
        assert.equal(h.state.draft, 'preserve the draft');
        assert.deepEqual(h.uploads.map(row => row.file.name), ['one.txt', 'two.txt']);
        assert.ok(h.uploads.every(row => row.route.endsWith('/sessions/session-1/files')));
        assert.deepEqual(Array.from(h.state.attachments, row => row.name), ['one.txt', 'two.txt']);
    });
}

test('nested file drag targets retain the hint and leaving or changing sessions resets it', () => {
    const h = harness(), event = drag();
    h.state.onFileDragEnter(event); h.state.onFileDragEnter(event);
    h.state.onFileDragLeave();
    assert.equal(h.state.fileDragDepth, 1);
    h.state.onFileDragLeave(); h.state.onFileDragLeave();
    assert.equal(h.state.fileDragDepth, 0);
    h.state.onFileDragEnter(event);
    h.options.watch.selectedId.call(h.state);
    assert.equal(h.state.fileDragDepth, 0);
});

test('ordinary text dragging keeps the native text-drop behavior', async () => {
    const h = harness(), event = drag([], {types: ['text/plain']});
    h.state.onFileDragEnter(event); h.state.onFileDragOver(event);
    await h.state.onFileDrop(event);
    assert.equal(event.prevented, false);
    assert.equal(event.stopped, false);
    assert.equal(h.state.fileDragDepth, 0);
    assert.equal(h.uploads.length, 0);
});

test('directories and attachment limit violations are rejected before any upload', async () => {
    const h = harness();
    await h.state.onFileDrop(drag([file('ordinary.txt')], {items: [{webkitGetAsEntry: () => ({isDirectory: true})}]}));
    assert.match(h.state.error, /文件夹/);
    h.state.attachments = [{id: 'existing'}];
    await h.state.onFileDrop(drag(Array.from({length: 8}, (_, index) => file(`${index}.txt`))));
    assert.match(h.state.error, /8/);
    for (const size of [0, 500 * 1024 * 1024 + 1]) {
        await h.state.onFileDrop(drag([file('invalid.bin', size)]));
        assert.match(h.state.error, /500 MB/);
    }
    assert.equal(h.uploads.length, 0);
    assert.equal(h.state.attachments[0].id, 'existing');
    assert.equal(h.state.fileDragDepth, 0);
});

test('a drop without a selected session or during an upload never navigates or submits again', async () => {
    const h = harness();
    h.state.selectedId = '';
    const unselected = drag([file('one.txt')]);
    await h.state.onFileDrop(unselected);
    assert.ok(unselected.prevented);
    h.state.selectedId = 'session-1'; h.state.uploading = true;
    const busy = drag([file('two.txt')]);
    h.state.onFileDragOver(busy);
    assert.equal(busy.dataTransfer.dropEffect, 'none');
    await h.state.onFileDrop(busy);
    assert.ok(busy.prevented);
    assert.equal(h.uploads.length, 0);
});

test('an in-flight drop blocks sending and retains its original destination after switching sessions', async () => {
    const h = harness(), routes = [];
    let finish;
    h.api.request = async route => {
        routes.push(route);
        return routes.length === 1 ? new Promise(resolve => { finish = resolve; }) : {attachment: {id: 'two'}};
    };
    h.state.draft = 'wait for the attachments';
    const pending = h.state.onFileDrop(drag([file('one.txt'), file('two.txt')]));
    assert.equal(h.state.uploading, true);
    await h.state.send();
    assert.equal(h.posts.length, 0);
    assert.equal(h.state.draft, 'wait for the attachments');
    h.state.selectedId = 'session-2'; h.state.attachments = [{id: 'new-session'}];
    finish({attachment: {id: 'one'}});
    await pending;
    assert.ok(routes.every(route => route.endsWith('/sessions/session-1/files')));
    assert.equal(h.state.attachments[0].id, 'new-session');
    assert.deepEqual(JSON.parse(h.storage.get('remotetool:web:draft-files:codex:session-1')).map(row => row.id), ['one', 'two']);
});

test('a partial upload failure retains completed attachments and ends the busy state without retrying', async () => {
    const h = harness(); let calls = 0;
    h.api.request = async () => {
        if (++calls === 2) throw new Error('upload failed');
        return {attachment: {id: 'completed'}};
    };
    await h.state.onFileDrop(drag([file('one.txt'), file('two.txt')]));
    assert.equal(calls, 2);
    assert.equal(h.state.attachments[0].id, 'completed');
    assert.equal(h.state.error, 'upload failed');
    assert.equal(h.state.uploading, false);
});
