const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const sandbox={window:{},document:{addEventListener(){}}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../WebApplication/wwwroot/js/mobile-navigation.js'),'utf8'),sandbox);
const {normalize,move}=sandbox.window.RemoteToolMobileNavigation;
const ids=['home','system','desktop','codex','hermes','files','terminal','cloudflare'];const items=ids.map(id=>({id}));
const plain=v=>Array.from(v);
test('five default destinations precede overflow, and each destination occurs once',()=>{
 const result=plain(normalize(null,items));assert.deepEqual(result.slice(0,5),['system','desktop','codex','hermes','files']);assert.equal(new Set(result).size,items.length);assert.equal(result.length,items.length);
});
test('saved priorities survive new features, unknown entries and corrupted storage shapes',()=>{
 const result=plain(normalize(['terminal','codex','codex','removed','<script>',null,{},'home'],[...items,{id:'claude'}]));assert.deepEqual(result.slice(0,3),['terminal','codex','home']);assert.equal(result.at(-1),'claude');assert.equal(result.length,items.length+1);assert.deepEqual(plain(normalize({bad:'value'},items)),plain(normalize(null,items)));
});
test('reordering across the overflow boundary keeps all destinations and does not mutate saved order',()=>{
 const input=plain(normalize(null,items));const moved=plain(move(input,input.indexOf('cloudflare'),4));assert.equal(moved[4],'cloudflare');assert.equal(moved[5],'files');assert.deepEqual(input.slice(0,5),['system','desktop','codex','hermes','files']);assert.deepEqual(plain(move(input,-1,2)),input);assert.equal(plain(move(input,0,999)).at(-1),'system');
});
