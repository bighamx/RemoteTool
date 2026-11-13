const assert=require('node:assert/strict');
const {test}=require('node:test');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const citation=require('../../WebApplication/wwwroot/js/agent-citations.js');
const first='turn792085news25',second='turn784471view0';
const marker=(...ids)=>'\uE200cite\uE202'+ids.join('\uE202')+'\uE201';
function fixture(agent='codex') {
    let options;
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../WebApplication/wwwroot/js/agent-workspace.js'),'utf8'),{
        window:{RemoteToolApi:{},RemoteToolCitations:citation,markdownit:require('../../WebApplication/wwwroot/vendor/markdown-it/markdown-it.min.js')},
        document:{currentScript:null,getElementById:id=>id==='agent-app'?{dataset:{agent}}:null},location:{search:''},localStorage:{getItem:()=>null},URLSearchParams,
        Vue:{createApp:value=>{options=value;return {use(){},mount(){}};}},ViewUIPlus:{},console
    });
    return Object.assign(options.data(),options.methods);
}
test('actual Markdown rendering turns the reported historical markers into stable compact source numbers',()=>{
    const s=fixture(),text='回复。'+marker(first,second)+' 再引用'+marker(first);
    const html=s.renderMarkdown(text);
    assert.equal((html.match(/\[1\]/g)||[]).length,2);assert.ok(html.includes('[2]'));
    assert.equal(html.includes('\uE200'),false);assert.equal(html.includes(first),false);
    assert.equal((html.match(/部分引用链接未随消息返回/g)||[]).length,1);
});
test('real source metadata creates safe clickable links and invalid URLs stay explicit',()=>{
    const s=fixture(),text='来源'+marker(first,second);
    const html=s.renderMarkdown(text,[{reference_id:first,url:'https://example.com/report',title:'A "source" <name>'},{reference_id:second,url:'javascript:alert(1)'}]);
    assert.match(html,/href="https:\/\/example.com\/report"/);assert.match(html,/rel="noopener noreferrer"/);
    assert.equal(html.includes('javascript:'),false);assert.equal(html.includes('title="A "source"'),false);assert.ok(html.includes('引用链接未提供'));
    const resolved=s.renderMarkdown(text,[{marker:marker(first,second),url:'https://example.com/report',title:'Source'}]);
    assert.equal(resolved.includes('部分引用'),false);assert.match(resolved,/href=/);
});
test('code examples, indented code, existing link labels and other agents retain their own Markdown semantics',()=>{
    const s=fixture(),raw=marker(first);
    for(const source of ['`'+raw+'`','```text\n'+raw+'\n```','    '+raw,'['+raw+'](https://example.com)']) {
        const html=s.renderMarkdown(source);assert.ok(html.includes(raw));assert.equal(html.includes('agent-citation-note'),false);
    }
    assert.ok(fixture('hermes').renderMarkdown(raw).includes(raw));
    assert.match(s.renderMarkdown('- item\n  - nested '+raw),/agent-citation/);
});
test('streamed fragments wait for a complete marker and metadata can upgrade an unchanged message',()=>{
    const s=fixture(),raw=marker(first);
    for(const tail of ['\uE200','\uE200c','\uE200cite\uE202turn792085']) {
        const html=s.renderMarkdown('回复'+tail,[],true);assert.equal(html.includes('\uE200'),false);assert.equal(html.includes('未随消息'),false);
    }
    assert.equal(s.renderMarkdown(raw,[],true).includes('未随消息'),false);
    assert.ok(s.renderMarkdown(raw).includes('未随消息'));
    assert.match(s.renderMarkdown(raw,[{reference_id:first,url:'https://example.com'}]),/href=/);
    assert.equal(citation.safeUrl('https://user:password@example.com'),'');
    assert.equal(citation.safeUrl('data:text/html,test'),'');
});

test('the SPA entry loads citations before mounting and stream metadata survives native history takeover',async()=>{
    const router=fs.readFileSync(path.join(__dirname,'../../WebApplication/wwwroot/js/workspace-router.js'),'utf8');
    assert.match(router,/codex:.*'agent-citations\.js', 'agent-workspace\.js'/);
    const {createHarness}=require('./agent-send.test.cjs');
    let written;
    const h=createHarness([],'codex',{write:async(id,payload)=>{written=payload;}}),s=h.state;
    s.activeRun='r';s.loadHistory=h.options.methods.loadHistory.bind(s);s.loadContext=async()=>{};
    s.onRunEvent({seq:1,type:'message.started',item_id:'message'});
    s.onRunEvent({seq:2,type:'message.delta',item_id:'message',delta:'回答'+marker(first)});
    const sources=[{reference_id:first,url:'https://example.com/report'}];
    s.onRunEvent({seq:3,type:'message.completed',item_id:'message',text:'回答'+marker(first),citations:sources});
    h.setHistory([{id:7,native_item_id:'message',role:'assistant',content:'回答'+marker(first)}]);
    await s.loadHistory();
    assert.equal(s.messages[0].citations[0].url,sources[0].url);
    assert.equal(written.data[0].citations[0].url,sources[0].url);
    s.liveSegments=[];await s.loadHistory();
    assert.equal(s.messages[0].citations[0].url,sources[0].url);
});
