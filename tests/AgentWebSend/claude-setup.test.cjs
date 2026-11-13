const assert=require('node:assert/strict');
const {test}=require('node:test');
const {createHarness}=require('./agent-send.test.cjs');

test('Claude configuration posts once and resumes progress via status reads',async()=>{
    const h=createHarness([],'claude'),s=h.state,timers=[];
    let refreshes=0;s.refreshSessions=async()=>{refreshes++;};
    h.context.clearTimeout=()=>{};h.context.setTimeout=callback=>{timers.push(callback);return timers.length;};
    let finish,posts=0,gets=0;
    h.api.post=async route=>{assert.equal(route,'/api/agent-setup/claude/configure');posts++;await new Promise(resolve=>finish=resolve);return {started:true,message:'submitted'};};
    h.api.get=async route=>{assert.match(route,/agent-setup\/claude/);gets++;return {state:'installing',canConfigureClaude:true,install:{busy:true,message:'正在安装 SDK'}};};
    const request=s.configureClaude();await s.configureClaude();
    assert.equal(posts,1);assert.equal(s.setupBusy,true);
    finish();await request;
    assert.equal(s.setupNotice,'正在安装 SDK');assert.equal(timers.length,1);assert.equal(gets,1);
    await s.configureClaude();assert.equal(posts,1);
    h.api.get=async()=>({state:'sign_in',canConfigureClaude:true,install:{busy:false,phase:'ready',message:'配置完成，请登录'}});
    await timers.shift()();assert.equal(s.agentSetup.state,'sign_in');assert.equal(s.setupNotice,'配置完成，请登录');assert.equal(timers.length,0);assert.equal(refreshes,1);
});

test('a configuration that finishes before the first status read still refreshes the workspace',async()=>{
    const h=createHarness([],'claude'),s=h.state;let refreshes=0;
    h.context.clearTimeout=()=>{};s.refreshSessions=async()=>{refreshes++;};
    h.api.post=async()=>({started:true});
    h.api.get=async()=>({state:'ready',install:{phase:'ready',busy:false}});
    await s.configureClaude();assert.equal(refreshes,1);assert.equal(s.setupRefreshPending,false);
});

test('an older status response cannot replace newer configuration progress',async()=>{
    const h=createHarness([],'claude'),s=h.state;let finish,reads=0;
    h.context.clearTimeout=()=>{};
    h.api.get=async()=>++reads===1?await new Promise(resolve=>{finish=resolve;}):{state:'ready',install:{phase:'ready',busy:false}};
    const older=s.loadAgentSetup();await s.loadAgentSetup();finish({state:'installing',install:{busy:true}});await older;
    assert.equal(s.agentSetup.state,'ready');
});

test('an uncertain configuration submission is reconciled through GET and never automatically posted again',async()=>{
    const h=createHarness([],'claude'),s=h.state,timers=[];
    h.context.clearTimeout=()=>{};h.context.setTimeout=callback=>{timers.push(callback);return timers.length;};
    let posts=0;
    h.api.post=async()=>{posts++;throw new Error('connection interrupted');};
    h.api.get=async()=>({state:'installing',install:{busy:true,message:'服务端任务正在执行'}});
    await s.configureClaude();assert.equal(posts,1);assert.equal(s.agentSetup.install.busy,true);assert.equal(timers.length,1);
    h.api.get=async()=>{throw new Error('temporarily offline');};
    await timers.shift()();assert.equal(timers.length,1);assert.match(s.setupNotice,/重新连接/);assert.equal(posts,1);
});
