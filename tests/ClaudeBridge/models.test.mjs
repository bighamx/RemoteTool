import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {ClaudeModelDiscovery, mergeModelCatalog} from '../../WebApplication/Tools/ClaudeBridge/models.mjs';

test('model discovery uses the endpoint credentials, coalesces reads, refreshes and isolates providers', async () => {
    let hits=0, fail=false;
    const paths=[],headers=[];
    const server=http.createServer((req,res)=>{
        hits++;paths.push(req.url);headers.push(req.headers);
        res.writeHead(fail?401:200,{'content-type':'application/json'});
        res.end(JSON.stringify(fail?{error:'secret-response-must-not-leak'}:{data:[
            {id:'new-model',display_name:'New model'},{id:'new-model'},{id:'other-model',api_key:'never-return-this'}]}));
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    try {
        const base=`http://127.0.0.1:${server.address().port}`,discovery=new ClaudeModelDiscovery();
        const env={ANTHROPIC_AUTH_TOKEN:'fixture-token'};
        const [a,b]=await Promise.all([discovery.read(base+'/gateway',env),discovery.read(base+'/gateway',env)]);
        assert.equal(hits,1);assert.deepEqual(a,b);
        assert.equal(paths[0],'/gateway/v1/models');
        assert.equal(headers[0].authorization,'Bearer fixture-token');
        assert.equal(headers[0]['x-api-key'],'fixture-token');
        assert.deepEqual(a.models.map(row=>row.model),['new-model','other-model']);
        assert.ok(!JSON.stringify(a).includes('never-return-this'));
        await discovery.read(base+'/gateway',env);assert.equal(hits,1);
        await discovery.read(base+'/gateway',env,true);assert.equal(hits,2);
        await discovery.read(base+'/v1',{ANTHROPIC_API_KEY:'another-provider'});assert.equal(hits,3);
        assert.equal(paths.at(-1),'/v1/models');
        fail=true;
        const stale=await discovery.read(base+'/gateway',env,true);
        assert.deepEqual(stale.models,a.models);assert.ok(stale.warning);
        assert.ok(!JSON.stringify(stale).includes('secret-response-must-not-leak'));
        const invalid=await discovery.read(base+'/gateway',{ANTHROPIC_AUTH_TOKEN:'different-key'});
        assert.deepEqual(invalid.models,[],'changed credentials cannot reuse another credential catalog');
        assert.deepEqual(mergeModelCatalog([{model:'new-model',displayName:'configured alias'}],a.models),[
            {model:'new-model',displayName:'configured alias'},a.models[1]]);
    } finally {await new Promise(resolve=>server.close(resolve));}
});

test('discovery does not follow redirects with credentials', async () => {
    const discovery=new ClaudeModelDiscovery(async (_url,options)=>{
        assert.equal(options.redirect,'error');throw new Error('redirect to an untrusted endpoint');
    });
    const result=await discovery.read('http://localhost:1',{ANTHROPIC_API_KEY:'fixture'});
    assert.deepEqual(result.models,[]);assert.ok(result.warning);
});

test('Anthropic pagination stays on the same endpoint and merges all pages', async () => {
    let count=0;
    const discovery=new ClaudeModelDiscovery(async (url,options)=>{
        assert.equal(url.origin,'http://localhost:1234');
        assert.equal(url.pathname,'/gw/claude/v1/models');
        assert.equal(options.headers['x-api-key'],'fixture');
        count++;
        assert.equal(url.searchParams.get('after_id'),count===1?null:'page-one');
        return {ok:true,json:async()=>({data:[{id:count===1?'one':'two'}],has_more:count===1,last_id:'page-one'})};
    });
    const result=await discovery.read('http://localhost:1234/gw/claude',{ANTHROPIC_API_KEY:'fixture'});
    assert.deepEqual(result.models.map(row=>row.model),['one','two']);assert.equal(count,2);
});
