const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
let options;
const root = {dataset: {agent: 'hermes'}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../WebApplication/wwwroot/js/agent-workspace.js'), 'utf8'), {
    window: {RemoteToolApi: {}},
    document: {currentScript: null, getElementById: id => id === 'agent-app' ? root : null},
    location: {search: ''}, localStorage: {getItem: () => null},
    URLSearchParams, Vue: {createApp: value => { options = value; return {use() {}, mount() {}}; }},
    ViewUIPlus: {}, console
});
const context = {...options.methods, agent: 'hermes', saveDelivered() {}};
const now = Date.now();
const config = '{\n  "mcpServers": {}\n}';
const fullText = '手机不在home局域网\n在office局域网\nphonetool直连是启用了的\n' + config;
const native = {id: 74678, role: 'user', timestamp: now,
    content: '[OUT-OF-BAND USER MESSAGE — a direct message from the user]\n' + fullText + '\n[/OUT-OF-BAND USER MESSAGE]'};
const receipt = (input, key) => ({input, key, delivery: '已送达', attachments: [], createdAt: now - 600000});
context.messages = [native];
context.delivered = ['手机不在home局域网', '在office局域网', 'phonetool直连是启用了的', config].map((text, i) => receipt(text, String(i)));
context.reconcileDelivered();
assert.ok(context.delivered.every(row => row.canonicalId === native.id));
assert.equal(context.messageText(native), fullText);
// An already acknowledged receipt must reserve its block on the next refresh.
context.delivered.push(receipt('在office局域网', 'duplicate'));
context.reconcileDelivered();
assert.equal(context.delivered.at(-1).canonicalId, undefined);
assert.equal(context.localMessageMatched(receipt('office局域网', 'substring'), [native]), undefined);
assert.equal(context.localMessageMatched(receipt(config, 'plain'), [{...native, content: fullText}]), undefined);
// Codex uses the native request key, independent of text or attachment timing.
context.agent = 'codex';
const keyed = {...native, client_request_id: 'phone', attachments: [{id: 'image'}]};
assert.equal(context.localMessageMatched({...receipt('请查看附件', 'phone'), attachments: [{id: 'image'}]}, [keyed]), keyed);
assert.equal(context.localMessageMatched(receipt('请查看附件', 'other'), [keyed]), undefined);
console.log('Agent workspace receipt checks passed');
