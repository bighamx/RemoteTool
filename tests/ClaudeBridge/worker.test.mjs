import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {randomBytes} from 'node:crypto';

test('real worker binds only loopback, protects its API and never exposes credentials in errors', {skip:process.platform==='win32'}, async t=>{
    const root=fs.mkdtempSync(path.join(os.tmpdir(),'claude-worker-'));
    const token=randomBytes(32).toString('hex');fs.writeFileSync(path.join(root,'connection.json'),JSON.stringify({token}));
    const worker=spawn(process.execPath,[fileURLToPath(new URL('../../WebApplication/Tools/ClaudeBridge/server.mjs',import.meta.url))],{env:{...process.env,HOME:root,REMOTETOOL_CLAUDE_STATE:root,REMOTETOOL_CLAUDE_EXECUTABLE:'/usr/bin/false'},stdio:'ignore'});
    t.after(async()=>{worker.kill();await new Promise(r=>{if(worker.exitCode!==null||worker.signalCode)r();else worker.once('exit',r);});fs.rmSync(root,{recursive:true,force:true});});
    let status;for(let i=0;i<100;i++){try{status=JSON.parse(fs.readFileSync(path.join(root,'status.json')));break;}catch{}await new Promise(r=>setTimeout(r,50));}
    assert.ok(status?.port);const base=`http://127.0.0.1:${status.port}`;
    assert.equal((await fetch(base+'/health')).status,401);
    const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
    assert.equal((await (await fetch(base+'/health',{headers})).json()).protocol,1);
    const setup=await(await fetch(base+'/setup',{headers})).json();assert.equal(setup.ready,false);assert.ok(!JSON.stringify(setup).includes(token));
    const created=await(await fetch(base+'/sessions',{method:'POST',headers,body:JSON.stringify({cwd:root})})).json();assert.ok(created.id);
    const rejected=await fetch(base+'/runs',{method:'POST',headers:{...headers,'Idempotency-Key':'worker_submission_123'},body:JSON.stringify({session_id:created.id,input:'Do not send'})});assert.equal(rejected.status,409);
    const receipt=await(await fetch(base+'/receipts/worker_submission_123',{headers})).json();assert.equal(receipt.pending,true);
    const list=await(await fetch(base+'/sessions',{headers})).json();assert.equal(list.data.length,1);
    const events=await fetch(base+'/runs/not-found/events?after=-1',{headers});assert.equal(events.status,400);
    // A second Web host can rediscover the same worker without cancelling runs.
    const again=await(await fetch(base+'/health',{headers})).json();assert.equal(again.pid,status.pid);
});
