// Browser UI checks with fixture API responses; never connects to a real machine or model.
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const http=require('node:http');
const {chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?path.join(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES,'playwright'):'playwright');
const root=path.join(__dirname,'../WebApplication/wwwroot');
const server=http.createServer((request,response)=>{
    const name=path.join(root,new URL(request.url,'http://localhost').pathname);
    if(!name.startsWith(root+path.sep)||!fs.existsSync(name)){response.writeHead(404);response.end();return;}
    response.setHeader('Content-Type',({'.html':'text/html','.css':'text/css','.js':'text/javascript','.mjs':'text/javascript','.svg':'image/svg+xml','.png':'image/png','.webmanifest':'application/manifest+json'})[path.extname(name)]||'text/plain');
    fs.createReadStream(name).pipe(response);
});
async function main(){
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const browser=await chromium.launch({executablePath:process.env.REMOTETOOL_TEST_CHROME||undefined,headless:true,args:['--no-sandbox']});
    try{
        const page=await browser.newPage({viewport:{width:Number(process.env.REMOTETOOL_TEST_WIDTH||390),height:844},serviceWorkers:'block',colorScheme:process.env.REMOTETOOL_TEST_COLOR_SCHEME||'light'});const errors=[];page.on('pageerror',error=>errors.push(error.message));
        let authenticated=false, runs=0, submitted=[], disconnected=false, unknown=false;
        const json=(route,value,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(value)});
        await page.route('**/api/**',async route=>{
            const req=route.request(), endpoint=new URL(req.url()).pathname;
            if(endpoint==='/api/auth/login'){authenticated=true;return json(route,{username:'fixture'});}
            if(endpoint==='/api/auth/logout'){authenticated=false;return json(route,{success:true});}
            if(!authenticated)return json(route,{message:'登录已过期'},401);
            if(endpoint==='/api/auth/me')return json(route,{authenticated:true});
            if(endpoint==='/api/platform/capabilities')return json(route,{platform:'macOS',desktopConfigured:true,permissions:{screenRecording:true,accessibility:true}});
            if(endpoint==='/api/system/info')return json(route,{data:{machineName:'测试 Mac',platform:'macOS',cpuUsagePercent:12,memoryUsagePercent:38}});
            if(endpoint==='/api/codex/projects')return json(route,{data:[]});
            if(endpoint==='/api/codex/sessions'&&req.method()==='GET')return json(route,{data:[{id:'fixture-session',title:'我的项目',cwd:'/Users/test/project',preview:'检查登录流程'}]});
            if(endpoint==='/api/codex/sessions/fixture-session')return json(route,{session:{id:'fixture-session',title:'我的项目',cwd:'/Users/test/project'}});
            if(endpoint.endsWith('/messages'))return json(route,{data:[{role:'assistant',content:'安全显示 <img src=x onerror=alert(1)>\n中文历史消息'}]});
            if(endpoint.endsWith('/active-run'))return json(route,{run_id:null});
            if(endpoint==='/api/codex/runs'&&req.method()==='POST'){
                submitted.push({key:req.headers()['idempotency-key'],body:req.postDataJSON()});if(unknown)return route.abort('failed');return json(route,{run_id:'fixture-run'});
            }
            if(endpoint==='/api/codex/runs/lookup')return json(route,{found:false});
            if(endpoint==='/api/codex/runs/fixture-run'){
                runs++;return json(route,{run_id:'fixture-run',status:runs>1?'completed':'started',seq:runs>1?2:0,output:runs>1?'流式中文回复':''});
            }
            if(endpoint.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:'id: 1\ndata: {"event":"message.delta","seq":1,"delta":"流式中文回复"}\n\n'});
            if(endpoint==='/api/system/screenshot')return route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRz0AAAAASUVORK5CYII=','base64')});
            throw new Error('Unexpected fixture API: '+endpoint);
        });
        const input=[];
        await page.routeWebSocket('**/ws/input',socket=>{socket.onMessage(message=>{input.push(JSON.parse(message));socket.send(JSON.stringify({type:'input-ack',ok:true}));});socket.onClose(()=>{disconnected=true;});});
        await page.routeWebSocket('**/ws/terminal*',socket=>socket.onMessage(message=>socket.send('fixture shell: '+message+'\n')));
        await page.goto(`http://127.0.0.1:${server.address().port}/mobile/index.html`);
        await page.locator('#login').waitFor({state:'visible'});await page.fill('#username','fixture');await page.fill('#password','fixture');await page.locator('#login-form button').click();
        await page.locator('#host').filter({hasText:'测试 Mac'}).waitFor();
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'mobile layout must fit viewport');
        await page.screenshot({path:process.env.REMOTETOOL_TEST_SCREENSHOT||'/tmp/remotetool-mobile.png',fullPage:true});
        await page.locator('[data-page="codex"]').click();
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'session list must fit viewport');
        if(process.env.REMOTETOOL_TEST_SCREENSHOT_PREFIX)await page.screenshot({path:process.env.REMOTETOOL_TEST_SCREENSHOT_PREFIX+'-sessions.png',fullPage:true});
        await page.locator('.session').click();
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'conversation must fit viewport');
        if(process.env.REMOTETOOL_TEST_SCREENSHOT_PREFIX)await page.screenshot({path:process.env.REMOTETOOL_TEST_SCREENSHOT_PREFIX+'-chat.png',fullPage:true});
        assert.equal(await page.locator('#messages img').count(),0,'messages must not inject HTML');
        await page.fill('#prompt','只读测试');await page.locator('#send').click();await page.locator('#run-state').filter({hasText:'completed'}).waitFor();
        assert.equal(submitted.length,1);assert.match(submitted[0].key,/^[a-f0-9]{32}$/);assert.equal(submitted[0].body.input,'只读测试');
        await page.locator('[data-page="desktop"]').click();await page.locator('#connect-desktop').click();await page.waitForFunction(()=>document.querySelector('#screen').naturalWidth>0);
        await page.locator('[data-shortcut="Enter"]').click();await page.fill('#remote-text','中文输入');await page.locator('#desktop-text button').click();
        await page.waitForFunction(()=>document.querySelector('#remote-text').value==='');await page.locator('#disconnect-desktop').click();
        assert(input.some(event=>event.type==='key'&&event.code==='Enter'&&event.isKeyDown));assert(input.some(event=>event.type==='text'&&event.text==='中文输入'));
        await page.locator('[data-page="terminal"]').click();await page.locator('#connect-terminal').click();await page.locator('#command').waitFor({state:'visible'});await page.waitForFunction(()=>!document.querySelector('#command').disabled);
        await page.fill('#command','pwd');await page.locator('#terminal-send').click();await page.locator('#terminal-output').filter({hasText:'fixture shell: pwd'}).waitFor();await page.locator('#disconnect-terminal').click();
        await page.locator('[data-page="codex"]').click();unknown=true;await page.fill('#prompt','结果不确定的测试');await page.locator('#send').click();await page.locator('#verify-run').waitFor({state:'visible'});
        assert.equal(await page.locator('#send').isDisabled(),true);await page.reload();await page.locator('[data-page="codex"]').click();await page.locator('#dismiss-pending').waitFor({state:'visible'});
        assert.equal(submitted.length,2,'reload must not replay POST');assert.equal(await page.locator('#send').isDisabled(),true);
        assert.deepEqual(errors,[]);console.log('PASS: mobile layout, login, Codex task, escaped history, desktop input, terminal, uncertain submission recovery');
    }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(error=>{console.error(error);server.close();process.exitCode=1;});
