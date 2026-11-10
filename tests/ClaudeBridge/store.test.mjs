import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {ClaudeStore, officialEnvironment} from '../../WebApplication/Tools/ClaudeBridge/core.mjs';
const wait = async predicate => {for(let i=0;i<100;i++){if(predicate())return;await new Promise(r=>setTimeout(r,5));}throw new Error('Timed out');};
function fixture(t, query, auth=async()=>({ready:true})) {
    const root=fs.mkdtempSync(path.join(os.tmpdir(),'claude-contract-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
    const sdk={query: input => {const result=query(input);if(result)result.close=()=>{};return result;},listSessions:async()=>[{sessionId:'native-external',summary:'External',lastModified:1}],getSessionMessages:async()=>[]};
    const store=new ClaudeStore(root,sdk,'/official/claude',auth);
    const session=store.create({cwd:root});
    return {root,store,session,sdk};
}
test('a single accepted run survives a disconnected caller and duplicate receipt lookup',async t=>{
    let release,invocations=0;const held=new Promise(r=>release=r);
    const {store,session}=fixture(t,({options})=>{invocations++;assert.equal(options.permissionMode,'default');assert.deepEqual(options.settingSources,['user','project','local']);return(async function*(){await held;yield{type:'assistant',session_id:session.id,uuid:'answer',message:{content:[{type:'text',text:'Done'}]}};yield{type:'result',session_id:session.id,is_error:false,usage:{input_tokens:10},total_cost_usd:0.01};})();});
    const body={session_id:session.id,input:'Task'},key='unique_submission_123';
    const run=await store.start(body,key);assert.equal(run.status,'started');
    assert.equal((await store.start(body,key)).id,run.id);assert.equal(invocations,1);
    await assert.rejects(store.start({...body,input:'different'},key),/另一条消息/);
    await assert.rejects(store.start(body,'second_submission_123'),/已有任务/);
    release();await wait(()=>run.status==='completed');
    const replay=store.events(run.id);assert.equal(replay.data[0].type,'user');assert.equal(replay.data.at(-1).type,'result');
    assert.equal(store.events(run.id,replay.cursor).data.length,0);
    assert.equal((await store.start(body,key)).id,run.id);assert.equal(invocations,1);
});
test('approval is exact-request scoped, denied without expanding native permissions',async t=>{
    let decisions;
    const {store,session}=fixture(t,({options})=>(async function*(){decisions=await options.canUseTool('Bash',{command:'echo test'},{signal:options.abortController.signal});yield{type:'result',session_id:session.id,is_error:false};})());
    const run=await store.start({session_id:session.id,input:'Task'},'approve_submission_123');
    await wait(()=>run.request);
    assert.throws(()=>store.reply(run.id,{request_id:'stale',allow:true}),/过期/);
    assert.throws(()=>store.reply(run.id,{request_id:run.request.id}),/明确/);
    const request=run.request.id;store.reply(run.id,{request_id:request,allow:false});await wait(()=>run.status==='completed');
    assert.deepEqual(decisions,{behavior:'deny',message:'用户拒绝了本次请求'});
    assert.throws(()=>store.reply(run.id,{request_id:request,allow:true}),/过期/);
});
test('parallel permission requests queue and AskUserQuestion preserves supplied questions',async t=>{
    let decisions;
    const {store,session}=fixture(t,({options})=>(async function*(){decisions=await Promise.all([options.canUseTool('Bash',{command:'one'},{signal:options.abortController.signal}),options.canUseTool('AskUserQuestion',{questions:[{question:'Which?',options:[{label:'A'}]}]},{signal:options.abortController.signal})]);yield{type:'result',session_id:session.id,is_error:false};})());
    const run=await store.start({session_id:session.id,input:'Task'},'questions_submission_123');await wait(()=>run.request);
    store.reply(run.id,{request_id:run.request.id,allow:true});await wait(()=>run.request?.tool==='AskUserQuestion');
    assert.throws(()=>store.reply(run.id,{request_id:run.request.id,allow:true,answers:{}}),/所有问题/);
    store.reply(run.id,{request_id:run.request.id,allow:true,answers:{'Which?':'A',injected:'ignored'}});await wait(()=>run.status==='completed');
    assert.deepEqual(decisions[1].updatedInput.answers,{'Which?':'A'});assert.equal(decisions[0].updatedPermissions,undefined);
});
test('stop releases a pending permission and restart never replays an unknown run',async t=>{
    const {store,session,root,sdk}=fixture(t,({options})=>(async function*(){await options.canUseTool('Bash',{}, {signal:options.abortController.signal});throw new Error('secret-token-from-sdk');})());
    const run=await store.start({session_id:session.id,input:'Task'},'cancel_submission_123');await wait(()=>run.request);store.stop(run.id);await wait(()=>run.status==='cancelled');assert.equal(run.request,null);assert.ok(!JSON.stringify(run).includes('secret-token'));
    run.status='started';store.save();const recovered=new ClaudeStore(root,sdk,'/official/claude',async()=>({ready:true}));
    assert.equal(recovered.run(run.id).status,'acceptance_unknown');
    assert.equal((await recovered.start({session_id:session.id,input:'Task'},'cancel_submission_123')).id,run.id);
    await assert.rejects(recovered.start({session_id:session.id,input:'again'},'fresh_submission_123'),/结果待核对/);
});
test('external transcripts stay read-only; auth and path failures send no prompt',async t=>{
    let calls=0;const {store,session}=fixture(t,()=>{calls++;},async()=>({ready:false,message:'Login required'}));
    assert.equal((await store.sessions()).data.find(x=>x.id==='native-external').managed,false);
    await assert.rejects(store.start({session_id:'native-external',input:'Task'},'external_submission_123'),/仅供查看/);
    await assert.rejects(store.start({session_id:session.id,input:'Task'},'login_submission_123'),/Login required/);
    assert.throws(()=>store.create({cwd:'relative-path'}),/绝对项目路径/);
    assert.throws(()=>store.create({cwd:session.cwd,permission_mode:'bypassPermissions'}),/权限模式/);
    assert.equal(calls,0);
});
test('concurrent auth preflights cannot accept two runs or silently reuse a mismatched key',async t=>{
    let release;const held=new Promise(r=>release=r);let invoked=0;
    const {store,session}=fixture(t,()=>(async function*(){invoked++;yield{type:'result',session_id:session.id,is_error:false};})(),async()=>{await held;return{ready:true};});
    const body={session_id:session.id,input:'Task'},key='concurrent_submission_123';
    const a=store.start(body,key),b=store.start({...body,input:'other'},key);release();
    await a;await assert.rejects(b,/另一条消息/);assert.equal(invoked,1);
});
test('a corrupt journal fails closed instead of losing accepted receipts',t=>{
    const {root,sdk}=fixture(t,()=>{});fs.writeFileSync(path.join(root,'state.json'),'invalid');
    assert.throws(()=>new ClaudeStore(root,sdk,'/official/claude',async()=>({ready:true})));
});

test('failed receipt storage never starts a model and preserves an unknown receipt in memory',async t=>{
    let calls=0;const {store,session}=fixture(t,()=>{calls++;});
    store.save=()=>{throw new Error('Disk unavailable');};
    await assert.rejects(store.start({session_id:session.id,input:'Task'},'disk_failure_submission_123'),/storage failed/);
    const receipt=store.state.receipts.disk_failure_submission_123;
    assert.equal(store.run(receipt.run).status,'acceptance_unknown');assert.equal(calls,0);
    assert.throws(()=>store.session('__proto__'),/仅供查看/);assert.throws(()=>store.run('constructor'),/不存在/);
});

test('official provider routing rejects custom gateways and cloud backends without changing their settings',()=>{
    assert.equal(officialEnvironment({}),true);
    assert.equal(officialEnvironment({ANTHROPIC_BASE_URL:'https://api.anthropic.com/'}),true);
    assert.equal(officialEnvironment({ANTHROPIC_BASE_URL:'https://api.anthropic.com.attacker.invalid'}),false);
    assert.equal(officialEnvironment({ANTHROPIC_BASE_URL:'http://127.0.0.1:9999'}),false);
    assert.equal(officialEnvironment({CLAUDE_CODE_USE_BEDROCK:'1'}),false);
});
