import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {project, windowRows, toolPage, ATTACHMENT_MARKER} from '../../WebApplication/Tools/ClaudeBridge/history.mjs';
import {StateStore} from '../../WebApplication/Tools/ClaudeBridge/state.mjs';
import {createApp, createServer} from '../../WebApplication/Tools/ClaudeBridge/server.mjs';

const temp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'claude-bridge-'));
const wait = (predicate, ms = 3000) => new Promise((resolve, reject) => {
    const started = Date.now();
    const tick = () => { try { const value = predicate(); if (value) return resolve(value); } catch (e) { return reject(e); }
        if (Date.now() - started > ms) return reject(new Error('timeout')); setTimeout(tick, 5); };
    tick();
});
const SID = '11111111-1111-4111-8111-111111111111';
const at = n => new Date(Date.UTC(2026, 0, 1, 0, 0, n)).toISOString();
const user = (uuid, content, n) => ({type: 'user', uuid, session_id: SID, parent_tool_use_id: null, parent_agent_id: null, timestamp: at(n), message: {role: 'user', content}});
const assistant = (uuid, id, block, n, usage) => ({type: 'assistant', uuid, session_id: SID, parent_tool_use_id: null, parent_agent_id: null, timestamp: at(n), message: {id, model: 'claude-opus-5-5', role: 'assistant', content: [block], usage}});

const transcript = [
    user('u1', 'first question', 1),
    assistant('a1', 'msg1', {type: 'thinking', thinking: 'hidden'}, 2),
    assistant('a2', 'msg1', {type: 'text', text: 'Part one'}, 3, {input_tokens: 10, output_tokens: 5, cache_read_input_tokens: 100}),
    assistant('a3', 'msg1', {type: 'text', text: 'Part two'}, 3),
    assistant('a4', 'msg2', {type: 'tool_use', id: 'tool-read', name: 'Read', input: {file_path: '/p/a.js'}}, 4),
    user('r1', [{type: 'tool_result', tool_use_id: 'tool-read', content: 'RAW SOURCE'}], 5),
    assistant('a5', 'msg3', {type: 'tool_use', id: 'tool-edit', name: 'Edit', input: {file_path: '/p/a.js'}}, 6),
    user('r2', [{type: 'tool_result', tool_use_id: 'tool-edit', content: 'denied', is_error: true}], 7),
    assistant('a6', 'msg4', {type: 'tool_use', id: 'ask', name: 'AskUserQuestion', input: {questions: [{question: 'Which?', options: [{label: 'A'}, {label: 'B'}]}]}}, 8),
    user('r3', [{type: 'tool_result', tool_use_id: 'ask', content: 'User has answered your questions: "Which?"="B". You can now continue.'}], 9),
    {type: 'assistant', uuid: 'side', parent_tool_use_id: 'tool-read', session_id: SID, timestamp: at(9), message: {id: 'side', content: [{type: 'text', text: 'SUBAGENT'}]}},
    user('u2', [{type: 'text', text: 'with file'}, {type: 'text', text: `${ATTACHMENT_MARKER}\n附件：/data/a.png\n说明`}], 10),
    assistant('a7', 'msg5', {type: 'text', text: 'Done'}, 11, {input_tokens: 20, output_tokens: 7, cache_read_input_tokens: 300, cache_creation_input_tokens: 3}),
    user('u3', 'This session is being continued from a previous conversation that ran out of context.', 12),
    user('u4', '<command-name>/compact</command-name>\n<command-args></command-args>', 13),
];

test('history projects native transcripts into the shared row contract', () => {
    const view = project(transcript);
    const roles = view.rows.map(row => row.type || row.role);
    assert.deepEqual(roles, ['user', 'assistant', 'interaction', 'toolSummary', 'user', 'assistant', 'contextCompaction', 'user']);
    assert.equal(view.rows[1].content, 'Part one\n\nPart two', 'blocks of one API message merge');
    assert.equal(view.rows[0].editable, false, 'first prompt has no earlier entry to rewind to');
    assert.equal(view.rows[4].content, 'with file');
    assert.deepEqual(view.rows[4].attachment_paths, ['/data/a.png']);
    assert.equal(view.rows[4].rewind_at, 'r3');
    assert.equal(view.rows[7].content, '/compact');
    assert.deepEqual(view.rows[2].interactions[0], {question: 'Which?', choices: ['A', 'B'], answer: 'B', status: 'answered'});
    assert.ok(!JSON.stringify(view.rows).includes('SUBAGENT') && !JSON.stringify(view.rows).includes('RAW SOURCE'));
    const page = toolPage(view.tools.get(view.rows[3].tool_summary_id));
    assert.deepEqual([page.tools, page.file_changes], [1, 1]);
    assert.deepEqual(page.data.map(tool => tool.status), ['completed', 'failed']);
    assert.match(page.data[0].description, /RAW SOURCE/);
    assert.equal(view.context.tokens, 330);
    assert.equal(view.usage.input_tokens, 30);
});

