const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function fixture(get){let options;const values=new Map();vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../WebApplication/wwwroot/js/claude.js'),'utf8'),{window:{RemoteToolApi:{get,id:encodeURIComponent}},document:{getElementById:()=>({closest:()=>null,classList:{toggle(){}}}),visibilityState:'visible'},localStorage:{setItem:(k,v)=>values.set(k,v),getItem:k=>values.get(k),removeItem:k=>values.delete(k)},Vue:{createApp:o=>{options=o;return{mount(){}};}}});const state={...options.data(),...options.methods};for(const[name,get]of Object.entries(options.computed))Object.defineProperty(state,name,{get:()=>get.call(state)});return state;}
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
test('late polling from the previous run cannot overwrite a new run in the same session',async()=>{
 const status=deferred(),events=deferred();const s=fixture(url=>url.includes('/events')?events.promise:status.promise);s.selected={id:'session'};s.run={id:'old',status:'started'};const pending=s.poll();s.accept({id:'new',session_id:'session',status:'started'});status.resolve({id:'old',status:'completed'});events.resolve({data:[],cursor:0});await pending;assert.equal(s.run.id,'new');assert.equal(s.cursor,0);
});
test('late receipt lookup cannot clear a newer unresolved submission',async()=>{
 const response=deferred();const s=fixture(()=>response.promise);s.selected={id:'session'};s.pending={key:'old'};const pending=s.checkReceipt();s.pending={key:'new'};response.resolve({id:'old-run',session_id:'session'});await pending;assert.equal(s.pending.key,'new');assert.equal(s.run,null);
});
test('selecting a chat changes the interface immediately while writes wait for its active-run read',async()=>{
 const history=deferred(),active=deferred();const s=fixture(url=>url.endsWith('/messages')?history.promise:active.promise);const selecting=s.select({id:'session',managed:true});assert.equal(s.selected.id,'session');assert.equal(s.loadingSession,true);s.draft='Do not send';await s.send();assert.equal(s.pending,null);history.resolve({data:[]});active.resolve({});await selecting;assert.equal(s.loadingSession,false);
});
