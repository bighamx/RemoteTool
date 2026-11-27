const assert = require('node:assert/strict');
const {test} = require('node:test');
const {createHarness} = require('./agent-send.test.cjs');

test('workspace warmup reads and persists the server setting and reconciles a lost response', async () => {
    const {state, api} = createHarness([]);
    let enabled = false;
    api.get = async () => ({supported:true,enabled,items:[]});
    api.post = async (path, body) => {assert.match(path,/workspaces\/warmup$/);enabled=body.enabled;throw new Error('response lost');};
    await state.loadWorkspaceWarmup();
    assert.equal(state.workspaceWarmup.enabled,false);
    await state.setWorkspaceWarmup(true);
    await state.loadWorkspaceWarmup();
    assert.equal(state.workspaceWarmup.enabled,true,'the subsequent server snapshot owns the displayed setting');
    assert.equal(state.warmupSaving,false);
    assert.equal(state.warmupError,'');
});

test('older servers hide the warmup switch and read failures retain the last state', async () => {
    const {state,api,posts}=createHarness([]);
    api.get=async()=>{throw Object.assign(new Error('unsupported'),{status:404});};
    await state.loadWorkspaceWarmup();
    assert.equal(state.workspaceWarmup,null);
    await state.setWorkspaceWarmup(true);
    assert.equal(posts.length,0);
    state.workspaceWarmup={supported:true,enabled:true};
    api.get=async()=>{throw new Error('offline');};
    await state.loadWorkspaceWarmup();
    assert.equal(state.workspaceWarmup.enabled,true);
    assert.match(state.warmupError,/读取失败/);
});

test('provider switch model picker resolves aliases and requires a catalog selection or explicit custom input', () => {
    const {state,options}=createHarness([]);
    for(const name of ['thirdPartyProviders','switchProviderMetadata','switchModelChoices','switchModelValid'])
        Object.defineProperty(state,name,{get:()=>options.computed[name].call(state)});
    state.providersState={endpoints:[{id:'third',model:'model-a'}]};
    state.globalModelOptions={providers:[{slug:'canonical',aliases:['third'],models:[{model:'model-a',displayName:'Model A'},'model-b']}]};
    state.providerSwitch.provider='third';
    state.chooseSwitchProvider();
    assert.equal(state.switchModelChoices[0].name,'Model A');
    assert.equal(state.switchModelValid,true);
    state.providerSwitch.model='nonexistent-6.2';
    assert.equal(state.switchModelValid,false);
    state.providerSwitchCustomModel=true;
    assert.equal(state.switchModelValid,true);
    state.providerSwitch.model=' ';
    assert.equal(state.switchModelValid,false);
});

test('provider editing opens a separate form and closes it only after a successful save', async () => {
    const {state,api,bodies}=createHarness([{}]);
    state.refreshWorkspaces=async()=>{};
    state.codexWorkspacePage='manage';
    state.editCodexProvider({id:'fixture',name:'Fixture',base_url:'https://example.invalid/v1',model:'fixture-model'});
    assert.equal(state.codexWorkspacePage,'edit');
    assert.equal(state.codexProviderEdit.api_key,'');
    await state.saveCodexProvider();
    assert.equal(state.codexWorkspacePage,'manage');
    assert.equal(state.codexProviderEdit,null);
    assert.equal(Object.hasOwn(bodies[0],'api_key'),false);
    state.editCodexProvider();
    api.post=async()=>{throw new Error('save failed');};
    await state.saveCodexProvider();
    assert.equal(state.codexWorkspacePage,'edit');
    assert.equal(state.workspaceError,'save failed');
});
