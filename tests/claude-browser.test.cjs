// Claude Code in the shared agent workspace, against a mocked /api/claude contract.
// Run: node tests/claude-browser.test.cjs  (needs a built WebApplication.dll, Chrome and Playwright)
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process');
const {chromium}=require(process.env.REMOTETOOL_TEST_PLAYWRIGHT||'playwright');
(async()=>{
const root=path.resolve(__dirname,'..'),temp=fs.mkdtempSync(path.join(os.tmpdir(),'claude-web-'));
const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));const base=`http://127.0.0.1:${port}`;
const server=spawn(process.env.REMOTETOOL_TEST_DOTNET||'dotnet',[path.join(root,'WebApplication/bin/Debug/net8.0/WebApplication.dll')],{cwd:path.join(root,'WebApplication'),env:{...process.env,ASPNETCORE_URLS:base,Auth__Username:'fixture',Auth__Password:'fixture-only-password',Auth__JwtSecret:'fixture-only-signing-key-at-least-thirty-two-characters',REMOTETOOL_DATA_DIR:path.join(temp,'data'),Hangfire__Database:path.join(temp,'hangfire.db'),Claude__Enabled:'false'},stdio:'ignore'});
let browser;
try{
for(let i=0;i<100;i++){try{if((await fetch(base+'/api/device/identity')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
browser=await chromium.launch({headless:true,executablePath:process.env.REMOTETOOL_TEST_CHROME||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const context=await browser.newContext({viewport:{width:1440,height:950}});const page=await context.newPage();page.setDefaultTimeout(10000);
const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
assert.equal((await context.request.get(base+'/api/claude/sessions')).status(),401,'API requires login');
assert.ok((await context.request.post(base+'/api/auth/login',{data:{username:'fixture',password:'fixture-only-password'}})).ok());
const disabled=await (await context.request.get(base+'/api/agent-setup/claude')).json();assert.equal(disabled.state,'disabled');

const held={id:'held-session',title:'Terminal session',cwd:'/project',status:null,last_active:Date.now()};
const own={id:'own-session',title:'Remote session',cwd:'/project',status:null,last_active:Date.now()-1000,permission_mode:'default'};
let sessions=[held,own],detail={...own},run=null,events=[],posts=[],tookOver=false,usageFails=false;
const history=[{id:'u1',role:'user',content:'Earlier question',timestamp:Date.now()-60000,editable:false},
  {id:'a1',role:'assistant',content:'**Earlier answer**',timestamp:Date.now()-50000},
  {id:'tools-u1',role:'system',type:'toolSummary',tool_summary_id:'tools-u1',content:'工具 1 次 · 文件修改 0 次',timestamp:Date.now()-40000}];
const sse=list=>list.map(e=>`id: ${e.seq}\ndata: ${JSON.stringify(e)}\n\n`).join('');
await page.route('**/api/claude/**',async route=>{
  const request=route.request(),url=new URL(request.url()),p=url.pathname.replace('/api/claude','');const method=request.method();
  if(method!=='GET')posts.push({path:p,body:request.postDataJSON?.()??null,key:request.headers()['idempotency-key']});
  let json={};
  if(p==='/sessions'&&method==='GET')json={data:sessions,has_more:false};
  else if(p==='/sessions'&&method==='POST'){const body=request.postDataJSON();const created={id:'created-session',title:body.title||'新 Claude Code 会话',cwd:body.cwd,permission_mode:body.permission_mode};sessions=[created,...sessions];json={session:created};}
  else if(/^\/sessions\/held-session$/.test(p))json={session:{...held,control:{state:'writer_held'}}};
  else if(/^\/sessions\/[^/]+$/.test(p))json={session:p.includes('own')?detail:sessions.find(s=>p.endsWith(s.id))};
  else if(p.endsWith('/messages'))json={data:p.includes('/held-')||p.includes('/own-')?history:[],has_more:false,oldest_id:'u1'};
  else if(p.endsWith('/activity'))json=p.includes('/held-')&&!tookOver?{available:true,running:true,kind:'external',pid:4242,started_at:1700000000,external_status:'idle',progress:[]}:{available:true,running:false,progress:[]};
  else if(p.endsWith('/takeover')){tookOver=true;json={ready:true,terminated_pid:4242};}
  else if(p.endsWith('/active-run'))json=run&&p.includes('/own-')?{session_id:'own-session',run_id:run.run_id,status:run.status}:{run_id:null};
  else if(p.endsWith('/context'))json={context:{available:true,tokens:50000,limit:200000,estimated:false}};
  else if(p.endsWith('/permissions')&&method==='GET')json={mode:detail.permission_mode,choices:[{id:'default',label:'按本机规则询问',description:'d'},{id:'acceptEdits',label:'自动批准文件编辑',description:'e'},{id:'plan',label:'计划模式',description:'p'}],current:{label:'按本机规则询问'}};
  else if(p.endsWith('/permissions')){detail={...detail,permission_mode:request.postDataJSON().mode};json={mode:detail.permission_mode,applied_to_running_task:false};}
  else if(p.includes('/tools/'))json={data:[{id:'t1',tool:'读取文件',status:'completed',description:'/project/a.js\n\nRAW'}],tools:1,file_changes:0,has_more:false};
  else if(p.endsWith('/acknowledge')){detail={...detail,unresolved_run:null};json={status:'acceptance_unknown',reviewed:true};}
  else if(p.endsWith('/rewind/cancel')){detail={...detail,rewind_pending:false};json={success:true};}
  else if(p==='/runs'&&method==='POST'){run={run_id:'run-1',session_id:'own-session',status:'running',created_at:Math.floor(Date.now()/1000)};json={...run,delivery:'accepted'};}
  else if(p==='/runs/run-1/events'){await route.fulfill({status:200,headers:{'content-type':'text/event-stream'},body:sse(events)});return;}
  else if(p==='/runs/run-1/approval'){run={...run,approval:null,status:'running'};json={accepted:true};}
  else if(p==='/runs/run-1'){const pending=[...events].reverse().find(e=>e.type==='approval.request'||e.type==='approval.resolved');json={...run,approval:pending?.type==='approval.request'&&!posts.some(row=>row.body?.request_id===pending.request_id)?pending:null};}
  else if(p==='/model-options')json={providers:[{id:'anthropic',slug:'anthropic',name:'Claude',models:[{model:'opus',displayName:'Opus',supportedReasoningEfforts:[{reasoningEffort:'high'}]}]}],provider:'anthropic'};
  else if(p==='/usage'){if(usageFails){await route.fulfill({status:503,json:{message:'temporary failure'}});return;}json={available:true,source:'官方 Claude Code 订阅额度',rateLimitsByLimitId:{claude:{primary:{windowDurationMins:300,usedPercent:0},secondary:{windowDurationMins:10080,usedPercent:64}}}};}
  else if(p.endsWith('/files'))json={data:[]};
  await route.fulfill({json});
});
await page.route('**/api/agent-setup/claude',route=>route.fulfill({json:{agent:'claude',state:'ready'}}));
await page.goto(base+'/#/claude');
const app=page.locator('#workspace-claude');
await app.locator('.agent-pane-identity h2').filter({hasText:'Claude Code'}).waitFor();
assert.equal(await app.locator('.agent-brand-icon img').getAttribute('src'),'/icons/claude-color.svg');

// A session held by a desktop Claude process can be read but not written.
await app.getByRole('button',{name:/Terminal session/}).click();
await app.getByText('电脑上的 Claude Code 正在使用此会话（PID 4242，空闲）',{exact:false}).waitFor();
assert.ok(await app.locator('.agent-send-actions').getByRole('button',{name:'发送'}).isDisabled(),'sending is blocked while the desktop holds the session');
await app.locator('.agent-markdown strong').filter({hasText:'Earlier answer'}).waitFor();
await app.getByRole('button',{name:'查看工具记录'}).click();await page.locator('.workspace-modal .agent-tool-detail').filter({hasText:'读取文件'}).waitFor();await page.locator('.workspace-modal').getByRole('button',{name:'关闭'}).click();
// Takeover ends exactly the confirmed desktop process, then the session becomes writable.
const heldBanner=app.locator('.agent-banner').filter({hasText:'会话被电脑占用'});await heldBanner.getByRole('button',{name:'结束电脑进程并接管'}).click();await heldBanner.waitFor({state:'hidden'});
assert.deepEqual(posts.find(row=>row.path==='/sessions/held-session/takeover').body,{expected_pid:4242,expected_started_at:1700000000,expected_status:'idle'});
await app.locator('textarea[aria-label="消息输入"]').fill('after takeover');assert.ok(!(await app.locator('.agent-send-actions').getByRole('button',{name:'发送'}).isDisabled()),'writable after takeover');await app.locator('textarea[aria-label="消息输入"]').fill('');

// An unresolved result blocks sending until explicitly acknowledged; never resent automatically.
detail={...own,unresolved_run:{run_id:'old-run',error:'桥接进程已结束，上一轮结果待核对；未自动重发。'},rewind_pending:true};
await app.getByRole('button',{name:/Remote session/}).click();
const unresolved=app.locator('.agent-banner').filter({hasText:'上一轮结果待核对'});await unresolved.waitFor();
await unresolved.getByRole('button',{name:'我已核对，继续'}).click();await unresolved.waitFor({state:'hidden'});
assert.ok(posts.some(row=>row.path==='/runs/old-run/acknowledge'));
await app.locator('.agent-banner').filter({hasText:'已撤回'}).getByRole('button',{name:'取消撤回'}).click();
assert.ok(posts.some(row=>row.path==='/sessions/own-session/rewind/cancel'));

// Official usage includes a zero-used short window and an independently populated weekly window.
await app.locator('.agent-model-chip').click();
const quota=app.locator('.agent-inspector .inspector-section').filter({hasText:'账户额度'});
await quota.getByText(/5 小时 · 剩余 100%/).first().waitFor();
assert.ok((await quota.textContent()).includes('周额度 · 剩余 36%'));
await app.locator('.agent-inspector-close').click();usageFails=true;
await app.locator('.agent-model-chip').click();
await quota.getByText('额度刷新失败，当前显示上次读取结果').waitFor();
assert.ok((await quota.textContent()).includes('周额度 · 剩余 36%'));
await app.locator('.agent-inspector-close').click();usageFails=false;

// Native permission modes, never a bypass option.
await app.locator('.agent-head-trailing details.agent-session-actions summary').click();await app.locator('.agent-head-trailing').getByRole('button',{name:'会话权限'}).click();
const permissions=page.locator('.ivu-modal').filter({hasText:'当前会话权限'});await permissions.getByText('计划模式',{exact:true}).waitFor();
assert.equal(await permissions.getByText(/完全访问|bypass/i).count(),0);
await permissions.locator('input[value=plan]').check();await permissions.getByRole('button',{name:'保存'}).click();
assert.ok(posts.some(row=>row.path==='/sessions/own-session/permissions'&&row.body.mode==='plan'));

// Send, stream, then answer an AskUserQuestion inline and approve a tool with the native suggestion.
events=[{seq:1,type:'run.running',status:'running'},{seq:2,type:'message.started',item_id:'m:0'},{seq:3,type:'message.delta',item_id:'m:0',delta:'Streaming reply'},
  {seq:4,type:'approval.request',request_id:'q1',kind:'user_input',questions:[{id:'0',question:'Which color?',multiSelect:true,options:[{label:'Red'},{label:'Blue'}]}],choices:[]}];
await app.locator('textarea[aria-label="消息输入"]').fill('Hello Claude');await app.locator('.agent-send-actions').getByRole('button',{name:'发送'}).click();
const send=posts.find(row=>row.path==='/runs');assert.ok(send.key?.length>=16);assert.deepEqual(send.body,{input:'Hello Claude',session_id:'own-session',attachment_ids:[]});
await app.getByText('Streaming reply',{exact:false}).first().waitFor();
const ask=app.locator('.agent-approval-questions');await ask.waitFor();
assert.ok(await ask.getByRole('button',{name:'提交回答'}).isDisabled());
await ask.getByRole('button',{name:'Red',exact:true}).click();await ask.getByRole('button',{name:'Blue',exact:true}).click();await ask.getByRole('button',{name:'提交回答'}).click();
await ask.waitFor({state:'hidden'});
assert.deepEqual(posts.find(row=>row.path==='/runs/run-1/approval').body,{answers:{0:{answers:['Red','Blue']}},request_id:'q1'});
events=[...events,{seq:5,type:'approval.resolved',request_id:'q1'},{seq:6,type:'approval.request',request_id:'p1',kind:'approval',description:'执行命令 · Bash',command:'npm test',
  choices:[{id:'once',label:'仅允许本次'},{id:'always',label:'允许并保存 Claude 建议的规则',detail:'允许：Bash(npm test:*) · 本地项目设置'},{id:'deny',label:'拒绝'}]}];
const approval=app.locator('.agent-banner').filter({hasText:'需要确认'});await approval.getByText('npm test',{exact:true}).waitFor({timeout:20000});
await approval.getByRole('button',{name:'允许并保存 Claude 建议的规则'}).click();
await page.waitForFunction(()=>true);await approval.waitFor({state:'hidden'});
assert.deepEqual(posts.filter(row=>row.path==='/runs/run-1/approval').at(-1).body,{choice:'always',request_id:'p1'});

// New session: working directory and permission mode; no phone-tools section for Claude.
await app.getByRole('button',{name:'新建会话'}).first().click();
const create=page.locator('.ivu-modal').filter({hasText:'新建 Claude Code 会话'});await create.waitFor();
assert.equal(await create.locator('.agent-phone-options').count(),0);
await create.locator('#agent-create-cwd input, input#agent-create-cwd').first().fill('/work/project');await create.locator('#agent-create-permission').selectOption('acceptEdits');
await create.getByRole('button',{name:'创建会话'}).click();
assert.deepEqual(posts.find(row=>row.path==='/sessions').body,{title:null,cwd:'/work/project',permission_mode:'acceptEdits'});

await page.setViewportSize({width:390,height:844});await page.waitForTimeout(300);
assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no horizontal scroll on phones');
await page.screenshot({path:path.join(temp,'claude-mobile.png')});
assert.deepEqual(errors,[]);
console.log('PASS: Claude in the shared workspace — held session, takeover, tool records, unresolved acknowledge, rewind cancel, permissions, send/stream, inline questions, native suggestion approval, create, mobile');
}finally{await browser?.close();server.kill();}})().catch(e=>{console.error(e);process.exitCode=1;});
