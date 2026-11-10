import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const dotnet=process.env.REMOTETOOL_TEST_DOTNET;
test('authenticated .NET API discovers one durable worker across Web host restart', {skip:!dotnet||process.platform==='win32'}, async t=>{
    const root=fs.mkdtempSync(path.join(os.tmpdir(),'claude-api-')),repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
    const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));const base=`http://127.0.0.1:${port}`;
    let host;
    const stop=async()=>{if(!host||host.exitCode!==null)return;host.kill();await new Promise(r=>host.once('exit',r));};
    t.after(async()=>{await stop();try{const worker=JSON.parse(fs.readFileSync(path.join(root,'claude-bridge/status.json')));process.kill(worker.pid);}catch{}fs.rmSync(root,{recursive:true,force:true});});
    async function start(){
        host=spawn(dotnet,[path.join(repo,'WebApplication/bin/Debug/net8.0/WebApplication.dll')],{cwd:path.join(repo,'WebApplication'),env:{...process.env,HOME:root,ASPNETCORE_URLS:base,Auth__Username:'fixture',Auth__Password:'fixture-only-password',Auth__JwtSecret:'fixture-only-signing-key-at-least-thirty-two-characters',REMOTETOOL_DATA_DIR:root,Hangfire__Database:path.join(root,'hangfire.db'),Claude__Executable:'/usr/bin/false',Claude__NodeExecutable:process.execPath},stdio:'ignore'});
        for(let i=0;i<100;i++){try{if((await fetch(base+'/api/device/identity')).ok)return;}catch{}await new Promise(r=>setTimeout(r,100));}throw new Error('Web host failed to start');
    }
    async function login(){const response=await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'fixture',password:'fixture-only-password'})});assert.equal(response.status,200);return response.headers.getSetCookie().map(x=>x.split(';')[0]).join('; ');}
    await start();assert.equal((await fetch(base+'/api/claude/setup')).status,401);
    let cookie=await login();let headers={Cookie:cookie,'Content-Type':'application/json'};
    const setup=await fetch(base+'/api/claude/setup',{headers});assert.equal(setup.status,200);assert.equal((await setup.json()).ready,false);
    const status=JSON.parse(fs.readFileSync(path.join(root,'claude-bridge/status.json')));
    const row=await(await fetch(base+'/api/claude/sessions',{method:'POST',headers,body:JSON.stringify({cwd:root,title:'API persistence'})})).json();assert.ok(row.id);
    if(process.platform!=='win32')assert.equal(fs.statSync(path.join(root,'claude-bridge/connection.json')).mode&0o777,0o600);
    await stop();await start();cookie=await login();headers={Cookie:cookie,'Content-Type':'application/json'};
    const sessions=await(await fetch(base+'/api/claude/sessions',{headers})).json();assert.ok(sessions.data.some(x=>x.id===row.id));
    assert.equal(JSON.parse(fs.readFileSync(path.join(root,'claude-bridge/status.json'))).pid,status.pid);
    const rejected=await fetch(base+'/api/claude/runs',{method:'POST',headers:{...headers,'Idempotency-Key':'api_submission_123'},body:JSON.stringify({session_id:row.id,input:'Must not send'})});assert.equal(rejected.status,409);assert.equal((await rejected.json()).delivery,'rejected');
});
