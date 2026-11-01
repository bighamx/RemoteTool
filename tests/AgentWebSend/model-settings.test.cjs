const assert = require('node:assert/strict');
const {test} = require('node:test');
const {createHarness} = require('./agent-send.test.cjs');

function setup(agent='codex') {
    const h = createHarness([],agent);
    for (const name of ['selectedProviderMetadata','providerChoices','modelChoices','modelMetadata','activeProvider','officialProviderInUse','thirdPartyProviders'])
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
