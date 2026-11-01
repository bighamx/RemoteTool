const assert=require('node:assert/strict');
const {test}=require('node:test');
const {createHarness}=require('./agent-send.test.cjs');

test('background refresh discovers sessions and desktop renames without changing the active conversation',async()=>{
    const {state,api}=createHarness([]);
    const row={id:'session-1',title:'old name',status:'idle'};
    state.sessions=[row,{id:'older',title:'loaded older page'}];state.selected={...row};state.selectedStatusKnown=true;
    state.draft='unsent draft';state.activeRun='run';
    api.get=async()=>({data:[{id:'new',title:'desktop session'},{id:'session-1',title:'renamed',status:'active'}]});
    await state.refreshSessionStatuses();
    assert.equal(state.sessions[0].id,'new');assert.equal(state.sessions[1],row);assert.equal(row.title,'renamed');assert.equal(row.status,'idle');
    assert.equal(state.sessions[2].id,'older');assert.equal(state.selected.title,'renamed');assert.equal(state.selectedId,'session-1');assert.equal(state.draft,'unsent draft');assert.equal(state.activeRun,'run');
    await state.refreshSessionStatuses();assert.equal(state.sessions.filter(row=>row.id==='new').length,1);
});

test('a linked session beyond the first page is fetched explicitly rather than opening an unrelated session',async()=>{
    const {state,api,context}=createHarness([]);state.selectedId='';context.location.search='?session=older';
    const routes=[];api.get=async route=>{routes.push(route);return route.endsWith('/sessions')?{data:[{id:'unrelated'}],has_more:true,next_cursor:'next'}:{session:{id:'older',title:'target'}};};
    state.select=async row=>{state.selectedId=row.id;state.selected=row;};
    await state.refreshSessions();
    assert.equal(state.selectedId,'older');assert.equal(state.sessions[0].id,'older');assert.equal(state.hasMore,true);
    assert.ok(routes.includes('/api/codex/sessions/older'));
});

test('an unreadable linked session stays explicit and never silently switches to another session',async()=>{
    const {state,api,context}=createHarness([]);state.selectedId='';context.location.search='?session=missing';
    api.get=async route=>{if(route.endsWith('/sessions'))return {data:[{id:'unrelated'}]};throw Object.assign(new Error('会话不存在'),{status:404});};
    state.select=async()=>{throw new Error('must not select an unrelated session');};
    await state.refreshSessions();assert.equal(state.selectedId,'');assert.equal(state.listError,'会话不存在');assert.equal(state.loadingList,false);
});

test('changing an already-mounted workspace URL opens the requested session',async()=>{
    const {state,api,context}=createHarness([]);context.window.RemoteToolWorkspace={};context.location.hash='#/codex?session=target';
    api.get=async()=>({session:{id:'target',title:'target'}});state.refreshSessionStatuses=async()=>{};
    state.select=async row=>{state.selectedId=row.id;};state.search='stale filter';
    await state.onWorkspaceRouteChange();assert.equal(state.selectedId,'target');assert.equal(state.sessions[0].id,'target');assert.equal(state.search,'');
});

test('a deleted remembered Hermes session is cleared while the list remains usable',async()=>{
    const {state,api,context}=createHarness([],'hermes');state.selectedId='';
    const saved='remotetool:web:selected:hermes';context.localStorage.setItem(saved,'missing');
    api.get=async route=>{if(route.endsWith('/sessions'))return {data:[{id:'available'}]};throw Object.assign(new Error('deleted'),{status:404});};
    state.select=async row=>{state.selectedId=row.id;};
    await state.refreshSessions();assert.equal(state.listError,'');assert.equal(state.selectedId,'available');
    assert.equal(context.localStorage.getItem(saved),null);
});

test('an older linked-session response cannot replace a newer manual selection',async()=>{
    const {state,api,context}=createHarness([]);context.window.RemoteToolWorkspace={};context.location.hash='#/codex?session=target';
    let finish;api.get=()=>new Promise(resolve=>finish=resolve);state.refreshSessionStatuses=async()=>{};
    state.select=async()=>{throw new Error('stale link must not select');};
    const pending=state.onWorkspaceRouteChange();++state.epoch;state.selectedId='manual';finish({session:{id:'target'}});await pending;
    assert.equal(state.selectedId,'manual');assert.equal(state.sessions.length,0);
});

test('session ID remains searchable when the desktop name differs from the original title',()=>{
    const {state,options}=createHarness([]);state.sessions=[{id:'wanted-id',title:'优化web'},{id:'other'}];state.search='wanted-id';
    assert.equal(options.computed.filteredSessions.call(state)[0].title,'优化web');
});
