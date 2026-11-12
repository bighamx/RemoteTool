const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process');const {chromium}=require('playwright');
(async()=>{
const root=path.resolve(__dirname,'..'),temp=fs.mkdtempSync(path.join(os.tmpdir(),'mobile-navigation-'));
const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));const base=`http://127.0.0.1:${port}`;
const server=spawn(process.env.REMOTETOOL_TEST_DOTNET||'dotnet',[path.join(root,'WebApplication/bin/Debug/net8.0/WebApplication.dll')],{cwd:path.join(root,'WebApplication'),env:{...process.env,ASPNETCORE_URLS:base,Auth__Username:'fixture',Auth__Password:'fixture-only-password',Auth__JwtSecret:'fixture-only-signing-key-at-least-thirty-two-characters',REMOTETOOL_DATA_DIR:path.join(temp,'data'),Codex__Home:path.join(temp,'codex'),Codex__Executable:'/usr/bin/false',Hangfire__Database:path.join(temp,'hangfire.db')},stdio:'ignore'});
let browser;
try{
for(let i=0;i<100;i++){try{if((await fetch(base+'/api/device/identity')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const context=await browser.newContext({viewport:{width:384,height:820},hasTouch:true});
assert.ok((await context.request.post(base+'/api/auth/login',{data:{username:'fixture',password:'fixture-only-password'}})).ok());
const page=await context.newPage();page.setDefaultTimeout(10000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
const key='remotetool:web:mobile-navigation:v1';
const ids=()=>page.locator('.mobile-navigation > [data-mobile-nav]').evaluateAll(nodes=>nodes.map(n=>n.dataset.mobileNav));
const initial=['system','desktop','codex','hermes','files','more'];
const editor=page.getByRole('dialog',{name:'自定义底部导航',exact:true});
async function edit(){await page.locator('[data-mobile-nav=more]').click();await page.getByRole('button',{name:'编辑导航',exact:true}).click();await editor.waitFor();}
await page.goto(base+'/#/');await page.waitForFunction(()=>document.querySelectorAll('.mobile-navigation > [data-mobile-nav]').length===6);assert.deepEqual(await ids(),initial);
await edit();await editor.getByRole('button',{name:'将Cloudflare加入底栏',exact:true}).click();await editor.getByRole('button',{name:'上移Cloudflare',exact:true}).click();
const cloud=editor.locator('[data-nav-id=cloudflare]'),first=editor.locator('li').first();const handle=await cloud.getByRole('button',{name:'拖动Cloudflare调整顺序',exact:true}).boundingBox(),target=await first.boundingBox();
const touch=await context.newCDPSession(page);await touch.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:handle.x+handle.width/2,y:handle.y+handle.height/2}]});for(let step=1;step<=8;step++)await touch.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:handle.x+handle.width/2,y:handle.y+handle.height/2+(target.y+2-handle.y-handle.height/2)*step/8}]});await touch.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await touch.detach();
assert.equal(await editor.locator('li').first().getAttribute('data-nav-id'),'cloudflare');
await page.screenshot({path:path.join(temp,'navigation-editor-mobile.png')});
await editor.getByRole('button',{name:'保存',exact:true}).click();assert.deepEqual(await ids(),['cloudflare','system','desktop','codex','hermes','more']);
assert.ok(await page.evaluate(key=>JSON.parse(localStorage.getItem(key))[0]==='cloudflare',key));
await page.reload();await page.waitForFunction(()=>document.querySelector('.mobile-navigation > a')?.dataset.mobileNav==='cloudflare');assert.equal((await ids()).at(-1),'more');
await page.locator('[data-mobile-nav=cloudflare]').click();await page.waitForFunction(()=>location.hash==='#/cloudflare');await page.waitForFunction(()=>document.querySelector('[data-mobile-nav=cloudflare]')?.getAttribute('aria-current')==='page');
await page.locator('[data-mobile-nav=more]').click();assert.equal(await page.locator('#mobile-tools [data-mobile-overflow=cloudflare]').count(),0);await page.locator('#mobile-tools [data-mobile-overflow=home]').click();assert.equal(await page.locator('#mobile-tools').getAttribute('open'),null);await page.waitForFunction(()=>document.querySelector('[data-mobile-nav=more]')?.getAttribute('aria-current')==='page');
await edit();await editor.getByRole('button',{name:'恢复默认',exact:true}).click();await editor.getByRole('button',{name:'取消',exact:true}).click();assert.equal((await ids())[0],'cloudflare','cancelling reset keeps saved priorities');
// Storage updates from a second tab refresh navigation without reloading the open page.
const other=await context.newPage();await other.goto(base+'/#/');await other.evaluate(key=>localStorage.setItem(key,JSON.stringify(['files','codex','hermes','system','desktop'])),key);await page.waitForFunction(()=>document.querySelector('.mobile-navigation > a')?.dataset.mobileNav==='files');await other.close();
await page.setViewportSize({width:320,height:720});await edit();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
await editor.getByRole('button',{name:'上移Codex',exact:true}).click();
await page.evaluate(()=>{window.restoreStorageSetItem=Storage.prototype.setItem;Storage.prototype.setItem=function(key,value){if(key==='remotetool:web:mobile-navigation:v1')throw new Error('blocked');return window.restoreStorageSetItem.call(this,key,value);};});
await editor.getByRole('button',{name:'保存',exact:true}).click();await editor.getByText('无法保存到当前浏览器，请检查隐私或存储设置',{exact:true}).waitFor();assert.equal((await ids())[0],'files');
await page.evaluate(()=>Storage.prototype.setItem=window.restoreStorageSetItem);await editor.getByRole('button',{name:'恢复默认',exact:true}).click();await editor.getByRole('button',{name:'保存',exact:true}).click();assert.deepEqual(await ids(),initial);
await page.screenshot({path:path.join(temp,'navigation-six-tabs-320.png')});
await page.evaluate(key=>localStorage.setItem(key,'{broken'),key);await page.reload();await page.waitForFunction(()=>document.querySelectorAll('.mobile-navigation > a').length===5);assert.deepEqual(await ids(),initial);assert.deepEqual(errors,[]);
console.log('PASS: six tabs, fixed More, touch-compatible drag, promote/up/down, overflow routing, save/reload/reset/cancel, cross-tab sync, storage failure, corrupt preferences and 320px layout');console.log('Screenshots: '+temp);
}finally{await browser?.close();server.kill();}})().catch(e=>{console.error(e);process.exitCode=1;});
