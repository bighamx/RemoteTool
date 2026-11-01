const assert=require('node:assert/strict');
const {test}=require('node:test');
const {createHarness}=require('./agent-send.test.cjs');

function mobile(){
 const h=createHarness([]);h.state.mobileViewport=true;h.state.sessionsOpen=true;h.state.selectedId='';
 h.context.window.RemoteToolWorkspace={};h.context.location.hash='#/codex';h.context.location.pathname='/';
 const navigation=[];h.context.history={state:null,pushState(state,_,url){this.state=state;navigation.push(['push',url]);},replaceState(state,_,url){this.state=state;navigation.push(['replace',url]);},back(){navigation.push(['back']);}};
 h.state.$nextTick=async callback=>callback?.();h.state.scrollToLatest=()=>{};h.state.refreshAll=async()=>{};h.state.refreshSessionStatuses=async()=>{};
 return {...h,navigation};
}

test('entering Codex on mobile opens the list instead of loading an arbitrary conversation',async()=>{
 const {state,api}=mobile();api.get=async()=>({data:[{id:'first',title:'first'}]});
 state.select=async()=>{throw new Error('mobile list must not auto-open history');};
 await state.refreshSessions();assert.equal(state.sessions.length,1);assert.equal(state.selectedId,'');assert.equal(state.sessionsOpen,true);
});

test('selecting from the mobile list creates a back target; list navigation preserves the draft',async()=>{
 const {state,navigation,context}=mobile();await state.select({id:'target',title:'target'});
 assert.equal(state.sessionsOpen,false);assert.equal(navigation[0][0],'push');assert.match(navigation[0][1],/session=target/);
 state.draft='keep draft';context.history.state={remoteToolSession:'target'};state.openSessionList();
 assert.equal(navigation.at(-1)[0],'back');assert.equal(state.sessionsOpen,true);assert.equal(state.draft,'keep draft');
});

test('a direct mobile conversation URL can return to the list without leaving the app',()=>{
 const {state,navigation,context}=mobile();state.selectedId='target';state.sessionsOpen=false;context.location.hash='#/codex?session=target';
 state.openSessionList();assert.deepEqual(navigation.at(-1),['replace','/#/codex']);assert.equal(state.sessionsOpen,true);
});

test('browser back shows the mobile list and re-entering the same session restores chat',async()=>{
 const {state,context}=mobile();state.selectedId='target';state.sessionsOpen=false;
 await state.onWorkspaceRouteChange();assert.equal(state.sessionsOpen,true);
 context.location.hash='#/codex?session=target';await state.onWorkspaceRouteChange();assert.equal(state.sessionsOpen,false);
});

test('mobile Enter inserts a newline, desktop Enter sends, and IME composition never sends',()=>{
 const {state}=mobile();let sends=0,prevented=0;state.send=()=>sends++;
 const event={key:'Enter',preventDefault(){prevented++;}};
 state.composerKeydown(event);assert.equal(sends,0);assert.equal(prevented,0);
 state.composerKeydown({...event,ctrlKey:true});assert.equal(sends,1);
 state.mobileViewport=false;state.composerKeydown(event);assert.equal(sends,2);
 state.composerKeydown({...event,isComposing:true});state.composerKeydown({...event,keyCode:229});assert.equal(sends,2);
});


test('mobile context summary resolves session provider aliases and translates reasoning effort',()=>{
 const {state,options}=mobile();state.selected={modelProvider:'company_login',model:'gpt-example',reasoning_effort:'medium'};
 state.models=[{slug:'openai',aliases:['company_login'],name:'OpenAI'}];
 const label=options.computed.mobileModelSummary.call(state);assert.equal(label,'OpenAI · gpt-example · 中思考');
});

test('mobile message clock accepts server seconds, milliseconds and ISO timestamps',()=>{
 const {state}=mobile();const ms=Date.parse('2026-11-01T00:47:00Z');
 assert.equal(state.messageClock(ms),state.messageClock(ms/1000));assert.equal(state.messageClock(ms),state.messageClock('2026-11-01T00:47:00Z'));
 assert.equal(state.messageClock(null),'');assert.equal(state.messageClock('invalid'),'');
});
