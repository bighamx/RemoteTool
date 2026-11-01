const assert=require('node:assert/strict');
const {test}=require('node:test');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {createHarness}=require('./AgentWebSend/agent-send.test.cjs');
const code=fs.readFileSync(path.join(__dirname,'../WebApplication/wwwroot/js/agent-history-cache.js'),'utf8');
function setup(storage=new Map(),username='alice',device='device'){
 const context={window:{RemoteToolApi:{get:async route=>route.includes('identity')?{id:device}:{authenticated:true,username}}},sessionStorage:{get length(){return storage.size;},key:i=>[...storage.keys()][i],getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)}};
 vm.runInNewContext(code,context);return {factory:context.window.RemoteToolHistoryCache,storage,context};
}
const row=(text,id='m1')=>({id,role:'assistant',content:text,timestamp:Date.now()});
const tick=()=>new Promise(resolve=>setImmediate(resolve));

test('history survives a tab reload but stays isolated by device, user, agent and profile',async()=>{
 const {factory,storage}=setup();const cache=factory.create('codex','default');await cache.write('session',{data:[row('canonical')],has_more:true});
 assert.equal((await setup(storage).factory.create('codex','default').read('session')).data[0].content,'canonical');
 for(const cache of [setup(storage,'bob').factory.create('codex','default'),setup(storage,'alice','other').factory.create('codex','default'),factory.create('hermes','default'),factory.create('codex','other')])assert.equal(await cache.read('session'),null);
});

test('corrupt, expired and oversized records fail closed without blocking live reads',async()=>{
 const {factory,storage}=setup();const cache=factory.create('codex','default');await cache.write('session',{data:[row('text')]});const key=[...storage.keys()][0];
 storage.set(key,'broken');assert.equal(await setup(storage).factory.create('codex','default').read('session'),null);
 storage.set(key,JSON.stringify({data:[null],at:Date.now()}));assert.equal(await setup(storage).factory.create('codex','default').read('session'),null);
 storage.set(key,JSON.stringify({data:[row('old')],at:Date.now()-31*60*1000}));assert.equal(await setup(storage).factory.create('codex','default').read('session'),null);
 await cache.write('session',{data:[row('x'.repeat(300000))]});assert.equal(cache.peek('session'),null);
});

test('cache stores only a bounded canonical suffix and a successful empty history clears old messages',async()=>{
 const {factory,storage}=setup();const cache=factory.create('codex','default');await cache.write('session',{data:Array.from({length:100},(_,i)=>row('text',String(i)))});
 assert.equal(cache.peek('session').data.length,80);assert.equal(cache.peek('session').oldest_id,'20');assert.equal(cache.peek('session').has_more,true);
 for(let i=0;i<25;i++)await cache.write(String(i),{data:[row('short')]});assert.ok(storage.size<=20);
 await cache.write('session',{data:[]});assert.equal(cache.peek('session').data.length,0);
});

test('cache removal invalidates a write waiting on identity verification',async()=>{
 const h=setup();const pending=[];h.context.window.RemoteToolApi.get=()=>new Promise(resolve=>pending.push(resolve));
 const cache=h.factory.create('codex','default');const writing=cache.write('session',{data:[row('old')]});const removal=cache.remove('session');
 pending[0]({id:'device'});pending[1]({authenticated:true,username:'alice'});await Promise.all([writing,removal]);
 assert.equal(cache.peek('session'),null);assert.equal(h.storage.size,0);
});

function selecting(cache){
 const h=createHarness([],'codex',cache);h.context.window.RemoteToolWorkspace={};h.context.location.hash='#/codex';h.context.location.pathname='/';h.context.history={replaceState(){}};
 h.state.$nextTick=async cb=>cb?.();h.state.scrollToLatest=()=>{};return h;
}

test('cached messages paint before history and never reconcile a pending submission',async()=>{
 const cache={peek:()=>({data:[row('cached')],has_more:true}),read:async()=>null,write:async()=>{}};
 const h=selecting(cache);let finish;
 h.state.refreshAll=()=>new Promise(resolve=>finish=resolve);h.state.reconcilePending=()=>{throw new Error('cache cannot acknowledge a submission');};
 const loading=h.state.select({id:'session-1',title:'test'});
 assert.equal(h.state.messages[0].content,'cached');assert.equal(h.state.historyLoading,false);assert.equal(h.state.historyCacheState,'syncing');
 h.state.draft='do not dispatch';await h.state.send();assert.equal(h.posts.length,0);assert.equal(h.state.draft,'do not dispatch');
 finish();await loading;
});

test('slow cache reads cannot overwrite fresh server history or another selected session',async()=>{
 let finishCache;const cache={peek:()=>null,read:()=>new Promise(resolve=>finishCache=resolve),write:async()=>{}};
 const h=selecting(cache);h.setHistory([row('live')]);h.state.loadContext=async()=>{};h.state.loadSessionDetail=async()=>{};h.state.loadSchedules=async()=>{};
 await h.state.select({id:'session-1'});finishCache({data:[row('stale')]});await tick();assert.equal(h.state.messages[0].content,'live');
 h.state.refreshAll=async()=>{};await h.state.select({id:'session-2'});++h.state.epoch;h.state.selectedId='session-3';finishCache({data:[row('wrong session')]});await tick();assert.equal(h.state.messages.length,0);
});

test('failed history retains a labeled preview; the next authoritative empty response replaces it',async()=>{
 const {state,api}=createHarness([]);state.showCachedHistory({data:[row('old')]});api.get=async()=>{throw new Error('offline');};
 await state.loadHistory();assert.equal(state.messages[0].content,'old');assert.equal(state.historyCacheState,'failed');
 await state.loadHistory(state.epoch,{data:[]});assert.equal(state.messages.length,0);assert.equal(state.historyCacheState,'');
});
