const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup() {
    let options;
    const calls=[];
    const api={id:encodeURIComponent,time:String,post:async(route,body)=>{calls.push({route,body});return {data:{items:[]}};}};
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../WebApplication/wwwroot/js/cloudflare.js'),'utf8'),{
        window:{RemoteToolApi:api},Vue:{createApp(value){options=value;return{use(){},mount(){}};}},ViewUIPlus:{},URLSearchParams,FormData:class{},confirm:()=>true
    });
    const state=Object.assign(options.data(),options.methods);state.accountId='account-1';state.zone='zone-1';
    return {state,api,calls};
}

test('DNS read-only flag accepts current and older wire formats', () => {
    const {state}=setup();assert.equal(state.dnsReadOnly({meta:{readOnly:true}}),true);assert.equal(state.dnsReadOnly({meta:{read_only:true}}),true);assert.equal(state.dnsReadOnly({meta:{}}),false);
});

test('listing rejects stale account responses and resets pagination for new filters', async () => {
    const {state}=setup();let finishOld;
    state.execute=async(op,args,account)=>state.accountId==='account-1'?new Promise(resolve=>finishOld=resolve):{items:[{id:'new'}]};
    const old=state.load();state.accountId='account-2';await state.load(true);finishOld({items:[{id:'old'}],hasMore:true});await old;
    assert.equal(state.rows[0].id,'new');assert.equal(state.hasMore,false);assert.equal(state.loading,false);
});

test('tunnel disconnect uses connector id, never an edge connection id', () => {
    const {state}=setup();
    const connector=state.connection({id:'connector',version:'2026.10',conns:[{id:'edge',colo_name:'HKG',origin_ip:'192.0.2.1'}]});
    assert.equal(connector.clientId,'connector');assert.equal(connector.locations,'HKG');
    assert.equal(state.connection({id:'edge',coloName:'HKG'}).clientId,'');
    assert.equal(state.connection({id:'edge',client_id:'connector'}).clientId,'connector');
});

test('editing tunnel routes retains advanced config and fallback settings', async () => {
    const {state,calls}=setup();state.tunnel={id:'tunnel'};
    state.tunnelConfig={originRequest:{connectTimeout:30},warp_routing:{enabled:true},ingress:[]};
    state.routes=[{hostname:'app.example.test',service:'http://localhost:8080',originRequest:{noTLSVerify:true}},{hostname:'',path:'/health',service:'http_status:200'}];
    state.fallback={service:'http_status:404',originRequest:{httpHostHeader:'fallback'}};
    await state.saveRoutes();
    const config=calls[0].body.config;
    assert.equal(config.originRequest.connectTimeout,30);assert.equal(config.warp_routing.enabled,true);assert.equal(config.ingress[0].originRequest.noTLSVerify,true);
    assert.equal(Object.hasOwn(config.ingress[1],'hostname'),false);assert.equal(config.ingress.at(-1).originRequest.httpHostHeader,'fallback');
});

test('a busy mutation cannot be submitted twice', async () => {
    const {state}=setup();let finish, count=0;
    const first=state.action(async()=>{++count;await new Promise(resolve=>finish=resolve);});
    await state.action(async()=>++count);finish();await first;assert.equal(count,1);assert.equal(state.busy,false);
});

test('R2 cursor history and directory parent retain the original object prefix', async () => {
    const {state}=setup();state.tab='r2';state.bucket='test';state.prefix='folder/deeper/';state.load=async()=>{};
    state.nextCursor='opaque';await state.turnPage(true);assert.equal(state.cursor,'opaque');assert.equal(state.page,2);
    await state.turnPage(false);assert.equal(state.cursor,'');assert.equal(state.page,1);
    await state.r2Up();assert.equal(state.prefix,'folder/');await state.r2Up();assert.equal(state.prefix,'');await state.r2Up();assert.equal(state.bucket,'');
});
