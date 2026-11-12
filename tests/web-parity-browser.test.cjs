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
        const login = await context.request.post(base+'/api/auth/login',{data:{username:'fixture',password:'fixture-only-password'}});assert.ok(login.ok());
        const page=await context.newPage(), errors=[], calls=[];
        page.setDefaultTimeout(12000);
        page.on('pageerror',error=>errors.push(error.message));
        const account={id:'fixture-account',name:'Fixture Cloudflare',accountId:'a'.repeat(32),hasToken:true,hasR2:true};
        await page.route('**/api/files/list*',route=>route.fulfill({json:{data:[{name:'手机布局验证与很长的文件名称.txt',path:'/fixture/mobile.txt',isDirectory:false,size:1200,modified:'2026-11-01T00:00:00Z'}]}}));
        await page.route('**/api/cloudflare/**',async route=>{
            const request=route.request(),url=new URL(request.url());let result;
            if(url.pathname.endsWith('/accounts')) result={data:[account]};
            else {
                const body=request.postDataJSON();calls.push(body);
                const operation=body.operation;
                const fixture={
                    zones:{items:[{id:'zone',name:'example.test'}]},
                    'dns.list':{items:[{id:'mutable',name:'app.example.test',type:'A',content:'192.0.2.1',ttl:1,proxied:true},{id:'readonly',name:'worker.example.test',type:'CNAME',content:'worker.example.test',ttl:1,meta:{read_only:true}}]},
                    'r2.buckets':{items:[{name:'fixture-bucket'}]},
                    'r2.list':{items:[{name:'hello.txt',key:'hello.txt',size:5}]},
                    'd1.list':{items:[{uuid:'database',name:'fixture-db'}]},
                    'd1.tables':[{results:[{name:'notes',type:'table'}]}],
                    'd1.schema':[{results:[{cid:0,name:'text',type:'TEXT'}]}],
                    'd1.rows':[{success:true,results:[{text:'fixture row'}]}],
                    'tunnels.list':{items:[{id:'tunnel',name:'fixture-tunnel',status:'healthy'}]},
                    'tunnels.detail':{tunnel:{id:'tunnel'},connections:[{id:'connector',version:'2026.10',conns:[{id:'edge',colo_name:'HKG'}]}]},
                    'tunnels.config':{config:{ingress:[{hostname:'app.example.test',service:'http://localhost:8080',originRequest:{noTLSVerify:true}},{service:'http_status:404'}],warp_routing:{enabled:true}}}
                };
                result={data:fixture[operation]||{ok:true}};
            }
            await route.fulfill({json:result});
        });
        await page.goto(base+'/#/');
        let releasePage;
        const pageGate=new Promise(resolve=>releasePage=resolve);
        const cloudflarePage=new RegExp(base+'/cloudflare$');
        await page.route(cloudflarePage,async route=>{await pageGate;await route.continue();});
        await page.evaluate(()=>location.hash='/cloudflare');
        await page.locator('.workspace-route-state:visible h1').getByText('Cloudflare',{exact:true}).waitFor();
        assert.equal(await page.locator('#workspace-home:visible').count(),0,'previous page disappears before target HTML arrives');
        assert.equal(await page.title(),'Cloudflare · RemoteTool');
        await page.evaluate(()=>location.hash='/');
        await page.locator('#workspace-home:visible').waitFor();releasePage();
        await page.locator('#cloudflare-app').waitFor({state:'attached'});
        assert.ok(await page.locator('#workspace-home').isVisible(),'late target cannot take over the newer route');
        await page.unroute(cloudflarePage);
        let failPage=true;
        const tunnelsPage=new RegExp(base+'/settings/auth$');
        await page.route(tunnelsPage,route=>failPage?route.fulfill({status:503,body:'fixture unavailable'}):route.continue());
        await page.evaluate(()=>location.hash='/identity');
        await page.locator('.workspace-route-state:visible [role=alert]').getByText('页面加载失败 (503)',{exact:true}).waitFor();
        assert.equal(await page.locator('#workspace-home:visible').count(),0,'failure stays on selected page');
        failPage=false;await page.locator('.workspace-route-state').getByRole('button',{name:'重试',exact:true}).click();
        await page.locator('#workspace-identity:visible').waitFor();
        await page.unroute(tunnelsPage);
        await page.goto(base+'/#/cloudflare');
        await page.getByText('app.example.test · A',{exact:true}).waitFor();
        assert.equal(await page.locator('#cloudflare-app .data-row').count(),2);
        assert.ok(await page.locator('#cloudflare-app .data-row').nth(1).getByRole('button',{name:'编辑',exact:true}).isDisabled());
        await page.locator('#cloudflare-app .data-row').first().getByRole('button',{name:'编辑',exact:true}).click();
        const visibleModal=()=>page.locator('.ivu-modal:visible');
        await visibleModal().getByText('保存记录',{exact:true}).waitFor();
        await visibleModal().getByRole('button',{name:'关闭',exact:true}).click();
        await page.getByRole('tab',{name:'R2',exact:true}).click();await page.getByText('fixture-bucket',{exact:true}).waitFor();
        await page.locator('#cloudflare-app').getByRole('button',{name:'打开',exact:true}).click();await page.getByText('hello.txt',{exact:true}).waitFor();
        await page.getByRole('tab',{name:'D1',exact:true}).click();await page.getByText('fixture-db',{exact:true}).waitFor();
        await page.getByRole('button',{name:'查看与查询',exact:true}).click();await visibleModal().getByLabel('数据表').selectOption('notes');await visibleModal().getByText('fixture row',{exact:true}).waitFor();
        await visibleModal().getByRole('button',{name:'关闭',exact:true}).click();
        await page.getByRole('tab',{name:'Tunnels',exact:true}).click();await page.getByText('fixture-tunnel',{exact:true}).waitFor();
        await page.getByRole('button',{name:'路由与连接器',exact:true}).click();await visibleModal().getByText('connector',{exact:true}).waitFor();
        await visibleModal().getByLabel('服务',{exact:true}).fill('http://localhost:9000');
        page.once('dialog',dialog=>dialog.accept());await visibleModal().getByRole('button',{name:'保存路由',exact:true}).click();
        const saved=calls.find(row=>row.operation==='tunnels.saveConfig');assert.ok(saved);assert.equal(saved.config.ingress[0].originRequest.noTLSVerify,true);assert.equal(saved.config.warp_routing.enabled,true);
        await visibleModal().getByRole('button',{name:'关闭',exact:true}).click();
        await page.setViewportSize({width:390,height:844});
        await page.getByRole('tab',{name:'DNS',exact:true}).click();await page.getByText('app.example.test · A',{exact:true}).waitFor();
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'mobile page fits viewport');
        await page.screenshot({path:path.join(temp,'cloudflare-mobile.png'),fullPage:true});
        await page.setViewportSize({width:1440,height:1000});
        const linked={id:'linked-hidden',title:'Linked hidden session',modelProvider:'company_login',model:'official-model',cwd:'/tmp/fixture'};
        const session={id:'session',title:'Fixture Codex',modelProvider:'company_login',model:'official-model',cwd:'/tmp/fixture'};
        let finishContext,finishHistory,officialQuota=false;
        const contextGate=new Promise(resolve=>finishContext=resolve);
        let historyGate=Promise.resolve();const codexRequests=[];
        await page.route('**/api/codex/**',async route=>{
            const url=new URL(route.request().url()), p=url.pathname;const detail=p.includes('/linked-hidden')?linked:session;let result={};codexRequests.push(p);
            if(p.endsWith('/context'))await contextGate;
            if(p.endsWith('/sessions'))result={data:[session]};
            else if(p.endsWith('/messages')){await historyGate;result={data:[{id:detail.id+'-message',role:'assistant',content:'History for '+detail.title,timestamp:Date.now()},{id:detail.id+'-long',role:'assistant',content:'这是手机端的布局验证。\n\n```javascript\nconst result = "'+'long-value-'.repeat(12)+'";\n```\n\n| 项目 | 状态 |\n| --- | --- |\n| 手机布局 | 已验证 |',timestamp:Date.now()},{id:detail.id+'-tools',role:'system',content:'工具 18 次 · 文件修改 0 次',timestamp:Date.now()},{id:detail.id+'-heading',role:'assistant',content:'##### '+'测'.repeat(25),timestamp:Date.now()},{id:detail.id+'-nested',role:'assistant',content:'- 父级内容\n  - '+'测'.repeat(54),timestamp:Date.now()}]};}
            else if(p.endsWith('/context'))result={context:{available:true,tokens:90500,limit:475000}};
            else if(p.endsWith('/activity'))result={available:true,running:false};
            else if(p.endsWith('/overview')) result={history:{data:[]},active_run:{},activity:{available:true,running:false},context:{},queue:{data:[]},detail};
            else if(p.endsWith('/model-options'))result={provider:'third',model:'private-model',providers:[{slug:'openai',aliases:['openai','company_login'],name:'OpenAI / ChatGPT',official_provider:true,models:[{model:'official-model'}]},{slug:'third',name:'Fixture Provider',models:[{model:'private-model'}],models_warning:'Fixture discovery warning'}]};
            else if(p.endsWith('/providers'))result=officialQuota?{provider:'company_login',official_provider:true,endpoints:[]}:{provider:'third',official_provider:false,endpoints:[{id:'third',name:'Fixture Provider',current:true,model:'private-model',auth_kind:'api_key'}]};
            else if(p.endsWith('/workspaces')||p.endsWith('/accounts'))result={current:'saved-official',data:[{id:'saved-official',workspace_name:'Saved official',email:'fixture@example.test'}]};
            else if(p.endsWith('/usage')){const usage={rateLimits:{primary:{windowDurationMins:300,usedPercent:officialQuota?46:77},secondary:{windowDurationMins:10080,usedPercent:officialQuota?49:92}}};result=p.includes('/workspaces/')?{workspace_id:'saved-official',available:true,usage}:usage;}
            else if(p.endsWith('/schedules'))result={items:[]};
            else if(p.endsWith('/projects'))result={data:[]};
            else if(p.endsWith('/session')||p.endsWith('/linked-hidden'))result=detail;
            await route.fulfill({json:result});
        });
        await page.goto(base+'/#/codex');
        await page.locator('.agent-timeline').getByText('History for Fixture Codex',{exact:true}).waitFor();
        assert.ok(!codexRequests.some(path=>path.endsWith('/model-options')),'model discovery is lazy');
        finishContext();
        await page.locator('.agent-model-chip').getByText('official-model',{exact:true}).waitFor();
        await page.locator('.agent-model-chip').click();
        const inspector=page.locator('.agent-inspector');
        await page.waitForFunction(()=>document.querySelector('datalist[id=agent-models-codex]')?.options.length>0);
        assert.equal(await inspector.getByLabel('模型',{exact:true}).inputValue(),'official-model');
        await inspector.getByLabel('模型',{exact:true}).fill('custom-model');
        assert.equal(await inspector.getByLabel('模型',{exact:true}).inputValue(),'custom-model');
        await inspector.locator('.agent-inspector-close').click();
        await page.locator('.agent-quota-summary').getByText('5 小时 · 剩余 23%',{exact:true}).waitFor();
        await page.locator('.agent-quota-summary').getByText('周额度 · 剩余 8%',{exact:true}).waitFor();
        await page.locator('.agent-quota-summary').getByText('官方额度 · Saved official',{exact:true}).waitFor();
        await page.locator('.agent-quota-summary').click();
        await page.getByText('当前使用三方 Provider：Fixture Provider',{exact:true}).waitFor();
        assert.equal(await visibleModal().getByText('当前工作空间',{exact:true}).count(),0);
        await visibleModal().getByText('已配置 API Key（尚未验证）',{exact:true}).waitFor();
        await visibleModal().getByRole('button',{name:'切换',exact:true}).waitFor();
        await page.locator('.ivu-modal:visible .ivu-modal-close').click();
        await page.goto(base+'/#/codex?session=linked-hidden');
        await page.locator('.agent-conversation-head h2').getByText('Linked hidden session',{exact:true}).waitFor();
        await page.locator('.agent-timeline').getByText('History for Linked hidden session',{exact:true}).waitFor();
        await page.waitForFunction(()=>Object.keys(sessionStorage).some(key=>key.startsWith('remotetool:history:v1:')&&key.endsWith(':linked-hidden')));
        historyGate=new Promise(resolve=>finishHistory=resolve);
        await page.reload();
        await page.locator('.agent-timeline').getByText('History for Linked hidden session',{exact:true}).waitFor();
        await page.getByText('缓存 · 正在同步最新消息',{exact:true}).waitFor();
        finishHistory();
        await page.waitForFunction(()=>![...document.querySelectorAll('[role=status]')].some(node=>node.textContent.includes('缓存 ·')));
        await page.locator('.agent-conversation-head h2').getByText('Linked hidden session',{exact:true}).waitFor();
        await page.getByRole('button',{name:'刷新会话列表',exact:true}).click();
        await page.locator('.agent-session-item.active').getByText('Linked hidden session',{exact:true}).waitFor();
        officialQuota=true;
        // Native-style mobile flow: list → chat → tools → back, including keyboard geometry.
        await page.setViewportSize({width:390,height:844});
        await page.goto(base+'/?mobile=1#/codex');
        await page.locator('.agent-sessions.mobile-open').waitFor();
        assert.ok(await page.locator('.mobile-navigation').isVisible());
        await page.waitForFunction(()=>getComputedStyle(document.querySelector('.agent-conversation')).display==='none');
        await page.screenshot({path:path.join(temp,'mobile-sessions.png'),fullPage:true});
        await page.locator('.agent-session-item').filter({hasText:'Fixture Codex'}).click();
        await page.locator('.agent-timeline').getByText('History for Fixture Codex',{exact:true}).waitFor();
        assert.ok(await page.locator('.mobile-navigation').isVisible());
        await page.locator('.mobile-quota').getByText('5 小时 · 剩余 54%',{exact:true}).waitFor();
        await page.locator('.mobile-quota').getByText('周额度 · 剩余 51%',{exact:true}).waitFor();
        await page.locator('.mobile-context-info').getByText(/90.5K \/ 475K · 19%/).waitFor();
        assert.equal(await page.locator('.agent-message-text').first().evaluate(el=>getComputedStyle(el).fontSize),'14px');
        assert.ok(await page.locator('.agent-workspace').evaluate(el=>getComputedStyle(el).getPropertyValue('--bg-primary').trim()===getComputedStyle(document.documentElement).getPropertyValue('--md-background').trim()),'chat uses the same theme tokens as the shell');
        for(const selector of ['.agent-markdown h5','.agent-markdown li ul']){
            const message=page.locator('.agent-message').filter({has:page.locator(selector)});
            assert.equal(await message.locator('.agent-message-meta').evaluate(el=>getComputedStyle(el).position),'static','complex Markdown keeps metadata below its content');
        }
        assert.equal(await page.locator('.agent-markdown h5').evaluate(el=>getComputedStyle(el).fontSize),'14px');
        assert.equal(await page.locator('.app-topbar:visible').count(),0);
        const composer=page.getByRole('textbox',{name:'消息输入',exact:true});
        await composer.fill('手机草稿');await composer.press('Enter');await composer.press('a');
        assert.equal(await composer.inputValue(),'手机草稿\na');
        await page.getByRole('button',{name:'消息工具',exact:true}).click();
        await visibleModal().getByRole('button',{name:'模型与会话设置',exact:true}).click();
        await page.locator('.agent-inspector:visible').waitFor();
        const modelBounds=await page.locator('.agent-inspector').boundingBox();assert.ok(modelBounds.x>=0&&modelBounds.x+modelBounds.width<=391);
        await page.locator('.agent-inspector-close').click();
        assert.equal(await page.getByRole('button',{name:'消息操作',exact:true}).first().textContent(),'操作');
        assert.equal(await page.locator('.from-system .mobile-message-menu').count(),0,'tool summaries have no redundant menu');
        const moreIcon=await page.locator('.mobile-more-icon').boundingBox();assert.ok(moreIcon.width<=24&&moreIcon.height<=25,'more icon has bounded dimensions');
        await page.getByRole('button',{name:'消息操作',exact:true}).first().click();
        await page.getByRole('menuitem',{name:'复制消息',exact:true}).waitFor();
        await page.getByRole('button',{name:'关闭操作菜单',exact:true}).click();
        assert.equal(await page.evaluate(()=>navigator.virtualKeyboard.overlaysContent),false,'browser keeps native keyboard avoidance');
        await composer.focus();
        await page.evaluate(()=>{Object.defineProperty(visualViewport,'height',{configurable:true,value:430});visualViewport.dispatchEvent(new Event('resize'));});
        await page.waitForFunction(()=>document.body.classList.contains('mobile-keyboard-open'));
        assert.equal(await page.locator('.mobile-navigation:visible').count(),0,'keyboard hides bottom navigation');
        const inputBounds=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(inputBounds.y+inputBounds.height<=431,'composer remains above keyboard');
        await page.screenshot({path:path.join(temp,'mobile-keyboard.png')});
        await page.evaluate(()=>{Object.defineProperty(visualViewport,'offsetTop',{configurable:true,value:80});visualViewport.dispatchEvent(new Event('scroll'));});
        await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--mobile-top')==='80px');
        const panned=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(panned.y>=80&&panned.y+panned.height<=511,'input follows iOS viewport panning');
        await page.evaluate(()=>{delete visualViewport.offsetTop;});
        await page.evaluate(()=>{delete visualViewport.height;document.activeElement.blur();visualViewport.dispatchEvent(new Event('resize'));});
        await page.waitForFunction(()=>!document.body.classList.contains('mobile-keyboard-open'));
        await composer.focus();
        await page.evaluate(()=>{navigator.virtualKeyboard.boundingRect={x:0,y:430,width:390,height:414};navigator.virtualKeyboard.dispatchEvent(new Event('geometrychange'));});
        await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--mobile-height')==='430px');
        const overlayBounds=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(overlayBounds.y+overlayBounds.height<=431,'input clears an overlay keyboard without a viewport resize');
        assert.equal(await page.locator('.mobile-navigation:visible').count(),0);
        await page.screenshot({path:path.join(temp,'mobile-overlay-keyboard.png')});
        await page.evaluate(()=>{navigator.virtualKeyboard.boundingRect={x:0,y:0,width:0,height:0};navigator.virtualKeyboard.dispatchEvent(new Event('geometrychange'));window.__fixtureInnerHeight=Object.getOwnPropertyDescriptor(window,'innerHeight');Object.defineProperty(window,'innerHeight',{configurable:true,value:430});window.dispatchEvent(new Event('resize'));});
        await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--mobile-height')==='430px');
        const layoutBounds=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(layoutBounds.y+layoutBounds.height<=431,'input clears keyboard when only innerHeight resizes');
        await page.evaluate(()=>{Object.defineProperty(window,'innerHeight',window.__fixtureInnerHeight);delete window.__fixtureInnerHeight;document.activeElement.blur();window.dispatchEvent(new Event('resize'));});
        await page.waitForFunction(()=>!document.body.classList.contains('mobile-keyboard-open'));
        await composer.focus();
        await page.evaluate(()=>Object.defineProperty(visualViewport,'height',{configurable:true,value:430}));
        await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--mobile-height')==='430px');
        const missedEvent=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(missedEvent.y+missedEvent.height<=431,'focus polling catches keyboard resize without events');
        await page.waitForTimeout(200);
        await page.evaluate(()=>{delete visualViewport.height;visualViewport.dispatchEvent(new Event('resize'));});
        await page.waitForTimeout(650);
        assert.equal(await page.locator('body.mobile-keyboard-unreported').count(),0,'closing a reported keyboard must not activate fallback while focus remains');
        await composer.blur();
        await page.waitForFunction(()=>!document.body.classList.contains('mobile-keyboard-open'));
        // Replay the device report: focused textarea, unchanged viewport, zero keyboard bounds.
        await page.setViewportSize({width:384,height:792});
        await composer.focus();
        await page.waitForFunction(()=>document.body.classList.contains('mobile-keyboard-unreported'));
        const unreported=await page.locator('.agent-compose-input-row').boundingBox();
        assert.ok(unreported.y>=0&&unreported.y+unreported.height<200,'unreported keyboard keeps the editor near the top');
        assert.equal(await page.evaluate(()=>innerHeight),792);assert.equal(await page.evaluate(()=>navigator.virtualKeyboard.boundingRect.height),0);
        assert.equal(await composer.inputValue(),'手机草稿\na');
        await page.screenshot({path:path.join(temp,'mobile-unreported-keyboard.png')});
        const finishBounds=await page.getByRole('button',{name:'完成',exact:true}).boundingBox();
        await page.mouse.move(finishBounds.x+finishBounds.width/2,finishBounds.y+finishBounds.height/2);await page.mouse.down();
        await page.waitForTimeout(200);assert.ok(await page.locator('body.mobile-keyboard-unreported').count(),'focusing composer controls must not move them before click');
        await page.mouse.up();
        await page.waitForFunction(()=>!document.body.classList.contains('mobile-keyboard-unreported'));
        const restored=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(restored.y>500,'finishing input restores bottom composer');
        await page.screenshot({path:path.join(temp,'mobile-chat.png'),fullPage:true});

        for(const width of [320,360,430]){
            await page.setViewportSize({width,height:844});
            assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'no mobile horizontal page overflow at '+width);
            const bounds=await page.locator('.agent-compose-input-row').boundingBox();assert.ok(bounds.x>=0&&bounds.x+bounds.width<=width+1);
        }
        await page.setViewportSize({width:844,height:390});
        await page.waitForFunction(()=>document.querySelector('.agent-mobile-sessions').getBoundingClientRect().width>=40);
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'landscape fits viewport');
        await page.setViewportSize({width:390,height:844});
        await page.getByRole('button',{name:'显示会话列表',exact:true}).click();
        await page.locator('.agent-sessions.mobile-open').waitFor();
        await page.locator('.agent-session-item').filter({hasText:'Fixture Codex'}).click();
        assert.equal(await composer.inputValue(),'手机草稿\na');
        await page.goBack();await page.locator('.agent-sessions.mobile-open').waitFor();
        await page.locator('[data-mobile-nav=more]').click();
        await page.getByRole('dialog',{name:'更多功能',exact:true}).waitFor();
        assert.equal(await page.locator('#mobile-tools a[href="/#/phone-tools"]').count(),0,'App phone tools stay outside the Web navigation');
        assert.ok(await page.locator('#mobile-tools img').first().evaluate(el=>document.documentElement.dataset.theme!=='dark'||getComputedStyle(el).filter.includes('invert(1)')),'dark menu icons retain contrast');
        await page.locator('#mobile-tools').getByRole('link',{name:/^Cloudflare/}).click();
        await page.getByText('app.example.test · A',{exact:true}).waitFor();
        assert.equal(await page.locator('#mobile-tools[open]').count(),0);
        assert.equal(await page.locator('[data-mobile-nav=more]').getAttribute('aria-current'),'page');
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
        await page.locator('[data-mobile-nav=more]').click();
        await page.locator('[data-mobile-action=theme]').click();
        assert.ok(await page.locator('#mobile-tools strong').first().evaluate(el=>getComputedStyle(el).color===getComputedStyle(document.querySelector('#mobile-tools h2')).color),'overflow labels match the light theme heading contrast');
        assert.ok(await page.locator('[data-mobile-action=customize]').evaluate(el=>el.scrollWidth<=el.clientWidth),'navigation editor label fits its button');
        await page.screenshot({path:path.join(temp,'mobile-more-light.png')});
        await page.locator('[data-close-tools]').click();
        await page.locator('[data-mobile-nav=files]').click();
        await page.locator('#files-list .file-row').waitFor();
        const fileCard=page.locator('#files-list .file-row').first();
        assert.equal(await fileCard.evaluate(el=>getComputedStyle(el).display),'flex');
        const fileBounds=await fileCard.boundingBox();assert.ok(fileBounds.x>=0&&fileBounds.x+fileBounds.width<=391,'file card fits phone');
        const dock=await page.locator('.file-mobile-dock').boundingBox(),navigation=await page.locator('.mobile-navigation').boundingBox();
        assert.ok(dock.y+dock.height<=navigation.y+1,'file actions stay above the full navigation bar');
        await fileCard.locator('.file-row-more').click();
        assert.ok(await page.locator('#context-menu [data-menu-action=download]').isVisible(),'file download remains accessible in row actions');
        await page.locator('#context-menu [data-close]').first().click();
        await page.locator('#file-mobile-settings-btn').click();
        assert.equal(await page.locator('#context-menu').evaluate(el=>getComputedStyle(el,'::before').display),'none','sheet scrim stays outside its contents');
        assert.ok(await page.locator('#context-menu [data-sort=name]').isVisible(),'file sorting remains accessible in view settings');
        await page.locator('#context-menu [data-sort=size]').click();
        await page.waitForFunction(()=>document.querySelector('#context-menu [data-sort=size]')?.classList.contains('active'));
        assert.equal(await page.locator('#context-menu').count(),1,'sorting replaces the sheet instead of stacking duplicate menus');
        await page.waitForFunction(()=>document.querySelector('#context-menu')?.classList.contains('open'));
        await page.locator('#context-menu').evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished.catch(()=>{}))));
        const sheetBounds=await page.locator('#context-menu').boundingBox();assert.ok(sheetBounds.x>=0&&sheetBounds.x+sheetBounds.width<=391,'file view sheet fits the phone');
        await page.screenshot({path:path.join(temp,'mobile-files-sort.png')});
        await page.locator('#workspace-topbar-title').click();
        await page.locator('#context-menu').waitFor({state:'detached'});
        await page.screenshot({path:path.join(temp,'mobile-files.png'),fullPage:true});
        await page.setViewportSize({width:1440,height:1000});
        assert.equal(await page.locator('.mobile-navigation:visible').count(),0);
        assert.ok(await page.locator('.app-sidebar').isVisible(),'desktop navigation retained');
        assert.deepEqual(errors,[],'browser has no uncaught exceptions');
        console.log('PASS: history before slow context, lazy models, reload cache before delayed network, linked session beyond page one, mounted URL navigation, Codex alias/custom-model/provider state, Razor pages, desktop/mobile Cloudflare navigation, read-only DNS, R2, D1 and tunnel route preservation; mobile list/chat/back/drafts, tools, 320–430px, landscape, keyboard resize/panning, themes and file cards');
        console.log('Screenshots: '+temp);
    } catch(error) {console.error(logs.slice(-3000));if(browser){const p=browser.contexts()[0]?.pages()[0];if(p){await p.screenshot({path:path.join(temp,'failure.png'),fullPage:true});console.error('Failure screenshot: '+temp+'/failure.png');}}throw error;}
    finally {await browser?.close();child.kill('SIGTERM');await new Promise(resolve=>{if(child.exitCode!==null)resolve();else child.once('exit',resolve);});}
})().catch(error=>{console.error(error);process.exitCode=1;});
