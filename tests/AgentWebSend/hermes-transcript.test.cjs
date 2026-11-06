const assert=require('node:assert/strict');
const {test}=require('node:test');
const {createHarness}=require('./agent-send.test.cjs');
const user=(id,text,timestamp)=>({id,role:'user',content:text,timestamp});
const assistant=(id,text,timestamp)=>({id,role:'assistant',content:text,timestamp});
function fixture(){const h=createHarness([],'hermes');Object.defineProperty(h.state,'visibleLiveSegments',{get:()=>h.options.computed.visibleLiveSegments.call(h.state)});return h;}
function emit(h,seq,event,text,time=1793960846){h.state.onRunEvent({seq,event,timestamp:time,...text});}

test('Hermes SSE chunks, interim and native history converge without orphan reasoning tags or duplicate bubbles',async()=>{
 const h=fixture(),s=h.state;
 s.messages=[user(84840,'修复会话残留',1793960846.005)];s.streamHistoryIds=['84840'];
 emit(h,1,'message.delta',{delta:'</thi'});assert.equal(s.visibleLiveSegments.length,0);
 emit(h,2,'message.delta',{delta:'nk>\n收到，两件事都做。'});assert.equal(s.visibleLiveSegments[0].text,'收到，两件事都做。');
 emit(h,3,'message.interim',{text:'收到，两件事都做。',already_streamed:true});
 assert.equal(s.liveSegments.length,1);assert.equal(s.visibleLiveSegments.length,1);
 h.setHistory([user(84840,'修复会话残留',1793960846.005),assistant(84841,'收到，两件事都做。',1793960846)]);
 await s.loadHistory();assert.equal(s.visibleLiveSegments.length,0);
 assert.deepEqual(Array.from(h.options.computed.timeline.call(s),x=>x.message.id),[84840,84841]);
 emit(h,3,'message.interim',{text:'收到，两件事都做。',already_streamed:true});assert.equal(s.liveSegments.length,1,'replayed seq is ignored');
 emit(h,4,'tool.started',{tool:'terminal'});
 emit(h,5,'message.delta',{delta:'</think>\n下一段还未落盘'},1793960870);
 assert.equal(s.liveSegments.length,2);assert.equal(s.visibleLiveSegments[0].text,'下一段还未落盘');
 await s.loadHistory();assert.equal(s.visibleLiveSegments[0].text,'下一段还未落盘','lagging history must not discard unsaved output');
});

test('same assistant text in distinct Hermes segments is preserved and native acknowledgements are consumed once',()=>{
 const h=fixture(),s=h.state;
 emit(h,1,'message.delta',{delta:'开始检查文件'});emit(h,2,'message.interim',{text:'开始检查文件',already_streamed:true});
 emit(h,3,'tool.started',{tool:'terminal'});emit(h,4,'message.delta',{delta:'开始检查文件'},1793960880);
 assert.equal(s.visibleLiveSegments.length,2);
 s.messages=[assistant(1,'开始检查文件',1793960846)];assert.equal(s.visibleLiveSegments.length,1,'one persisted row acknowledges only one segment');
 s.messages.push(assistant(2,'开始检查文件',1793960880));assert.equal(s.visibleLiveSegments.length,0);
});

test('leading reasoning envelopes are presentation-only; user quotes, code and Codex content stay literal',()=>{
 const h=fixture(),s=h.state;
 for(const raw of ['</think>\n答复','<think>内部内容</think>答复','</think> </think>答复'])assert.equal(s.messageText(assistant(1,raw)), '答复');
 for(const raw of ['引用 </think> 标签','```xml\n</think>\n```'])assert.equal(s.messageText(assistant(1,raw)),raw);
 assert.equal(s.messageText(user(1,'</think>这个标签有问题')),'</think>这个标签有问题');
 s.agent='codex';assert.equal(s.messageText(assistant(1,'</think>literal')),'</think>literal');
});

