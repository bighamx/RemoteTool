const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../WebApplication/wwwroot/js/agent-workspace.js'), 'utf8');
let options;
const root = { dataset: { agent: 'hermes' } };
const sandbox = {
    document: { currentScript: null, getElementById: id => id === 'agent-app' ? root : null },
    window: { RemoteToolApi: {} },
    Vue: { createApp: value => { options = value; return { use() {}, mount() {} }; } },
    ViewUIPlus: {}
};
vm.runInNewContext(source, sandbox, { filename: 'agent-workspace.js' });
const visible = options.computed.visibleLiveSegments;
const helper = name => (options.methods && options.methods[name]) || options.computed[name];

const now = Date.now();
const context = messages => ({
    messages,
    liveSegments: [
        { id: 'i1', text: '第一条旁白内容', createdAt: now - 1000 },
        { id: 'i2', text: '第二条旁白内容', createdAt: now - 990 },
        { id: 'snap', text: '第一条旁白内容\n\n第二条旁白内容\n\n第三条还没落库', createdAt: now - 980 },
    ],
    ...(Object.fromEntries(['asText', 'narrationNormalized', 'narrationCovers', 'sameNarration', 'messageStamp', 'messageText', 'messageTextRaw', 'unwrapOob']
        .map(name => [name, (options.methods && options.methods[name]) || options.computed[name]]))),
});
const msg = (text, ts) => ({ role: 'assistant', timestamp: ts, content: text });

// 场景1：前两条 interim 已落库，第三条还没有 —— 只保留拼接段的未落库部分
const partial = visible.call(context([msg('第一条旁白内容', now - 2000), msg('第二条旁白内容', now - 1900)]));
assert.equal(partial.map(s => s.id).join(), 'snap', 'partial landing keeps only the snapshot segment');
assert.match(partial[0].text, /第三条还没落库/);

// 场景2：三条全部落库 —— live 区域应为空（不再与历史气泡重复）
const all = visible.call(context([
    msg('第一条旁白内容', now - 2000),
    msg('第二条旁白内容', now - 1900),
    msg('第三条还没落库', now - 500),
]));
assert.equal(all.length, 0, 'fully landed snapshot is suppressed');

// 场景3：全新的、历史里没有的 live 段必须保留
const fresh = visible.call({
    messages: [msg('第一条旁白内容', now - 2000)],
    liveSegments: [{ id: 'live-new', text: '全新的进行中旁白', createdAt: now }],
    asText: helper('asText'),
    narrationNormalized: helper('narrationNormalized'),
    narrationCovers: helper('narrationCovers'),
    sameNarration: helper('sameNarration'),
    messageStamp: helper('messageStamp'),
    messageText: helper('messageText'),
    messageTextRaw: helper('messageTextRaw'),
    unwrapOob: helper('unwrapOob'),
});
assert.equal(fresh.map(s => s.id).join(), 'live-new', 'unrelated live segment stays visible');

console.log('narration dedupe: snapshot-vs-landed-interim cases passed');
