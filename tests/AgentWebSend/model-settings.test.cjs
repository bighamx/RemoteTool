const assert = require('node:assert/strict');
const {test} = require('node:test');
const {createHarness} = require('./agent-send.test.cjs');

function setup(agent='codex') {
    const h = createHarness([],agent);
    for (const name of ['selectedProviderMetadata','providerChoices','modelChoices','modelMetadata','modelDirty','effortChoices','tierChoices','activeProvider','officialProviderInUse','thirdPartyProviders'])
        Object.defineProperty(h.state,name,{get:()=>h.options.computed[name].call(h.state)});
    return h;
}

test('official aliases resolve their catalog without changing the session provider id', () => {
    const {state} = setup();
    state.models = [{slug:'openai',aliases:['openai','company_login'],official_provider:true,models:[{model:'official-model'}]},{slug:'third',models:['private-model']}];
    state.modelSelection = {provider:'company_login',model:'official-model'};
    assert.equal(state.providerChoices[0].id,'company_login');
    assert.equal(state.modelChoices[0].id,'official-model');
    state.modelSelection.provider = 'third';
    state.providerChanged();
    assert.equal(state.modelChoices[0].id,'private-model');
});

test('Hermes lock with disabled reasoning and resolved custom endpoint overrides legacy defaults', () => {
    const {state} = setup('hermes');
    const selection = state.readModelSelection({provider:'custom:actual',model:'stale',reasoning_effort:'high',model_config:JSON.stringify({browser_model_lock:{provider:'custom',model:'session-model',model_options:{reasoning:{enabled:false}}}})},{provider:'default',model:'default-model',reasoning_effort:'high',service_tier:'fast'});
    assert.equal(selection.provider,'custom:actual');
    assert.equal(selection.model,'session-model');
    assert.equal(selection.effort,'none');
    assert.equal(selection.tier,'');
    assert.equal(state.readModelSelection({model:'other'},{}).effort,'');
});

test('model catalog is scoped to the selected session and late responses cannot cross sessions', async () => {
    const {state,api} = setup();
    let resolveOld;
    const routes = [];
    api.get = route => {routes.push(route); return route.includes('session=session-1') ? new Promise(resolve=>resolveOld=resolve) : Promise.resolve({providers:[{slug:'third',models:['new-model']}],provider:'third',model:'global'});};
    state.selected = {id:'session-1',modelProvider:'old',model:'old-model'};
    const old = state.loadModels();
    ++state.epoch; state.selectedId='session-2';state.selected={id:'session-2',modelProvider:'third',model:'new-model'};
    await state.loadModels();
    resolveOld({providers:[],provider:'old',model:'old-default'});await old;
    assert.match(routes[0],/session=session-1/);
    assert.equal(state.modelSelection.model,'new-model');assert.equal(state.models[0].slug,'third');
});

test('third-party switching excludes official aliases and disabled rows without changing the management catalog', () => {
    const {state} = setup();
    state.providersState={endpoints:[{id:'openai'},{id:'company-login',official_provider:true},{id:'disabled',enabled:false},{id:'local',name:'OpenAI compatible',official_provider:false},{id:'legacy-local'}]};
    assert.deepEqual(Array.from(state.thirdPartyProviders,row=>row.id),['local','legacy-local']);
    assert.equal(state.providersState.endpoints.length,5);
});

test('saved official login is not shown as active when third-party routing is selected', () => {
    const {state} = setup();
    state.providersState={provider:'third',official_provider:false,endpoints:[{id:'third',name:'Private',auth_kind:'api_key'}]};
    state.workspaces={current:'official-login'};
    assert.equal(state.officialProviderInUse,false);
    assert.equal(state.providerAuthText(state.activeProvider),'已配置 API Key（尚未验证）');
});

test('schedule retry preserves destination, request key and payload after navigation or uncertain delivery', async () => {
    const {state,api} = setup();
    const bodies=[];
    state.draft='original schedule';state.openSchedule();
    state.selectedId='session-2';
    api.post=async (route,body)=>{bodies.push({...body});if(bodies.length===1)throw new Error('timeout');return {id:'one-schedule'};};
    state.loadSchedules=async()=>{};
    await state.createSchedule();
    state.scheduleText='edited after timeout';
    await state.createSchedule();
    assert.equal(bodies.length,2);
    assert.equal(bodies[0].sessionId,'session-1');
    assert.ok(bodies[0].clientKey);
    assert.deepEqual(bodies[0],bodies[1]);
});


test('model picker retains a custom model absent from the refreshed catalog', () => {
    const {state} = setup();
    state.models=[{slug:'third',models:[{model:'listed',displayName:'Listed model'}]}];
    state.selected={provider:'third',model:'custom/model',reasoning_effort:'high'};
    state.resetModelSelection();
    assert.equal(state.modelDirty,false);
    assert.equal(state.modelChoices[0].id,'custom/model');
    assert.equal(state.modelChoices[1].name,'Listed model');
    state.modelSelection.model='other/model';
    assert.equal(state.modelDirty,true);
    state.resetModelSelection();
    assert.equal(state.modelSelection.model,'custom/model');
    assert.equal(state.modelDirty,false);
});