test('a live turn keeps its tools out of history and windows count visible rows', () => {
    const live = project(transcript.slice(0, 8), {activeSince: Date.parse(at(1))});
    assert.ok(!live.rows.some(row => row.type === 'toolSummary'));
    assert.equal(live.active.length, 2);
    const rows = Array.from({length: 10}, (_, i) => ({id: 'r' + i, role: i % 2 ? 'assistant' : 'user', content: 'x'}));
    const recent = windowRows(rows, 4);
    assert.deepEqual([recent.data.map(r => r.id), recent.has_more, recent.oldest_id], [['r6', 'r7', 'r8', 'r9'], true, 'r6']);
    assert.deepEqual(windowRows(rows, 4, 'r6').data.map(r => r.id), ['r2', 'r3', 'r4', 'r5']);
});

// ---- fake SDK -----------------------------------------------------------------
function fakeSdk(script, {info = {sessionId: SID, cwd: os.tmpdir(), lastModified: 1}} = {}) {
    const calls = [];
    const sdk = {
        calls,
        getSessionInfo: async id => id === SID && info ? info : undefined,
        listSessions: async () => info ? [info] : [],
        getSessionMessages: async () => transcript,
        resolveSettings: async () => ({effective: {env: {}}}),
        renameSession: async () => {}, deleteSession: async () => {}, forkSession: async () => ({sessionId: '22222222-2222-4222-8222-222222222222'}),
        query({prompt, options}) {
            const control = {interrupted: false, closed: false, modes: []};
            calls.push({prompt, options, control});
            const iterator = script(prompt[Symbol.asyncIterator](), options, control);
            return Object.assign(iterator, {
                interrupt: async () => { control.interrupted = true; control.onInterrupt?.(); },
                close: () => { control.closed = true; }, setPermissionMode: async mode => { control.modes.push(mode); },
                supportedModels: async () => [{value: 'opus', displayName: 'Opus', supportedEffortLevels: ['low', 'high']}],
            });
        },
    };
    return sdk;
}
const ok = {type: 'result', subtype: 'success', is_error: false, usage: {input_tokens: 1}, total_cost_usd: 0.01, modelUsage: {m: {contextWindow: 1000000}}};
const init = {type: 'system', subtype: 'init', session_id: SID};

function setup(script, options) {
    const root = temp(), sdk = fakeSdk(script, options);
    const exec = async (file, args) => file === 'ps' ? {stdout: String(options?.parent ?? 1)} : args[0] === 'auth' ? {stdout: JSON.stringify({loggedIn: true, apiProvider: 'firstParty'})} : {stdout: JSON.stringify(typeof options?.holders === 'function' ? options.holders() : options?.holders || [])};
    const app = createApp({root, executable: '/bin/claude', attachments: path.join(root, 'att'), sdk, exec});
    return {root, sdk, app};
}

