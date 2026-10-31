const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture() {
    let config; const calls=[];
    const api={time:()=>'',id:encodeURIComponent,get:async()=>({mode:'default'}),post:async(path,body)=>{calls.push({path,body});return {mode:body.mode};}};
    const root={dataset:{agent:'codex'}};
    const context={window:{RemoteToolApi:api},document:{getElementById:id=>id==='agent-app'?root:null},location:{search:''},
        localStorage:{getItem:()=>null},URLSearchParams,ViewUIPlus:{},confirm:()=>true,
        Vue:{createApp:c=>{config=c;return {use(){},mount(){}};}}};
    vm.runInNewContext(fs.readFileSync('WebApplication/wwwroot/js/agent-workspace.js','utf8'),context);
    const state=config.data();state.selectedId='session-a';state.epoch=1;state.$Message={success(){}};
    for(const [key,fn] of Object.entries(config.methods))state[key]=fn.bind(state);
    for(const [key,fn] of Object.entries(config.computed))Object.defineProperty(state,key,{get:()=>fn.call(state)});
    return {state,api,calls,context};
}

test('approval scope, confirmation, duplicate taps and changing requests',async()=>{
    const {state,api,calls,context}=fixture();
    assert.deepEqual(Array.from(state.approvalChoices,x=>x.id),['once','deny']);
    state.activeRun='run-a';state.approval={request_id:'req-a',choices:[{id:'always',label:'保存规则',detail:'dotnet build'}]};
    context.confirm=()=>false;await state.approve('always');assert.equal(calls.length,0);
    context.confirm=()=>true;let finish;
    api.post=async(path,body)=>{calls.push({path,body});return new Promise(r=>finish=r);};
    const first=state.approve('always');await state.approve('always');assert.equal(calls.length,1);
    assert.equal(calls[0].body.request_id,'req-a');assert.equal(calls[0].body.choice,'always');
    state.approval={request_id:'req-b'};finish({accepted:true});await first;
    assert.equal(state.approval.request_id,'req-b');assert.equal(state.approvalBusy,false);
});

test('late permission reads cannot overwrite another session',async()=>{
    const {state,api}=fixture();let finish;
    api.get=async()=>new Promise(r=>finish=r);
    const old=state.loadPermissions();state.selectedId='session-b';state.epoch++;
    api.get=async()=>({mode:'workspace'});await state.loadPermissions();
    finish({mode:'full'});await old;
    assert.equal(state.permissionsMode,'workspace');assert.equal(state.permissionsLoading,false);
});

test('permission saves are bound to the selected session and invalid mode is ignored',async()=>{
    const {state,api,calls}=fixture();
    state.permissionsLoaded=true;await state.savePermissions();assert.equal(calls.length,0);
    state.permissionsChoice='full';let finish;
    api.post=async(path,body)=>{calls.push({path,body});return new Promise(r=>finish=r);};
    const saving=state.savePermissions();await state.savePermissions();assert.equal(calls.length,1);
    assert.equal(calls[0].path,'/api/codex/sessions/session-a/permissions');
    state.selectedId='session-b';state.epoch++;state.permissionsMode='workspace';
    finish({mode:'full'});await saving;assert.equal(state.permissionsMode,'workspace');
});

test('Markdown links require an explicit URL or Markdown link',()=>{
    const markdown=require('../WebApplication/wwwroot/vendor/markdown-it/markdown-it.min.js')({linkify:true});
    markdown.linkify.set({fuzzyLink:false,fuzzyEmail:false,fuzzyIP:false});
    assert.equal(markdown.render('另一个项目 agents.md / example.com / user@example.com').includes('<a '),false);
    assert.equal((markdown.render('[规则](https://example.com/rules) https://example.com/docs').match(/<a /g)||[]).length,2);
});
