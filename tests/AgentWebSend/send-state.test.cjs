const assert = require('node:assert/strict');
const test = require('node:test');
const { createHarness: make } = require('./agent-send.test.cjs');
const deferred = () => { let resolve, reject; const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject}; };

test('unknown receipts survive time passing and cannot match another request or attachment', () => {
    const h=make([]), key='remotetool:web:pending:codex:session-1';
    h.storage.set(key,JSON.stringify({key:'ours',input:'继续',delivery:'发送状态待核对',createdAt:Date.now()-86400000}));
    assert.equal(h.state.restorePending('session-1').key,'ours');
    h.state.pending={key:'ours',input:'继续',attachments:[{id:'ours-file'}],createdAt:Date.now()};
    h.state.messages=[{id:'other',role:'user',content:'继续',client_request_id:'another-device',timestamp:Date.now()}];
    h.state.reconcilePending();assert.equal(h.state.pending.key,'ours');
    h.state.messages=[{id:'without-key',role:'user',content:'继续',timestamp:Date.now()}];
    h.state.reconcilePending();assert.equal(h.state.pending.key,'ours');
    h.state.messages=[{id:'exact',role:'user',content:'继续',client_request_id:'ours'}];
    h.state.reconcilePending();assert.equal(h.state.pending,null);
});

test('overlapping enqueue clicks submit once and persist the receipt before the response',async()=>{
    const reply=deferred(),h=make([reply.promise]);h.state.draft='once';
    const first=h.state.enqueue();await h.state.enqueue();
    assert.equal(h.posts.length,1);assert.equal(h.state.pending.kind,'queue');
    assert.equal(JSON.parse(h.storage.get('remotetool:web:pending:codex:session-1')).key,h.bodies[0].key);
    reply.resolve({queuedSubmission:{id:'native-1'}});await first;
    assert.equal(h.state.pending,null);assert.equal(h.state.delivered.length,0);
});

test('uncertain enqueue cannot be retried with a new key and survives switching sessions',async()=>{
    const reply=deferred(),h=make([reply.promise]);h.state.draft='original';
    const first=h.state.enqueue();h.state.selectedId='session-2';h.state.pending=null;h.state.draft='new draft';
    reply.reject(new Error('lost response'));await first;
    assert.equal(h.state.draft,'new draft');
    const receipt=h.state.restorePending('session-1');assert.equal(receipt.kind,'queue');assert.equal(receipt.key,h.bodies[0].key);
    h.state.selectedId='session-1';h.state.pending=receipt;h.state.draft='original';
    await h.state.enqueue();assert.equal(h.posts.length,1);
});

test('confirmed queue lookup resolves receipt without duplicating a message or starting a completed run',async()=>{
    const h=make([]);h.state.pending={key:'q-key',kind:'queue',input:'once',createdAt:Date.now()};
    h.api.get=async()=>({found:true,run_id:'q-run',status:'completed',delivery:'accepted'});
    await h.state.verifyPending();assert.equal(h.state.pending,null);assert.equal(h.state.delivered.length,0);
});

test('multi-file upload keeps its originating session across a selection change',async()=>{
    const reply=deferred(),h=make([]),routes=[];
    h.api.request=async route=>{routes.push(route);return routes.length===1?reply.promise:{attachment:{id:'second'}};};
    const upload=h.state.uploadFiles({target:{files:['one','two']}});
    h.state.selectedId='session-2';h.state.attachments=[{id:'new-session-file'}];
    reply.resolve({attachment:{id:'first'}});await upload;
    assert.equal(routes.length,2);assert.ok(routes.every(route=>route.includes('/session-1/')));
    assert.equal(h.state.attachments[0].id,'new-session-file');
    assert.deepEqual(JSON.parse(h.storage.get('remotetool:web:draft-files:codex:session-1')).map(f=>f.id),['first','second']);
});

test('a repeated new assistant answer is not suppressed by an earlier turn',()=>{
    const h=make([],'hermes');
    h.state.messages=[{id:'old',native_item_id:'old-native',role:'assistant',content:'处理完成',timestamp:Date.now()}];
    h.state.liveSegments=[{id:'new-native',nativeItem:true,historyIds:['old'],text:'处理完成',createdAt:Date.now()}];
    assert.equal(h.options.computed.visibleLiveSegments.call(h.state).length,1);
    h.state.messages.push({id:'new',native_item_id:'new-native',role:'assistant',content:'处理完成',timestamp:Date.now()});
    assert.equal(h.options.computed.visibleLiveSegments.call(h.state).length,0);
});