test('a turn streams coalesced events, completes, and its receipt is idempotent', async () => {
    const {app, sdk} = setup(async function* (input) {
        await input.next();
        yield init;
        yield {type: 'stream_event', event: {type: 'message_start', message: {id: 'm1'}}};
        yield {type: 'stream_event', event: {type: 'content_block_start', index: 0, content_block: {type: 'text'}}};
        for (const piece of ['Hel', 'lo']) yield {type: 'stream_event', event: {type: 'content_block_delta', index: 0, delta: {type: 'text_delta', text: piece}}};
        yield {type: 'assistant', uuid: 'x', message: {id: 'm1', content: [{type: 'text', text: 'Hello'}]}};
        yield {type: 'assistant', uuid: 'y', message: {id: 'm2', content: [{type: 'tool_use', id: 't1', name: 'Bash', input: {command: 'ls'}}]}};
        yield {type: 'user', message: {content: [{type: 'tool_result', tool_use_id: 't1', content: 'ok'}]}};
        yield ok;
    });
    const key = 'k'.repeat(20);
    const run = await app.runs.start({session_id: SID, input: 'hi'}, key);
    assert.equal(run.delivery, 'accepted');
    await wait(() => app.store.run(run.run_id).status === 'completed');
    const types = app.runs.eventsAfter(run.run_id).map(e => e.type);
    assert.deepEqual(types, ['run.started', 'run.running', 'message.started', 'message.delta', 'message.completed', 'tool.started', 'tool.completed', 'turn.completed', 'run.completed']);
    assert.equal(app.runs.eventsAfter(run.run_id).find(e => e.type === 'message.delta').delta, 'Hello');
    assert.equal(app.runs.eventsAfter(run.run_id).find(e => e.type === 'message.completed').item_id, 'm1:0');
    assert.equal(sdk.calls[0].options.resume, SID);
    assert.match(sdk.calls[0].options.systemPrompt.append, /MEDIA:/);
    assert.equal(app.store.meta(SID).context_window, 1000000);
    assert.equal((await app.runs.start({session_id: SID, input: 'hi'}, key)).run_id, run.run_id, 'same key returns the accepted run');
    await assert.rejects(app.runs.start({session_id: SID, input: 'other'}, key), e => e.status === 409 && e.delivery === 'rejected');
    assert.equal(sdk.calls.length, 1, 'no replay');
    assert.deepEqual(app.runs.lookup(key), {found: true, kind: 'turn', delivery: 'accepted', run_id: run.run_id, session_id: SID, status: 'completed'});
    assert.equal(app.store.data.models.list[0].value, 'opus');
});

test('approvals map to native permission results, including suggestions and questions', async () => {
    const decisions = [];
    const {app} = setup(async function* (input, options) {
        await input.next(); yield init;
        decisions.push(await options.canUseTool('Bash', {command: 'rm x'}, {signal: new AbortController().signal, suggestions: [{type: 'addRules', rules: [{toolName: 'Bash', ruleContent: 'rm:*'}], behavior: 'allow', destination: 'localSettings'}]}));
        decisions.push(await options.canUseTool('Write', {file_path: '/x'}, {signal: new AbortController().signal}));
        decisions.push(await options.canUseTool('AskUserQuestion', {questions: [{question: 'Which?', multiSelect: true, options: [{label: 'A'}, {label: 'B'}]}]}, {signal: new AbortController().signal}));
        yield ok;
    });
    const run = await app.runs.start({session_id: SID, input: 'go'}, 'a'.repeat(20));
    const pending = () => app.store.run(run.run_id).approval;
    await wait(() => pending()?.tool === 'Bash');
    assert.deepEqual(pending().choices.map(c => c.id), ['once', 'always', 'deny']);
    assert.match(pending().choices[1].detail, /rm:\*/);
    assert.equal(app.store.run(run.run_id).status, 'waiting');
    assert.throws(() => app.runs.approve(run.run_id, {request_id: 'stale', choice: 'once'}), e => e.status === 409);
    app.runs.approve(run.run_id, {request_id: pending().request_id, choice: 'always'});
    await wait(() => pending()?.tool === 'Write');
    assert.deepEqual(pending().choices.map(c => c.id), ['once', 'deny'], 'no persistent option without a native suggestion');
    assert.throws(() => app.runs.approve(run.run_id, {request_id: pending().request_id, choice: 'always'}));
    app.runs.approve(run.run_id, {request_id: pending().request_id, choice: 'deny'});
    await wait(() => pending()?.kind === 'user_input');
    assert.throws(() => app.runs.approve(run.run_id, {request_id: pending().request_id, answers: {}}), /回答所有问题/);
    app.runs.approve(run.run_id, {request_id: pending().request_id, answers: {0: {answers: ['A', 'B']}}});
    await wait(() => app.store.run(run.run_id).status === 'completed');
    assert.equal(decisions[0].behavior, 'allow'); assert.equal(decisions[0].updatedPermissions[0].destination, 'localSettings');
    assert.equal(decisions[1].behavior, 'deny');
    assert.deepEqual(decisions[2].updatedInput.answers, {'Which?': 'A, B'});
});

