const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../WebApplication/Services/Codex/CodexDesktopAppServer.cs'), 'utf8');
const bootstrap = source.match(/Bootstrap = """\r?\n([\s\S]*?)\r?\n        """;/)[1]
    .replace('await import(url)', 'await importModule(url)');

async function fixture(state, { legacy = false, error = null, nativeThread = null, readError = null, nativeTurns = null, turnsError = null } = {}) {
    if (state) state = { rolloutPath: 'home/rollout', ...state };
    const calls = [];
    let disconnected;
    const client = {
        getConversation: async id => id === 'session' ? state : null,
        sendRequest: async (method, params) => {
            calls.push({ method, params });
            if (method === 'thread/read') {
                if (readError) throw readError;
                return { thread: nativeThread };
            }
            if (method === 'thread/turns/list') {
                if (turnsError) throw turnsError;
                return {data: nativeTurns ?? (state?.turns?.length
                    ? state.turns.map(turn=>({...turn,id:turn.turnId}))
                    : [{id:'persisted',status:'completed',startedAt:123,items:[]}])};
            }
            if (error) throw error;
            return { turn: { id: 'new-turn' } };
        },
        onRpcBroken: handler => { disconnected = handler; },
        hasLocalHistorySubscription: async () => false,
        resumeConversation: async params => { calls.push({ method: 'resumeConversation', params }); return { status: 'ready' }; },
    };
    client.startTurn = async (id, turnStart) => client.sendRequest('turn/start', { ...turnStart.request, threadId: id });
    for (const method of ['compactThread', 'interruptConversation', 'replyWithCommandExecutionApprovalDecision',
        'replyWithFileChangeApprovalDecision', 'replyWithPermissionsRequestApprovalResponse', 'replyWithUserInputResponse']) {
        client[method] = async (...params) => { calls.push({ method, params }); };
    }
    const atom = {};
    const manager = { forHost: id => { assert.equal(id, 'local'); return client; } };
    const scope = { node: {}, get: value => value === atom ? manager : null };
    const node = { __reactFiber$fixture: { memoizedState: { memoizedState: { current: scope } } } };
    const module = legacy ? {
        __webpack_require__: {
            c: { manager: { exports: { atom } } },
            m: { manager: function () { return 'AppServerManager RPC is unavailable for hostId: .forHost('; } },
        },
    } : { atom };
    const context = vm.createContext({
        window: {},
        document: {
            scripts: [{ src: 'app://-/assets/123.abc.js' }],
            querySelectorAll: selector => selector === 'link[href]' ? [] : [node],
        },
        performance: { getEntriesByType: () => [] },
        importModule: async () => module,
    });
    assert.equal(await vm.runInContext(bootstrap, context), true);
    return { call: (method, params = {}) => context.window.__remoteToolDesktopAppServer(method, { conversationId: 'session', ...params }),
        calls, client, context, disconnect: () => disconnected() };
}

test('discovers ESM and rspack native managers and invalidates disconnected clients', async () => {
    for (const legacy of [false, true]) {
        const f = await fixture({ rolloutPath: 'home/rollout' }, { legacy });
        assert.equal((await f.call('probe')).available, true);
        f.disconnect();
        assert.equal(f.context.window.__remoteToolDesktopAppServer, undefined);
    }
});

test('sends once with the original message identity, attachments and permission fields', async () => {
    const f = await fixture({});
    const request = { input: [{ type: 'text', text: '`$() unicode 中文' }], clientUserMessageId: 'message-key',
        approvalPolicy: 'on-request', sandboxPolicy: { type: 'workspaceWrite' }, additionalContext: { attachment: 'file' } };
    const reply = await f.call('thread-follower-start-turn', { turnStart: { request } });
    assert.equal(reply.result.turn.id, 'new-turn');
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].method, 'turn/start');
    assert.deepEqual(JSON.parse(JSON.stringify(f.calls[0].params)), { ...request, threadId: 'session' });
});