test('catalog and detail refresh preserve an unsaved model selection', async () => {
    const {state,api} = setup();
    state.selected={provider:'openai',model:'old'};
    state.modelSelection={provider:'third',model:'draft',effort:'low',tier:''};
    state.inspectorOpen=true;
    api.get=async route=>route.includes('model-options')
        ?{provider:'openai',model:'default',providers:[]}
        :{session:{provider:'openai',model:'server-current'}};
    await state.loadModels(true);
    assert.equal(state.modelSelection.model,'draft');
    await state.loadSessionDetail();
    assert.equal(state.selected.model,'server-current');
    assert.equal(state.modelSelection.model,'draft');
    assert.equal(state.modelsLoading,false);
});

test('model save sends one captured choice and confirms the session before clearing pending edits', async () => {
    const {state,api,posts,bodies} = setup();
    let finish;
    api.post=async (route,body)=>{posts.push(route);bodies.push({...body});await new Promise(resolve=>finish=resolve);};
    state.selected={id:'session-1',provider:'openai',model:'old'};
    state.sessions=[state.selected];
    state.inspectorOpen=true;
    state.modelSelection={provider:'third',model:' new/model ',effort:'high',tier:'fast'};
    api.get=async route=>route.includes('model-options')?{providers:[]}:{session:{id:'session-1',provider:'third',model:'new/model',reasoning_effort:'high',service_tier:'fast'}};
    const saving=state.saveModel();
    assert.equal(state.modelSaving,true);
    await state.saveModel();
    assert.equal(posts.length,1);
    assert.equal(bodies[0].model,'new/model');
    assert.equal(bodies[0].service_tier,'fast');
    finish();await saving;
    assert.equal(state.selected.model,'new/model');
    assert.equal(state.sessions[0].model,'new/model');
    assert.equal(state.modelDirty,false);
    assert.match(state.modelNotice,/已应用/);
    assert.equal(state.modelSaving,false);
});

test('late model save cannot refresh or display feedback in another session', async () => {
    const {state,api} = setup();
    let finish;
    api.post=()=>new Promise(resolve=>finish=resolve);
    state.selected={provider:'openai',model:'old'};
    state.modelSelection={provider:'openai',model:'new',effort:'',tier:''};
    const saving=state.saveModel();
    ++state.epoch;state.selectedId='session-2';state.selected={model:'second'};
    state.modelSelection={provider:'openai',model:'second',effort:'',tier:''};
    finish({});await saving;
    assert.equal(state.selected.model,'second');
    assert.equal(state.modelSelection.model,'second');
    assert.equal(state.modelNotice,'');
    assert.equal(state.modelSaving,false);
});

test('failed model save keeps edits and displays its error inside model settings', async () => {
    const {state,api} = setup();
    state.selected={model:'old'};
    state.modelSelection={provider:'openai',model:'new',effort:'',tier:''};
    api.post=async()=>{throw new Error('模型不可用');};
    await state.saveModel();
    assert.equal(state.modelSaveError,'模型不可用');
    assert.equal(state.modelSelection.model,'new');
    assert.equal(state.modelSaving,false);
    state.resetModelSelection();
    assert.equal(state.modelSaveError,'');
});

test('unsupported Hermes model settings cannot be submitted and supported changes keep native reasoning shape', async () => {
    const {state,bodies} = setup('hermes');
    state.selected={model:'old'};
    state.modelSelection={provider:'custom:one',model:'new',effort:'none',tier:''};
    state.modelSessionSupported=false;
    await state.saveModel();
    assert.equal(bodies.length,0);
    assert.match(state.modelSaveError,/全局模型/);
    state.modelSessionSupported=true;
    state.loadSessionDetail=async()=>false;
    state.loadModels=async()=>{};
    await state.saveModel();
    assert.equal(bodies[0].model_options.reasoning.enabled,false);
    assert.equal('service_tier' in bodies[0],false);
});


test('model chip describes the applied provider while the inspector edits another provider', () => {
    const {state,options} = setup();
    state.models=[{slug:'openai',name:'Official'},{slug:'third',name:'Gateway'}];
    state.selected={provider:'openai',model:'current'};
    state.modelSelection={provider:'third',model:'draft',effort:'',tier:''};
    assert.match(options.computed.modelChipTitle.call(state),/Official/);
    assert.doesNotMatch(options.computed.modelChipTitle.call(state),/Gateway/);
});

test('edits made during a pending detail read survive its completion', async () => {
    const {state,api} = setup();
    let finish;
    state.selected={provider:'openai',model:'old'};
    state.resetModelSelection();
    state.inspectorOpen=true;
    api.get=()=>new Promise(resolve=>finish=resolve);
    const loading=state.loadSessionDetail();
    state.modelSelection.model='typed-while-loading';
    finish({session:{provider:'openai',model:'new-server-value'}});
    await loading;
    assert.equal(state.selected.model,'new-server-value');
    assert.equal(state.modelSelection.model,'typed-while-loading');
});
