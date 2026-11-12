const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function fixture(get) {
    let options;
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../WebApplication/wwwroot/js/agent-workspace.js'), 'utf8'), {
        window: {RemoteToolApi: {get, id: encodeURIComponent}},
        document: {currentScript: null, getElementById: id => id === 'agent-app' ? {dataset: {agent: 'codex'}} : null},
        location: {search: ''}, localStorage: {getItem: () => null}, URLSearchParams,
        Vue: {createApp(value) {options = value; return {use() {}, mount() {}};}}, ViewUIPlus: {}
    });
    const state = {...options.data(), ...options.methods};
    for (const [name, get] of Object.entries(options.computed)) Object.defineProperty(state, name, {get: () => get.call(state)});
    return state;
}
const quota = {rateLimits: {primary: {windowDurationMins: 300, usedPercent: 77}, secondary: {windowDurationMins: 10080, usedPercent: 92}}};
const spaces = {current: 'official', data: [{id: 'official', workspace_id: 'account', workspace_name: 'Saved workspace'}]};
test('third-party model keeps official workspace quotas visible and labels their source', async () => {
    const calls = [];
    const state = fixture(async url => {
        calls.push(url);
        if (url.endsWith('/providers')) return {provider: 'custom', official_provider: false};
        if (url.endsWith('/workspaces')) return spaces;
        return quota;
    });
    await state.loadCurrentUsage();
    assert.ok(calls.includes('/api/codex/usage'));
    assert.equal(state.officialProviderInUse, false);
    assert.match(state.quotaWorkspaceName, /官方额度 · Saved workspace/);
    assert.equal(state.headerQuotaWindows[0].remaining, 23);
    assert.equal(state.headerQuotaWindows[1].remaining, 8);
    assert.equal(state.usageError, '');
});
test('workspace refresh updates the header for third-party models without accepting another account', async () => {
    let wrong = false;
    const state = fixture(async url => {
        if (url.endsWith('/providers')) return {provider: 'custom', official_provider: false};
        if (url.endsWith('/workspaces')) return spaces;
        if (url.includes('/usage')) return {workspace_id: 'official', chatgpt_account_id: wrong ? 'other-account' : 'account', available: true, usage: quota};
        return {};
    });
    await state.refreshWorkspaces();
    assert.equal(state.headerQuotaWindows[0].remaining, 23);
    state.codexUsage = null;
    wrong = true;
    await state.refreshWorkspaces();
    assert.equal(state.codexUsage, null);
    assert.equal(state.workspaceUsages.official.available, false);
});
test('failed and superseded reads never display another workspace quota', async () => {
    let finish;
    const state = fixture(url => url.endsWith('/workspaces') ? new Promise(resolve => finish = resolve) : Promise.resolve(quota));
    const pending = state.loadCurrentUsage();
    state.usageEpoch++;
    state.usageLoading = false;
    finish(spaces);
    await pending;
    assert.equal(state.codexUsage, null);
    const failed = fixture(async () => {throw new Error('登录已失效');});
    failed.codexUsage = quota;
    await failed.loadCurrentUsage();
    assert.equal(failed.codexUsage, null);
    assert.equal(failed.usageError, '登录已失效');
    assert.equal(failed.headerQuotaWindows[0].remaining, null);
});
