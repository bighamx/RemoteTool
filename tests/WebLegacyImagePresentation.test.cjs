const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function fixture(){
 let config;
 const context={window:{RemoteToolApi:{time:()=>'',id:encodeURIComponent}},document:{getElementById:id=>id==='agent-app'?{dataset:{agent:'hermes'}}:null},
  location:{search:''},localStorage:{getItem:()=>null},URLSearchParams,ViewUIPlus:{},
  Vue:{createApp:c=>{config=c;return {use(){},mount(){}};}}};
 vm.runInNewContext(fs.readFileSync('WebApplication/wwwroot/js/agent-workspace.js','utf8'),context);
 const state=config.data();for(const [key,fn] of Object.entries(config.methods))state[key]=fn.bind(state);
 for(const [key,fn] of Object.entries(config.computed))Object.defineProperty(state,key,{get:()=>fn.call(state)});
 return state;
}
test('accepted legacy image is displayed on one native row without acknowledging or changing history',()=>{
 const s=fixture(),at=Date.now(),picture={id:'upload',mime:'image/png'};
 s.messages=[{id:201,role:'user',content:'caption',timestamp:at/1000}];
 s.delivered=[{key:'send',input:'caption',createdAt:at,delivery:'已送达',historyIds:['200'],attachments:[picture]}];
 const rows=s.displayMessages;assert.equal(rows.length,1);assert.equal(rows[0].id,201);assert.equal(rows[0].attachments[0],picture);
 assert.equal(s.delivered[0].canonicalId,undefined);assert.equal(s.messages[0].attachments,undefined);
});
test('legacy preview cannot bind old, uncertain, mismatched or ambiguous history',()=>{
 const s=fixture(),at=Date.now(),row={key:'send',input:'caption',createdAt:at,delivery:'已送达',historyIds:['200'],attachments:[{id:'image'}]};
 const native={id:201,role:'user',content:'caption',timestamp:at/1000};
 assert.equal(s.legacyImageDisplayMatch(row,[native,{...native,id:202}],new Set()),null);
 assert.equal(s.legacyImageDisplayMatch({...row,delivery:'发送状态待核对'},[native],new Set()),null);
 assert.equal(s.legacyImageDisplayMatch(row,[{...native,id:199}],new Set()),null);
 assert.equal(s.legacyImageDisplayMatch(row,[{...native,requestKey:'other'}],new Set()),null);
 assert.equal(s.legacyImageDisplayMatch(row,[{...native,timestamp:(at+20000)/1000}],new Set()),null);
});