test('prepares the native subscription while preserving server model and permission configuration', async () => {
    const f = await fixture({ cwd: 'C:/workspace' });
    assert.equal((await f.call('prepare')).ready, true);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].method, 'resumeConversation');
    assert.equal(f.calls[0].params.preserveServerConfiguration, true);
    assert.deepEqual(Array.from(f.calls[0].params.workspaceRoots), ['C:/workspace']);
    f.client.hasLocalHistorySubscription = async () => true;
    assert.equal((await f.call('prepare')).ready, true);
    assert.equal(f.calls.length, 1);
});

test('preserves active snapshots, pending native requests and usage', async () => {
    const request = { id: 123, method: 'item/tool/requestUserInput', params: { turnId: 'active' } };
    const f = await fixture({ turns: [{ turnId: 'active', status: 'inProgress' }], requests: [request],
        turnHistory: { history: { entitiesByKey: { older: { turnId: 'older', status: 'completed', turnStartedAtMs: 100 } } } },
        latestTokenUsageInfo: { totalTokens: 20 } });
    const snapshot = await f.call('snapshot');
    assert.equal(snapshot.turns.at(-1).turnId, 'active');
    assert.equal(snapshot.requests[0], request);
    assert.equal(snapshot.latestTokenUsageInfo.totalTokens, 20);
    assert.equal(f.calls.length, 0);
});

test('reads native paginated history when desktop has evicted historical turns', async () => {
    const f = await fixture({ turns: [] });
    const snapshot = await f.call('snapshot');
    assert.equal(snapshot.turns[0].turnId, 'persisted');
    assert.equal(snapshot.turns[0].turnStartedAtMs, 123000);
    assert.equal(f.calls[0].method, 'thread/turns/list');
    assert.equal(f.calls[0].params.limit, 2);
});

test('steers the observed native turn and rejects a completed turn before sending', async () => {
    const f = await fixture({ turns: [{ turnId: 'current', status: 'inProgress' }] });
    await f.call('thread-follower-steer-turn', { input: [], clientUserMessageId: 'key' });
    assert.equal(f.calls[1].params.expectedTurnId, 'current');
    assert.equal(f.calls[1].params.clientUserMessageId, 'key');
    const idle = await fixture({ turns: [] }, {nativeThread:{id:'session',status:{type:'idle'}}});
    assert.equal((await idle.call('thread-follower-steer-turn')).rejected, true);
    assert.ok(idle.calls.every(row=>row.method==='thread/turns/list'||row.method==='thread/read'));
});

test('maps native interrupt, compaction and approval replies without expanding decisions', async () => {
    const f = await fixture({});
    const mappings = [
        ['thread-follower-compact-thread', 'compactThread', ['session']],
        ['thread-follower-interrupt-turn', 'interruptConversation', ['session', 'user-stop', 'expected']],
        ['thread-follower-command-approval-decision', 'replyWithCommandExecutionApprovalDecision', ['session', 7, 'decline']],
        ['thread-follower-file-approval-decision', 'replyWithFileChangeApprovalDecision', ['session', 7, 'decline']],
        ['thread-follower-permissions-request-approval-response', 'replyWithPermissionsRequestApprovalResponse', ['session', 7, { scope: 'turn' }]],
        ['thread-follower-submit-user-input', 'replyWithUserInputResponse', ['session', 7, { scope: 'turn' }]],
    ];
    for (const [method, nativeMethod, params] of mappings) {
        await f.call(method, { mode: 'user-stop', expectedTurnId: 'expected', requestId: 7, decision: 'decline', response: { scope: 'turn' } });
        assert.equal(f.calls.at(-1).method, nativeMethod);
        assert.deepEqual(f.calls.at(-1).params, params);
    }
});

test('keeps uncertain delivery uncertain and never repeats a failed operation', async () => {
    for (const rejected of [false, true]) {
        const error = Object.assign(new Error('connection failure'), rejected ? { delivery: { stage: 'not-sent' } } : {});
        const f = await fixture({}, { error });
        const result = await f.call('thread-follower-start-turn', { turnStart: { request: {} } });
        assert.equal(result.failed, true);
        assert.equal(result.rejected, rejected);
        assert.equal(f.calls.length, 1);
    }
});

test('rejects missing conversations and unsupported operations before dispatch', async () => {
    const f = await fixture({});
    assert.equal((await f.call('probe', { conversationId: 'other' })).available, false);
    assert.equal((await f.call('thread/delete')).rejected, true);
    assert.deepEqual(f.calls.map(row => row.method), ['thread/read']);
});


