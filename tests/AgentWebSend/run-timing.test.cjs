const assert=require('node:assert/strict');
const {test}=require('node:test');
const {createHarness}=require('./agent-send.test.cjs');

const start=1794530000000;
function fixture(agent='hermes',saved=null) {
    const h=createHarness([],agent),s=h.state;
    let now=start;
    h.context.Date=class extends Date {static now(){return now;}};
    h.context.clearTimeout=()=>{};
    h.context.document.visibilityState='visible';
    if(saved)for(const [key,value] of saved)h.storage.set(key,value);
    s.watchEventStream=async()=>{};
    s.loadStatus=h.options.methods.loadStatus.bind(s);
    s.ensureEventStream=h.options.methods.ensureEventStream.bind(s);
    s.loadHistory=async()=>{};
    s.loadContext=async()=>{};
    s.loadSessionDetail=async()=>{};
    Object.defineProperty(s,'runTimers',{get:()=>h.options.computed.runTimers.call(s)});
    return {...h,at:value=>{now=value;},run:async(id='run-1',doc={created_at:start/1000})=>{
        await s.loadStatus(s.epoch,{run_id:id},{...doc,status:'running'});
    }};
}

for(const agent of ['codex','hermes','claude'])test(`${agent} run clocks follow actual status and SSE calls without counting polls or heartbeats`,async()=>{
    const h=fixture(agent),s=h.state;
    await h.run();
    h.at(start+65000);s.updateRunClock();
    assert.equal(s.runElapsed(s.runTimers[0].since),'01:05');
    assert.equal(s.runTimers[0].label,'等待响应');
    s.onRunEvent({seq:1,event:'tool.started',run_id:'run-1',item_id:'call-1',tool:'shell',timestamp:(start-60000)/1000});
    assert.equal(s.liveTools.length,1);
    assert.equal(s.runTimers[0].label,'上次响应');
    assert.equal(s.runTiming.lastResponseAt,start+65000,'measure local receipt, independent of server event time');
    h.at(start+80000);
    await h.run();
    s.onRunEvent({seq:2,event:'heartbeat',run_id:'run-1'});
    s.onRunEvent({seq:3,event:'future.event',run_id:'run-1'});
    s.updateRunClock();
    assert.equal(s.runElapsed(s.runTimers[0].since),'00:15');
    assert.equal(s.runElapsed(s.runTimers[1].since),'01:20');
    s.onRunEvent({seq:4,event:'tool.completed',run_id:'run-1',item_id:'call-1'});
    assert.equal(s.liveTools[0].status,'completed');
    s.updateRunClock();
    assert.equal(s.runElapsed(s.runTimers[0].since),'00:00');
});

test('reloaded and reconnected streams retain response time while new runs and sessions start independently',async()=>{
    const h=fixture();await h.run();h.at(start+10000);
    h.state.onRunEvent({seq:1,event:'message.delta',run_id:'run-1',delta:'hello'});
    h.state.saveRunTiming();
    const restored=fixture('hermes',h.storage);restored.at(start+70000);await restored.run();
    restored.state.onRunEvent({seq:1,event:'message.delta',run_id:'run-1',delta:'hello'});
    assert.equal(restored.state.runTiming.lastResponseAt,start+10000,'SSE replay is not a fresh response');
    restored.state.stopEventStream();restored.state.ensureEventStream('run-1');
    assert.equal(restored.state.runTiming.lastResponseAt,start+10000);
    await restored.run('run-2',{started_at:new Date(start+60000).toISOString()});
    assert.equal(restored.state.runTiming.lastResponseAt,null);
    assert.equal(restored.state.runTiming.startedAt,start+60000);
    restored.state.onRunEvent({event:'message.delta',run_id:'run-1',delta:'late response'});
    assert.equal(restored.state.runTiming.lastResponseAt,null);
    restored.state.selectedId='other-session';restored.state.runTiming={};
    await restored.run();
    assert.equal(restored.state.runTiming.lastResponseAt,null,'session timing cache is isolated');
});

test('unsequenced replays, hidden pages, unknown timestamps and terminal status keep timing honest',async()=>{
    const h=fixture(),s=h.state;await h.run('run-1',{});
    assert.equal(s.runElapsed(s.runTimers[1].since),'—');
    h.at(start+10000);
    const event={event:'reasoning.delta',run_id:'run-1',delta:'thinking'};
    s.onRunEvent(event);s.updateRunClock();
    h.at(start+20000);s.onRunEvent(event);
    assert.equal(s.runTiming.lastResponseAt,start+10000);
    h.context.document.visibilityState='hidden';s.updateRunClock();
    assert.equal(s.runNow,start+10000,'hidden pages stop visual clock updates');
    h.context.document.visibilityState='visible';s.updateRunClock();
    assert.equal(s.runElapsed(s.runTimers[0].since),'00:10');
    s.onRunEvent({event:'message.delta',run_id:'run-1',seq:'invalid',delta:'answer'});
    assert.equal(s.runTiming.lastResponseAt,start+20000);
    h.api.get=async()=>({available:true,running:false});
    await s.loadStatus(s.epoch,{run_id:'run-1'},{status:'completed'});
    assert.equal(s.activeRun,null);assert.equal(s.externalRunning,false);
    h.at(start+30000);s.updateRunClock();
    assert.equal(s.runNow,start+20000,'completed runs stop the clock');
    s.runNow=start+3661000;
    assert.equal(s.runElapsed(start),'1:01:01');
    assert.equal(s.runElapsed(start+4000000),'00:00');
});

test('external activity uses authoritative saved-response and phase times across polls',async()=>{
    const h=fixture(),s=h.state;
    const snapshot={available:true,running:true,activity_id:'turn-1',started_at:start,last_response_at:start+10000,progress:[]};
    await s.loadStatus(s.epoch,{run_id:null},null,snapshot);
    h.at(start+90000);s.updateRunClock();
    assert.equal(s.runTimers[0].label,'上次保存响应');
    assert.equal(s.runElapsed(s.runTimers[0].since),'01:20');
    assert.equal(s.runElapsed(s.runTimers[1].since),'01:30');
    await s.loadStatus(s.epoch,{run_id:null},null,{...snapshot});
    assert.equal(s.runElapsed(s.runTimers[0].since),'01:20');
    s.externalActivity={...snapshot,kind:'compact',phase_started_at:start+50000};
    assert.equal(s.runTimers[0].label,'等待压缩');
    assert.equal(s.runElapsed(s.runTimers[0].since),'00:40');
    s.externalActivity={...snapshot,activity_id:'turn-2',started_at:start+80000,last_response_at:null};
    assert.equal(s.runTimers[0].label,'等待响应');
    assert.equal(s.runElapsed(s.runTimers[1].since),'00:10');
});
