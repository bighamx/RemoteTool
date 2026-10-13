const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

let definition;
const provider = { id: 'p1', name: 'Example', issuer: 'https://issuer.example/', clientId: 'client',
    allowedEmails: ['user@example.com'], enabled: true, discovery: true, scopes: ['openid', 'email'] };
const api = {
    async get(url) {
        if (url.endsWith('/settings')) return [provider];
        if (url.endsWith('/access-settings')) return { enabled: true, team: 'myteam', audience: 'aud', allowedEmails: ['user@example.com'] };
        throw new Error('unexpected GET ' + url);
    },
    async post() { return { id: 'p1' }; },
    id: encodeURIComponent
};
const context = {
    document: { getElementById: () => ({}) },
    window: { Vue: { createApp(value) { definition = value; return { use() { return this; }, mount() {} }; } }, RemoteToolApi: api },
    Vue: null, ViewUIPlus: {}, location: { origin: 'https://remote.example' }, console
};
context.Vue = context.window.Vue;
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../WebApplication/wwwroot/js/oidc-settings.js'), 'utf8'), context);
const state = Object.assign(definition.data(), definition.methods);
let focused = false;
state.$refs = { oidcTab: { focus() { focused = true; } }, accessTab: { focus() {} } };
state.$nextTick = callback => callback();

(async () => {
    await state.refresh();
    state.newProvider();
    state.form.name = 'Unsaved provider';
    state.access.team = 'unsaved-team';
    state.accessEmails = 'unsaved@example.com';
    state.selectTab('access');
    state.selectTab('oidc');
    assert.equal(state.form.name, 'Unsaved provider');
    assert.equal(state.access.team, 'unsaved-team');
    assert.equal(state.accessEmails, 'unsaved@example.com');
    state.selectTab('access');
    state.onTabKeydown({ key: 'ArrowLeft', preventDefault() {} });
    assert.equal(state.activeTab, 'oidc');
    assert.equal(focused, true);
    await state.refreshProviders();
    assert.equal(state.access.team, 'unsaved-team', 'refreshing OIDC must not discard the Access draft');
    state.form = { ...provider, clientSecret: '' };
    state.emailsText = 'user@example.com';
    await state.saveProvider();
    assert.equal(state.access.team, 'unsaved-team', 'saving OIDC must not discard the Access draft');
    console.log('Identity tabs: switching, keyboard focus and independent drafts passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
