const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process');const {chromium}=require('playwright');
(async()=>{
const root=path.resolve(__dirname,'..'),temp=fs.mkdtempSync(path.join(os.tmpdir(),'claude-web-'));
const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));const base=`http://127.0.0.1:${port}`;
const dotnet=process.env.REMOTETOOL_TEST_DOTNET||'dotnet';const server=spawn(dotnet,[path.join(root,'WebApplication/bin/Debug/net8.0/WebApplication.dll')],{cwd:path.join(root,'WebApplication'),env:{...process.env,ASPNETCORE_URLS:base,Auth__Username:'fixture',Auth__Password:'fixture-only-password',Auth__JwtSecret:'fixture-only-signing-key-at-least-thirty-two-characters',REMOTETOOL_DATA_DIR:path.join(temp,'data'),Hangfire__Database:path.join(temp,'hangfire.db'),Claude__Enabled:'false'},stdio:'ignore'});
let browser;
try{
for(let i=0;i<100;i++){try{if((await fetch(base+'/api/device/identity')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
browser=await chromium.launch({headless:true,executablePath:process.env.REMOTETOOL_TEST_CHROME||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const context=await browser.newContext({viewport:{width:1440,height:1000}});const page=await context.newPage();page.setDefaultTimeout(10000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
assert.equal((await context.request.get(base+'/api/claude/sessions')).status(),401);
assert.ok((await context.request.post(base+'/api/auth/login',{data:{username:'fixture',password:'fixture-only-password'}})).ok());
assert.equal((await context.request.get(base+'/api/claude/setup')).status(),503);
const external={id:'native',title:'Terminal history',cwd:'/project',managed:false},managed={id:'managed',title:'Managed session',cwd:'/project',permission_mode:'default',managed:true};let sessions=[external],run=null,posts=0,postedKeys=[],replied=[],failSend=false,receipt=null;
await page.route('**/api/claude/**',async route=>{const request=route.request(),url=new URL(request.url()),p=url.pathname;let result={};
if(p.endsWith('/setup'))result={ready:true,message:'官方 Claude Code 已登录'};
else if(p.endsWith('/sessions')&&request.method()==='POST'){const body=request.postDataJSON();assert.equal(body.permission_mode,'default');sessions=[managed,...sessions];result=managed;}
else if(p.endsWith('/sessions'))result={data:sessions};
else if(p.endsWith('/messages'))result={data:p.includes('/native/')?[{id:'old',role:'user',content:'<img src=x onerror=alert(1)>'}]:[]};
else if(p.endsWith('/active-run'))result=p.includes('/managed/')?(run||{}):{};
else if(p.endsWith('/runs')&&request.method()==='POST'){posts++;postedKeys.push(request.headers()['idempotency-key']);if(failSend){receipt=null;await route.abort();return;}run={id:'run',session_id:'managed',status:'waiting',sequence:2,request:{id:'permission',tool:'Bash',input:{command:'echo fixture'}}};receipt=run;result=run;}
else if(p.includes('/receipts/'))result=receipt||{pending:true};
else if(p.endsWith('/reply')){replied.push(request.postDataJSON());run={...run,status:'started',request:null};result={accepted:true};}
else if(p.endsWith('/stop')){run={...run,status:'cancelled',request:null};result=run;}
else if(p.endsWith('/events')){const after=Number(url.searchParams.get('after'));result={data:after?[]:[{seq:1,type:'user',data:{text:'Hello'}},{seq:2,type:'stream',data:{type:'content_block_delta',delta:{type:'text_delta',text:'Streaming answer'}}}],cursor:2,status:run?.status};}
else result=run||{};
await route.fulfill({json:result});});
await page.goto(base+'/agents/claude');await page.getByRole('button',{name:/Terminal history/}).click();await page.getByText('<img src=x onerror=alert(1)>',{exact:true}).waitFor();assert.equal(await page.locator('.claude-history img').count(),0);assert.equal(await page.getByRole('button',{name:'发送',exact:true}).count(),0);
await page.locator('.claude-sessions').getByRole('button',{name:'新建',exact:true}).click();const dialog=page.getByRole('dialog',{name:'新建 Claude 会话'});await dialog.getByLabel('本机项目绝对路径').fill('/project');await dialog.getByRole('button',{name:'创建',exact:true}).click();
await page.getByRole('textbox',{name:'发送给 Claude'}).fill('Hello');await page.getByRole('button',{name:'发送',exact:true}).click();await page.getByRole('button',{name:'允许本次',exact:true}).waitFor();assert.equal(posts,1);assert.ok(postedKeys[0]?.length>=16);await page.getByRole('button',{name:'允许本次',exact:true}).click();assert.deepEqual(replied[0],{request_id:'permission',allow:true,answers:{}});
await page.getByText('Streaming answer',{exact:true}).waitFor();
run={...run,status:'waiting',request:{id:'question',tool:'AskUserQuestion',input:{questions:[{question:'Which?',multiSelect:true,options:[{label:'A'},{label:'B'}]}]}}};
await page.getByLabel('Which?',{exact:true}).waitFor();await page.getByRole('button',{name:'A',exact:true}).click();await page.getByRole('button',{name:'B',exact:true}).click();await page.getByRole('button',{name:'提交回答',exact:true}).click();assert.deepEqual(replied[1],{request_id:'question',allow:true,answers:{'Which?':'A, B'}});
await page.getByRole('button',{name:'停止任务',exact:true}).click();await page.getByText('已停止',{exact:true}).waitFor();
failSend=true;await page.getByRole('textbox',{name:'发送给 Claude'}).fill('Uncertain');await page.getByRole('button',{name:'发送',exact:true}).click();await page.getByRole('button',{name:'核对发送回执',exact:true}).waitFor();assert.equal(posts,2);assert.ok(await page.getByRole('button',{name:'发送',exact:true}).isDisabled());
await page.goto(base+'/#/claude');await page.getByRole('button',{name:/Managed session/}).click();await page.locator('.claude-chat').getByText('尚未找到接收回执，请稍后核对；未自动重发。',{exact:true}).waitFor();assert.equal(posts,2,'reload does not replay uncertain input');
// Read-only receipt reconciliation restores the existing accepted task, with no POST.
receipt={...run,status:'completed'};await page.getByRole('button',{name:'核对发送回执',exact:true}).click();await page.getByRole('button',{name:'核对发送回执',exact:true}).waitFor({state:'hidden'});assert.equal(posts,2);
await page.setViewportSize({width:384,height:820});await page.locator('.claude-back').click();await page.getByRole('button',{name:/Managed session/}).click();await page.getByRole('textbox',{name:'发送给 Claude'}).fill('Mobile draft');await page.getByRole('textbox',{name:'发送给 Claude'}).focus();await page.setViewportSize({width:384,height:460});
await page.waitForFunction(()=>{const box=document.querySelector('.claude-composer textarea')?.getBoundingClientRect();return box&&box.bottom<=innerHeight;},{},{timeout:5000});
const box=await page.getByRole('textbox',{name:'发送给 Claude'}).boundingBox();assert.ok(box&&box.y+box.height<=460,'composer stays in resized viewport');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
await page.screenshot({path:path.join(temp,'claude-mobile.png')});assert.deepEqual(errors,[]);console.log('PASS: Claude native read-only history, safe rendering, create, stream, approval, stop, uncertain send/reload/receipt, mobile keyboard layout');console.log('Screenshot: '+path.join(temp,'claude-mobile.png'));
}finally{await browser?.close();server.kill();}})().catch(e=>{console.error(e);process.exitCode=1;});
