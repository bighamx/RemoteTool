// Run with NODE_PATH pointing at an installed Playwright; uses only local, isolated fixtures.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const {spawn} = require('node:child_process');
const {chromium} = require('playwright');
const root = path.resolve(__dirname,'..');
const dotnet = process.env.REMOTETOOL_TEST_DOTNET || 'dotnet';

(async () => {
    const temp = fs.mkdtempSync(path.join(os.tmpdir(),'remotetool-web-parity-'));
    const probe = net.createServer();await new Promise(resolve=>probe.listen(0,'127.0.0.1',resolve));const port = probe.address().port;await new Promise(resolve=>probe.close(resolve));
    const base = `http://127.0.0.1:${port}`;
    const child = spawn(dotnet,[path.join(root,'WebApplication/bin/Debug/net8.0/WebApplication.dll')],{
        cwd:path.join(root,'WebApplication'),env:{...process.env,ASPNETCORE_URLS:base,Auth__Username:'fixture',Auth__Password:'fixture-only-password',Auth__JwtSecret:'fixture-only-signing-key-at-least-thirty-two-characters',Codex__Home:path.join(temp,'codex'),Codex__Executable:'/usr/bin/false',Codex__AccountStore:path.join(temp,'accounts'),REMOTETOOL_DATA_DIR:path.join(temp,'data'),Hangfire__Database:path.join(temp,'hangfire.db'),DOTNET_HOST_PATH:dotnet},stdio:['ignore','pipe','pipe']
    });
    let logs='';child.stdout.on('data',chunk=>logs+=chunk);child.stderr.on('data',chunk=>logs+=chunk);
    let browser;
    try {
        let ready=false;
        for(let attempt=0;attempt<100;attempt++) {try{const response=await fetch(base+'/api/device/identity');if(response.ok){ready=true;break;}}catch{}await new Promise(resolve=>setTimeout(resolve,100));}
        assert.ok(ready,'isolated server started: '+logs.slice(-2500));
        browser = await chromium.launch({headless:true,executablePath:process.env.REMOTETOOL_TEST_CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
        const context = await browser.newContext({viewport:{width:1440,height:1000},hasTouch:true});
        await context.addInitScript(()=>Object.defineProperty(navigator,'virtualKeyboard',{configurable:true,value:Object.assign(new EventTarget(),{overlaysContent:false,boundingRect:{x:0,y:0,width:0,height:0}})}));
        const anonymous=await browser.newContext();const protectedPage=await anonymous.request.get(base+'/phone-tools',{maxRedirects:0});assert.ok([302,401].includes(protectedPage.status()),'phone page requires authentication');await anonymous.close();
        const login = await context.request.post(base+'/api/auth/login',{data:{username:'fixture',password:'fixture-only-password'}});assert.ok(login.ok());
        const page=await context.newPage(), errors=[], calls=[];
        page.setDefaultTimeout(12000);
        page.on('pageerror',error=>errors.push(error.message));
        let phones=[{id:'phone-fixture',name:'Fixture Android',online:true}],connections=[],devicesMissing=false;
        const sessionCalls=[],connectionCalls=[];
        await page.route('**/api/phone/v1/**',async route=>{
            const request=route.request(),url=new URL(request.url());let result={};
            if(url.pathname.endsWith('/devices')){if(devicesMissing){await route.fulfill({status:404,json:{message:'not available'}});return;}result={devices:phones};}
            else if(url.pathname.endsWith('/tools'))result={tools:[{name:'phone_observe',description:'读取手机界面，不执行操作。'}]};
            else if(url.pathname.endsWith('/connections')){
                if(request.method()==='POST'){const body=request.postDataJSON();connectionCalls.push(body);connections=[{id:'fixture-binding',phone_id:body.phone_id,online:true,allow_advanced:body.allow_advanced,created:1794270000}];result={id:'fixture-binding',token:'fixture-token-not-real',mcp_url:base+'/api/phone/v1/bound/fixture-binding/mcp'};}
                else result={connections};
            }else if(request.method()==='DELETE'){connections=[];result={revoked:true};}
            await route.fulfill({json:result});
        });
        for(const agent of ['codex','hermes'])await page.route('**/api/'+agent+'/**',async route=>{
            const request=route.request(),url=new URL(request.url());let result={};
            if(url.pathname.endsWith('/sessions')){
                if(request.method()==='POST'){const body=request.postDataJSON();sessionCalls.push({agent,body});result={session:{id:agent+'-new',title:body.title}};}
                else result={data:[]};
            }else if(url.pathname.endsWith('/messages'))result={data:[]};
            else if(url.pathname.endsWith('/activity'))result={available:true,running:false};
            else if(url.pathname.endsWith('/projects'))result={data:[]};
            else if(url.pathname.endsWith('/schedules'))result={items:[]};
            await route.fulfill({json:result});
        });
        await page.goto(base+'/#/phone-tools');
        await page.locator('#phone-tools-app').getByRole('heading',{name:'Fixture Android',exact:true}).waitFor();
        await page.getByRole('button',{name:'可用工具',exact:true}).click();
        await page.locator('.ivu-modal:visible').locator('code').getByText('phone_observe',{exact:true}).waitFor();
        await page.locator('.ivu-modal:visible .ivu-modal-close').click();
        await page.getByRole('button',{name:'创建 MCP 连接',exact:true}).click();
        assert.equal(await page.locator('.ivu-modal:visible').getByRole('checkbox',{name:'允许高级操作',exact:true}).isChecked(),false);
        await page.locator('.ivu-modal:visible').getByRole('button',{name:'创建连接',exact:true}).click();
        const config=page.getByRole('textbox',{name:'手机 MCP 配置',exact:true});await config.waitFor();
        await page.locator('.ivu-modal').filter({hasText:'创建手机 MCP 连接'}).waitFor({state:'hidden'});
        assert.match(await config.inputValue(),/fixture-token-not-real/);assert.equal(connectionCalls[0].allow_advanced,false);
        assert.ok(await page.evaluate(()=>!JSON.stringify({...localStorage,...sessionStorage}).includes('fixture-token-not-real')));
        await page.locator('.ivu-modal:visible .ivu-modal-close').click();
        await config.waitFor({state:'hidden'});
        await page.getByRole('button',{name:'撤销连接',exact:true}).waitFor();
        await page.screenshot({path:path.join(temp,'phone-tools-desktop.png'),fullPage:true});
        page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'撤销连接',exact:true}).click();
        await page.getByText('暂无外部连接。聊天会话的手机工具在新建会话时单独选择。',{exact:true}).waitFor();
        await page.setViewportSize({width:390,height:844});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
        await page.screenshot({path:path.join(temp,'phone-tools-mobile.png'),fullPage:true});
        await page.goto(base+'/#/codex');await page.getByRole('button',{name:'新建会话',exact:true}).click();
        let modal=page.locator('.ivu-modal:visible');
        await modal.getByRole('button',{name:'无项目',exact:true}).click();
        await modal.getByLabel('连接方式',{exact:true}).selectOption('tools');
        await modal.getByLabel('在线手机',{exact:true}).getByText('Fixture Android',{exact:true}).waitFor({state:'attached'});
        await modal.getByRole('checkbox',{name:'允许高级操作',exact:true}).check();
        await page.screenshot({path:path.join(temp,'phone-chat-create-mobile.png'),fullPage:true});
        await modal.getByRole('button',{name:'创建会话',exact:true}).click();
        await page.locator('.agent-conversation-head h2').getByText('新Codex会话',{exact:true}).waitFor();
        assert.deepEqual(sessionCalls[0].body.phone_tools,{mode:'tools',phone_id:'phone-fixture',allow_advanced:true});
        phones=[];await page.goto(base+'/#/hermes');await page.getByRole('button',{name:'新建会话',exact:true}).click();
        modal=page.locator('.ivu-modal:visible');await modal.getByLabel('连接方式',{exact:true}).selectOption('description');
        await modal.getByText('手机未连接，创建时将跳过手机说明。',{exact:true}).waitFor();
        await modal.getByRole('button',{name:'创建会话',exact:true}).click();
        await page.locator('#workspace-hermes .agent-conversation-head h2').getByText('新Hermes会话',{exact:true}).waitFor();
        assert.deepEqual(sessionCalls[1].body.phone_tools,{mode:'description',phone_id:'',allow_advanced:false});
        await page.getByRole('button',{name:'新建会话',exact:true}).click();
        modal=page.locator('.ivu-modal:visible');devicesMissing=true;await modal.getByLabel('连接方式',{exact:true}).selectOption('tools');
        await modal.getByText('当前服务不支持手机能力，请更新服务端。',{exact:true}).waitFor();
        assert.ok(await modal.getByRole('button',{name:'创建会话',exact:true}).isDisabled());
        await modal.getByLabel('连接方式',{exact:true}).selectOption('off');
        assert.ok(await modal.getByRole('button',{name:'创建会话',exact:true}).isEnabled());
        await modal.getByRole('button',{name:'取消',exact:true}).click();
        assert.deepEqual(errors,[]);console.log('PASS: phone tools, MCP creation/revoke, secret storage exclusion, Codex/Hermes phone options, offline description and old-server fallback');console.log('Screenshots: '+temp);
    }catch(error){console.error(logs.slice(-1800));if(browser){const p=browser.contexts()[0]?.pages()[0];if(p){await p.screenshot({path:path.join(temp,'failure.png'),fullPage:true});console.error('Failure screenshot: '+temp+'/failure.png');}}throw error;}
    finally{await browser?.close();child.kill('SIGTERM');await new Promise(resolve=>{if(child.exitCode!==null)resolve();else child.once('exit',resolve);});}
})().catch(error=>{console.error(error);process.exitCode=1;});