test('complete Hermes lists remove expired cron placeholders but incomplete pages retain older conversations and drafts',async()=>{
 const h=fixture(),s=h.state;s.sessions=[{id:'session-1'},{id:'ghost',source:'cron'},{id:'older'}];s.draft='未发送';
 h.api.get=async()=>({data:[{id:'session-1'},{id:'new'}],has_more:true});await s.refreshSessionStatuses();
 assert.deepEqual(Array.from(s.sessions,x=>x.id),['new','session-1','older']);assert.equal(s.draft,'未发送');
 h.api.get=async()=>({data:[{id:'session-1'}],has_more:false});await s.refreshSessionStatuses();
 assert.deepEqual(Array.from(s.sessions,x=>x.id),['session-1']);assert.equal(s.selectedId,'session-1');assert.equal(s.draft,'未发送');
});

test('live Hermes narration stays before a later user interjection without reordering native rows',()=>{
 const h=fixture(),s=h.state;
 s.messages=[user(10,'第一问',1793960846.005),assistant(11,'原生回复',1793960846),user(12,'插话',1793960900)];
 s.liveSegments=[{id:'old',text:'插话前的旁白',createdAt:1793960870000,historyIds:['10','11','12']},{id:'new',text:'插话后的旁白',createdAt:1793960910000,historyIds:['10','11','12']}];
 const items=h.options.computed.timeline.call(s);
 assert.deepEqual(Array.from(items,x=>x.message?.id||x.segment.id),[10,11,'old',12,'new']);
 assert.deepEqual(Array.from(items.filter(x=>x.kind==='message'),x=>x.message.id),[10,11,12]);
});

test('native message_uid wins over identical text, source-time differences and changed row IDs',async()=>{
 const h=fixture(),s=h.state;
 s.messages=[{...assistant(1,'相同正文',1793960000),message_uid:'uid-previous'}];
 emit(h,1,'message.delta',{delta:'相同正文',message_uid:'uid-current'});
 assert.equal(s.visibleLiveSegments.length,1,'different UIDs must never merge by text');
 emit(h,2,'message.interim',{text:'相同正文',message_uid:'uid-current',already_streamed:true});
 assert.equal(s.liveSegments.length,1);
 s.messages.push({...assistant(2,'相同正文',1793900000),message_uid:'uid-current'});
 assert.equal(s.visibleLiveSegments.length,0,'UID acknowledges despite unrelated timestamp');
 assert.equal(s.messageKey(s.messages[1]),'uid-uid-current');
 s.messages[1].id=99;assert.equal(s.messageKey(s.messages[1]),'uid-uid-current','row rewrites retain UI identity');
});

test('interim-only UID upgrades legacy streaming and partial native history without dropping live suffix',async()=>{
 const h=fixture(),s=h.state;
 emit(h,1,'message.delta',{delta:'</think>完整回复'});
 emit(h,2,'message.interim',{text:'完整回复',message_uid:'uid-one',already_streamed:true});
 assert.equal(s.liveSegments.length,1);assert.equal(s.liveSegments[0].messageUid,'uid-one');
 s.messages=[{...assistant(1,'完整',1793960846),message_uid:'uid-one'}];
 assert.equal(s.visibleLiveSegments.length,0);assert.equal(s.displayMessages[0].content,'完整回复');
 assert.equal(s.messages[0].content,'完整','presentation must not mutate native history');
 emit(h,3,'message.interim',{text:'完整回复',message_uid:'uid-one',already_streamed:true});
 assert.equal(s.liveSegments.length,1,'UID makes repeated interim delivery idempotent');
});

test('Hermes history paging reconciles stable UIDs across rewritten row IDs',async()=>{
 const h=fixture(),s=h.state;
 s.messages=[{...user(1,'较早的问题',1),message_uid:'older'},{...assistant(2,'原回复',2),message_uid:'reply'}];
 h.setHistory([{...assistant(102,'更新后的回复',2),message_uid:'reply'},{...user(103,'下一问',3),message_uid:'next'}]);
 await s.loadHistory();
 assert.deepEqual(Array.from(s.messages,x=>x.id),[1,102,103]);
 s.$refs={};s.$nextTick=async()=>{};
 h.api.get=async()=>({data:[{...user(99,'更早的问题',0),message_uid:'first'},{...user(101,'较早的问题',1),message_uid:'older'}],has_more:false,oldest_id:99});
 await s.loadOlder();
 assert.deepEqual(Array.from(s.messages,x=>x.id),[99,1,102,103]);
 assert.equal(s.oldestId,99,'native numeric cursor remains unchanged');
});
