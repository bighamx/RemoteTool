const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const context={window:{markdownit:require('../../WebApplication/wwwroot/vendor/markdown-it/markdown-it.min.js')}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../WebApplication/wwwroot/js/claude-content.js'),'utf8'),context);const {project,html}=context.window.RemoteToolClaudeContent;
const row=(id,role,blocks)=>({id,role,blocks});
test('file calls and source-heavy results pair into one collapsed tool instead of user dialogue',()=>{
 const input=[row('prompt','user',[{type:'text',text:'Fix it'}]),row('call','assistant',[{type:'thinking',thinking:'private'},{type:'tool_use',id:'read',name:'Read',input:{file_path:'/app/source.js'}}]),row('result','user',[{type:'tool_result',tool_use_id:'read',content:'source code\n'.repeat(10000)}]),row('answer','assistant',[{type:'text',text:'## Done\nFixed the issue.'}])];
 const before=JSON.stringify(input);const result=project(input).history;assert.equal(result.length,3);assert.equal(result[1].role,'tool');assert.equal(result[1].parts.length,1);assert.equal(result[1].parts[0].name,'读取文件');assert.equal(result[1].parts[0].complete,true);assert.equal(result[1].parts[0].results[0].content.length,120000);assert.equal(JSON.stringify(input),before);
});
test('adjacent tools group but real text and message order remain intact',()=>{
 const input=[row('1','assistant',[{type:'tool_use',id:'a',name:'Read',input:{}}]),row('2','user',[{type:'tool_result',tool_use_id:'a',content:'a'}]),row('3','assistant',[{type:'tool_use',id:'b',name:'Edit',input:{new_string:'code'}}]),row('4','assistant',[{type:'text',text:'Your result'},{type:'tool_use',id:'c',name:'Bash',input:{command:'test'}}]),row('5','user',[{type:'text',text:'Keep this code literal'},{type:'tool_result',tool_use_id:'c',content:'done',is_error:true}])];
 const output=project(input).history;assert.equal(output.length,3);assert.equal(output[0].parts.length,2);assert.equal(output[1].parts[0].text,'Your result');assert.equal(output[1].parts[1].error,true);assert.equal(output[2].parts[0].text,'Keep this code literal');
});
test('orphan results remain readable, reasoning-only messages disappear, raw code is not deleted',()=>{
 const output=project([row('1','assistant',[{type:'thinking',thinking:'hidden'}]),row('2','user',[{type:'tool_result',tool_use_id:'missing',content:'original result'}]),row('3','assistant',[{type:'text',text:'x'.repeat(7000)}])]).history;
 assert.equal(output.length,2);assert.equal(output[0].parts[0].name,'工具结果');assert.equal(output[1].parts[0].long,true);assert.equal(output[1].parts[0].text.length,7000);assert.ok(!html('<img src=x onerror=alert(1)>').includes('<img'));
});
test('history and live streams share tool identities without duplicating their cards',()=>{
 const history=[row('a','assistant',[{type:'tool_use',id:'tool',name:'Read',input:{}}])];const live=[row('live','tool',[{type:'tool_result',tool_use_id:'tool',content:[{type:'text',text:'result'}]}]),row('a','assistant',[{type:'tool_use',id:'tool',name:'Read',input:{}}])];const output=project(history,live);assert.equal(output.history[0].parts[0].complete,true);assert.equal(output.live.length,0);
});

test('Markdown is formatted while HTML, unsafe links and long code stay safe and compact',()=>{
 const rendered=html('**完成**\n\n- item\n\n```js\n'+'const x = 1;\n'.repeat(20)+'```\n\n<img src=x onerror=alert(1)>\n\n[unsafe](javascript:alert(1))');
 assert.ok(rendered.includes('<strong>完成</strong>'));assert.ok(rendered.includes('<ul>'));assert.ok(rendered.includes('class="claude-code-fold"'));assert.ok(!rendered.includes('<img'));assert.ok(!rendered.includes('href="javascript:'));assert.ok(!rendered.includes('<details open'));
});