test('steering feeds the same process and the run waits for the steered turn', async () => {
    let seen = [];
    const {app} = setup(async function* (input) {
        seen.push((await input.next()).value); yield init;
        await wait(() => app.store.data.receipts['s'.repeat(20)]);
        yield ok;
        const next = await input.next(); seen.push(next.value);
        yield ok;
        assert.equal((await input.next()).done, true, 'input closes after the steered turn');
    });
    const run = await app.runs.start({session_id: SID, input: 'one'}, 'o'.repeat(20));
    await wait(() => app.store.run(run.run_id).status === 'running');
    assert.deepEqual(app.runs.steer(run.run_id, {input: 'two'}, 's'.repeat(20)), {accepted: true, delivery: 'accepted', run_id: run.run_id});
    assert.equal(app.runs.steer(run.run_id, {input: 'two'}, 's'.repeat(20)).accepted, true, 'retry is idempotent');
    await wait(() => app.store.run(run.run_id).status === 'completed');
    assert.deepEqual(seen.map(m => m.message.content[0].text), ['one', 'two']);
    assert.throws(() => app.runs.steer(run.run_id, {input: 'late'}, 'l'.repeat(20)), e => e.status === 409 && e.delivery === 'rejected');
});

test('stop interrupts gracefully and marks the run cancelled', async () => {
    const {app, sdk} = setup(async function* (input, options, control) {
        await input.next(); yield init;
        await new Promise(resolve => { control.onInterrupt = resolve; });
        yield {type: 'result', subtype: 'error_during_execution', is_error: true};
    });
    const run = await app.runs.start({session_id: SID, input: 'long'}, 'p'.repeat(20));
    await wait(() => app.store.run(run.run_id).status === 'running');
    assert.equal((await app.runs.stop(run.run_id)).stop_requested, true);
    await wait(() => app.store.run(run.run_id).status === 'cancelled');
    assert.equal(sdk.calls[0].control.interrupted, true);
    assert.equal(sdk.calls[0].options.abortController.signal.aborted, false, 'no hard kill when interrupt works');
});

test('failure before start is rejected-safe; an unknown result blocks until acknowledged', async () => {
    let mode = 'spawn';
    const {app} = setup(async function* (input) {
        await input.next();
        if (mode === 'spawn') throw new Error('spawn ENOENT secret-token-123');
        yield init; // started, then the stream ends without a result
    });
    const first = await app.runs.start({session_id: SID, input: 'a'}, 'f'.repeat(20));
    await wait(() => app.store.run(first.run_id).status === 'failed');
    assert.ok(!app.store.run(first.run_id).error.includes('secret'), 'raw errors never leak');
    mode = 'drop';
    const second = await app.runs.start({session_id: SID, input: 'b'}, 'g'.repeat(20));
    await wait(() => app.store.run(second.run_id).status === 'acceptance_unknown');
    await assert.rejects(app.runs.start({session_id: SID, input: 'c'}, 'h'.repeat(20)), e => e.code === 'unresolved_run' && e.run_id === second.run_id);
    assert.equal((await app.sessions.detail(SID)).unresolved_run.run_id, second.run_id);
    app.runs.acknowledge(second.run_id);
    assert.equal((await app.sessions.detail(SID)).unresolved_run, null);
    assert.ok(await app.runs.start({session_id: SID, input: 'c'}, 'h'.repeat(20)));
});

test('preflight refuses sessions held by another Claude process and custom endpoints', async () => {
    const {app, sdk} = setup(async function* () {}, {holders: [{sessionId: SID, pid: 42, status: 'idle'}]});
    await assert.rejects(app.runs.start({session_id: SID, input: 'x'}, 'q'.repeat(20)), e => e.code === 'session_held' && e.delivery === 'rejected');
    assert.equal((await app.sessions.activity(SID)).running, true);
    assert.equal(sdk.calls.length, 0);
    assert.equal(Object.keys(app.store.data.receipts).length, 0, 'rejected sends leave no receipt');
    const own = setup(async function* () {}, {holders: [{sessionId: SID, pid: 43, status: 'busy'}], parent: process.pid});
    assert.equal((await own.app.sessions.activity(SID)).running, false, 'our own lingering SDK child is not an external holder');
    const custom = setup(async function* () {});
    custom.sdk.resolveSettings = async () => ({effective: {env: {ANTHROPIC_BASE_URL: 'https://gateway.example'}}});
    await assert.rejects(custom.app.runs.start({session_id: SID, input: 'x'}, 'r'.repeat(20)), e => e.code === 'custom_endpoint');
});

test('rewind truncates history now and resumes at the kept entry on the next turn', async () => {
    const {app, sdk} = setup(async function* (input) { await input.next(); yield init; yield ok; });
    assert.equal((await app.sessions.rewind(SID, {message_id: 'u2'})).rewound, true);
    const rows = (await app.sessions.messages(SID, 80)).data;
    assert.ok(!rows.some(row => row.id === 'u2'));
    await assert.rejects(app.sessions.rewind(SID, {message_id: 'u1'}), /第一条/);
    const run = await app.runs.start({session_id: SID, input: 'edited'}, 'w'.repeat(20));
    await wait(() => app.store.run(run.run_id).status === 'completed');
    assert.equal(sdk.calls[0].options.resumeSessionAt, 'r3');
    assert.equal(app.store.meta(SID).rewind, undefined, 'rewind is consumed once the native process starts');
});

