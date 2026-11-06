const assert=require('node:assert/strict');
const {test}=require('node:test');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {createHarness}=require('./agent-send.test.cjs');

function fixture(agent='hermes') {
 const h=createHarness([],agent),s=h.state,timers=new Map();let next=0;
 h.context.setTimeout=(callback,ms)=>{const id=++next;timers.set(id,{callback,ms});return id;};
 h.context.clearTimeout=id=>timers.delete(id);
 s.$refs={};s.$nextTick=callback=>callback?.();
 Object.defineProperty(s,'visibleLiveSegments',{get:()=>h.options.computed.visibleLiveSegments.call(s)});
 s.activeRun='r';s.watchEventStream=async()=>{};s.loadContext=async()=>{};
 s.ensureEventStream=h.options.methods.ensureEventStream.bind(s);
 s.ensureEventStream('r');
 return {...h,timers,tick:()=>{for(const [id,timer] of [...timers]){timers.delete(id);timer.callback();}}};
}
const reasoningText=s=>s.liveSegments.filter(row=>row.reasoning).map(row=>row.text).join('');

test('Hermes reasoning streams through the actual byte/SSE parser and consumer without markup parsing or message pollution',async()=>{
 const h=fixture(),s=h.state;
 const events=[
  {event:'reasoning.delta',run_id:'r',timestamp:1793960846,delta:'<thi'},
  {event:'reasoning.delta',run_id:'r',timestamp:1793960846,delta:'nk>先检查🙂\n'},
  {event:'future.event',run_id:'r',delta:'忽略'},
  {event:'reasoning.delta',run_id:'r',timestamp:1793960846,delta:'再确认</think>'}
 ];
 const bytes=Buffer.from(events.map(event=>`event: ${event.event}\r\ndata: ${JSON.stringify(event)}\r\n\r\n`).join(''));
 const body=new ReadableStream({start(controller){for(let i=0;i<bytes.length;i+=7)controller.enqueue(bytes.subarray(i,i+7));controller.close();}});
 const context={window:{},Headers,TextDecoder,localStorage:h.context.localStorage,
  fetch:async()=>({ok:true,body})};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../WebApplication/wwwroot/js/web-sse.js'),'utf8'),context);
 await context.window.RemoteToolSse({url:'/api/hermes/runs/r/events',onEvent:event=>s.onRunEvent(event)});
 assert.equal(reasoningText(s),'','token bursts do not render synchronously');
 assert.equal(h.timers.size,1);assert.equal([...h.timers.values()][0].ms,100);
 h.tick();assert.equal(reasoningText(s),'<think>先检查🙂\n再确认</think>','literal fragments are concatenated unchanged');
 assert.equal(s.visibleLiveSegments[0].text,'<think>先检查🙂\n再确认</think>','uses the existing narration timeline without tag cleanup');
 s.onRunEvent({event:'message.delta',run_id:'r',delta:'正常回答'});
 assert.equal(s.messages.length,0);assert.equal(s.liveSegments.length,2);assert.equal(s.liveSegments[1].text,'正常回答');
});

test('terminal events flush pending reasoning, reconnect retains it, and a new run clears it before any tokens arrive',()=>{
 const h=fixture(),s=h.state;
 s.onRunEvent({seq:1,event:'reasoning.delta',run_id:'r',delta:'一'});
 s.onRunEvent({seq:1,event:'reasoning.delta',run_id:'r',delta:'重复'});
 s.onRunEvent({seq:2,event:'run.completed',run_id:'r'});
 assert.equal(reasoningText(s),'一');assert.equal(s.reasoningEnded,true);assert.equal(h.timers.size,0);
 s.onRunEvent({seq:3,event:'reasoning.delta',run_id:'r',delta:'迟到'});h.tick();assert.equal(reasoningText(s),'一');
 s.stopEventStream();s.ensureEventStream('r');assert.equal(reasoningText(s),'一');
 s.activeRun='next';s.ensureEventStream('next');
 assert.equal(reasoningText(s),'');assert.equal(s.reasoningEnded,false);
 s.onRunEvent({event:'reasoning.delta',run_id:'r',delta:'旧轮迟到'});
 s.onRunEvent({event:'reasoning.delta',run_id:'next',delta:'新轮'});h.tick();assert.equal(reasoningText(s),'新轮');
 s.onRunEvent({event:'reasoning.delta',run_id:'next',delta:'未刷新的旧会话内容'});
 s.resetReasoning();h.tick();assert.equal(reasoningText(s),'');assert.equal(h.timers.size,0);
});

test('reasoning tolerates malformed deltas and remains ignored for Codex; status polling also finalizes it',async()=>{
 const h=fixture(),s=h.state;
 for(const delta of [null,undefined,{},7,''])s.onRunEvent({event:'reasoning.delta',run_id:'r',delta});
 assert.equal(h.timers.size,0);
 s.onRunEvent({event:'reasoning.delta',run_id:'r',delta:'轮询结束也保留'});
 h.api.get=async()=>({available:true,running:false});
 await h.options.methods.loadStatus.call(s,s.epoch,{run_id:'r'},{status:'failed'});
 assert.equal(reasoningText(s),'轮询结束也保留');assert.equal(s.reasoningEnded,true);assert.equal(s.activeRun,null);
 const codex=fixture('codex');codex.state.onRunEvent({event:'reasoning.delta',run_id:'r',delta:'忽略'});codex.tick();
 assert.equal(reasoningText(codex.state),'');assert.equal(codex.state.liveSegments.length,0);
});

test('reasoning follows existing narration bubbles across tools and interjections while history keeps native order',()=>{
 const h=fixture(),s=h.state;
 const emit=(event,delta,time)=>s.onRunEvent({event,run_id:'r',delta,timestamp:time});
 s.messages=[{id:1,role:'user',content:'请求',timestamp:1000.005}];
 emit('reasoning.delta','第一段思考',1000);emit('tool.started',undefined,1001);
 emit('reasoning.delta','第二段思考',1002);emit('message.delta','最终回复',1003);
 s.messages.push({id:2,role:'assistant',content:'最终回复',timestamp:1003});
 s.messages.push({id:3,role:'user',content:'插话',timestamp:1004});
 emit('reasoning.delta','插话后的思考',1005);h.tick();
 const items=h.options.computed.timeline.call(s);
 assert.deepEqual(Array.from(items,item=>item.message?.id||item.segment.text),[1,'第一段思考','第二段思考',2,3,'插话后的思考']);
 assert.equal(reasoningText(s),'第一段思考第二段思考插话后的思考');
 assert.equal(s.visibleLiveSegments.filter(row=>row.reasoning).length,3,'token fragments update a bubble; tool boundaries start a new narration');
});

test('reconnecting an active run keeps its growing narration and sequence cursor',()=>{
 const h=fixture(),s=h.state;
 s.onRunEvent({seq:1,event:'reasoning.delta',run_id:'r',delta:'前半段'});h.tick();
 s.stopEventStream();s.ensureEventStream('r');
 assert.equal(s.streamSeq,1);assert.equal(reasoningText(s),'前半段');
 s.onRunEvent({seq:1,event:'reasoning.delta',run_id:'r',delta:'重放'});
 s.onRunEvent({seq:2,event:'reasoning.delta',run_id:'r',delta:'后半段'});h.tick();
 assert.equal(reasoningText(s),'前半段后半段');assert.equal(s.liveSegments.filter(row=>row.reasoning).length,1);
});