test('cold and metadata-only desktop sessions use native identity checks before preparing and sending once', async () => {
    for (const state of [null, {rolloutPath: null}]) {
        const f = await fixture(state, {nativeThread: {id:'session',path:'home/native-rollout',cwd:'C:/native-project'}});
        const probe = await f.call('probe');
        assert.equal(probe.available,true);
        assert.equal(probe.rolloutPath,'home/native-rollout');
        assert.deepEqual(f.calls.map(row=>row.method),['thread/read']);
        assert.equal(f.calls[0].params.includeTurns,false);
        assert.equal((await f.call('prepare')).ready,true);
        const resume=f.calls.find(row=>row.method==='resumeConversation');
        assert.equal(resume.params.preserveServerConfiguration,true);
        assert.deepEqual(Array.from(resume.params.workspaceRoots),['C:/native-project']);
        const snapshot=await f.call('snapshot');
        assert.equal(snapshot.turns[0].turnId,'persisted');
        const request={input:[{type:'text',text:'original'}],clientUserMessageId:'original-key'};
        await f.call('thread-follower-start-turn',{turnStart:{request}});
        const sends=f.calls.filter(row=>row.method==='turn/start');
        assert.equal(sends.length,1);
        assert.equal(sends[0].params.clientUserMessageId,'original-key');
    }
});

test('unreadable or mismatched native metadata never prepares or sends a cold session', async () => {
    for (const options of [{readError:new Error('not found')},{nativeThread:{id:'another-session',path:'home/other'}},{nativeThread:{id:'session',path:''}}]) {
        const f=await fixture(null,options);
        assert.equal((await f.call('probe')).available,false);
        assert.equal((await f.call('prepare')).available,false);
        assert.equal((await f.call('thread-follower-start-turn',{turnStart:{request:{}}})).available,false);
        assert.ok(f.calls.every(row=>row.method==='thread/read'));
    }
});


test('steering an evicted or stale desktop cache uses the authoritative active turn and sends only once', async () => {
    const states=[{turns:[]},{turns:[{turnId:'previous',status:'completed'}]},
        {turns:[{turnId:'stale-active',status:'inProgress'}]}];
    for (const state of states) {
        const f=await fixture(state,{nativeTurns:[{id:'native-current',status:'inProgress',items:[]}]});
        const input=[{type:'text',text:'original steering input'}];
        await f.call('thread-follower-steer-turn',{input,clientUserMessageId:'steer-original-key'});
        assert.equal(f.calls[0].method,'thread/turns/list');
        const writes=f.calls.filter(row=>row.method==='turn/steer');
        assert.equal(writes.length,1);
        assert.equal(writes[0].params.expectedTurnId,'native-current');
        assert.equal(writes[0].params.clientUserMessageId,'steer-original-key');
        assert.deepEqual(writes[0].params.input,input);
    }
});

test('native read gaps and an active thread without a listed turn remain unsent and never claim completion', async () => {
    for (const options of [{turnsError:new Error('read disconnected')},
        {nativeTurns:[],nativeThread:{id:'session',status:{type:'active'}}},
        {nativeTurns:[],nativeThread:{id:'session',status:{type:'notLoaded'}}}]) {
        const f=await fixture({turns:[{turnId:'old-cache',status:'inProgress'}]},options);
        const result=await f.call('thread-follower-steer-turn',{input:[],clientUserMessageId:'key'});
        assert.equal(result.rejected,true);
        assert.doesNotMatch(result.message,/任务已结束/);
        assert.ok(f.calls.every(row=>row.method!=='turn/steer'));
    }
});

test('a native steer failure after the read is never retried or converted to a new turn', async () => {
    const f=await fixture({turns:[]},{nativeTurns:[{id:'active',status:'inProgress'}],error:new Error('lost acknowledgement')});
    const result=await f.call('thread-follower-steer-turn',{input:[],clientUserMessageId:'key'});
    assert.equal(result.failed,true);
    assert.equal(result.rejected,false);
    assert.deepEqual(f.calls.map(row=>row.method),['thread/turns/list','turn/steer']);
});