test('restart marks live runs unknown and a corrupt state fails closed', () => {
    const root = temp();
    const store = new StateStore(root);
    store.data.runs.r = {id: 'r', session_id: SID, status: 'running', created_at: Date.now()};
    store.save();
    assert.equal(new StateStore(root).data.runs.r.status, 'acceptance_unknown');
    fs.writeFileSync(path.join(root, 'state-v2.json'), '{broken');
    assert.throws(() => new StateStore(root));
});

test('the HTTP layer authenticates and SSE resumes from Last-Event-ID', async () => {
    const {app} = setup(async function* (input) { await input.next(); yield init; yield ok; });
    const token = 't'.repeat(48), server = createServer(app, token);
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`, auth = {Authorization: 'Bearer ' + token};
    try {
        assert.equal((await fetch(base + '/sessions')).status, 401);
        assert.equal((await fetch(base + '/sessions/..%2Fx', {headers: auth})).status, 400);
        const list = await (await fetch(base + '/sessions', {headers: auth})).json();
        assert.equal(list.data[0].id, SID);
        const missing = await fetch(base + '/runs', {method: 'POST', headers: auth, body: JSON.stringify({session_id: SID, input: 'x'})});
        assert.deepEqual([missing.status, (await missing.json()).delivery], [400, 'rejected']);
        const run = await (await fetch(base + '/runs', {method: 'POST', headers: {...auth, 'Idempotency-Key': 'z'.repeat(20)}, body: JSON.stringify({session_id: SID, input: 'x'})})).json();
        await wait(() => app.store.run(run.run_id).status === 'completed');
        const text = await (await fetch(`${base}/runs/${run.run_id}/events`, {headers: {...auth, 'Last-Event-ID': '2'}})).text();
        const events = text.split('\n\n').filter(chunk => chunk.includes('data:')).map(chunk => JSON.parse(chunk.split('data: ')[1]));
        assert.ok(events.every(e => e.seq > 2));
        assert.equal(events.at(-1).type, 'run.completed');
        const lookup = await (await fetch(`${base}/runs/lookup?key=${'z'.repeat(20)}`, {headers: auth})).json();
        assert.equal(lookup.run_id, run.run_id);
        const active = await (await fetch(`${base}/sessions/${SID}/active-run`, {headers: auth})).json();
        assert.equal(active.run_id, null);
    } finally { server.close(); }
});

test('takeover signals only the confirmed desktop holder and waits for it to exit', async () => {
    let rows = [{sessionId: SID, pid: 4242, startedAt: 1700000000000, status: 'idle'}];
    const killed = [];
    const {app} = setup(async function* () {}, {holders: () => rows});
    const opts = {kill: (pid, signal) => killed.push([pid, signal]), wait: async () => {}, timeout: 2000};
    await assert.rejects(app.sessions.takeover(SID, {expected_pid: 999}, opts), e => e.code === 'holder_changed');
    await assert.rejects(app.sessions.takeover(SID, {expected_pid: 4242, expected_started_at: 1}, opts), e => e.code === 'holder_changed');
    assert.equal(killed.length, 0, 'a changed holder is never signalled');
    await assert.rejects(app.sessions.takeover(SID, {expected_pid: 4242, expected_started_at: 1700000000}, opts), e => e.code === 'holder_alive');
    assert.deepEqual(killed, [[4242, 'SIGTERM']], 'graceful signal only, no forced kill');
    const exiting = {...opts, kill: (pid, signal) => { killed.push([pid, signal]); rows = []; }};
    assert.deepEqual(await app.sessions.takeover(SID, {expected_pid: 4242, expected_started_at: 1700000000}, exiting), {ready: true, terminated_pid: 4242, was_busy: false});
    assert.equal((await app.sessions.activity(SID)).running, false);
    rows = [{sessionId: SID, pid: 77, startedAt: Date.now(), status: 'busy'}];
    const own = setup(async function* () {}, {holders: () => rows, parent: process.pid});
    assert.deepEqual(await own.app.sessions.takeover(SID, {}, opts), {ready: true}, 'our own SDK child is not taken over');
});
