const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function fixture(mobile=true){
 const listeners=new Map(),elements=new Map(),posts=[],refreshes=[],toasts=[];
 function element(id=''){
  return {id,style:{},hidden:false,disabled:false,textContent:'',innerHTML:'',children:[],events:{},classList:{toggle(){}},
   addEventListener(name,fn){this.events[name]=fn;},appendChild(child){this.children.push(child);},querySelectorAll(){return [];},remove(){elements.delete(id);}};
 }
 for(const id of ['file-picker-dialog','picker-dialog-title','picker-description','picker-message','picker-confirm-btn','picker-close-btn','picker-cancel-btn','picker-home-btn','picker-back-btn','picker-refresh-btn','picker-breadcrumb','picker-files-list','picker-loading','picker-empty'])elements.set(id,element(id));
 const buttons=['picker-confirm-btn','picker-close-btn','picker-cancel-btn','picker-home-btn','picker-back-btn','picker-refresh-btn'].map(id=>elements.get(id));
 elements.get('file-picker-dialog').querySelectorAll=()=>buttons;
 const location={href:'http://fixture/#/files?path=%2Fsource'};
 const entries=[{state:{route:'files'},url:location.href}];let index=0;
 const history={get state(){return entries[index].state;},pushState(state,_,url){entries.splice(++index);entries[index]={state,url};location.href=url;},back(){queueMicrotask(()=>{if(index>0){index--;location.href=entries[index].url;for(const fn of listeners.get('popstate')||[])fn();}});}};
 let fetcher=async(url,options)=>options?.method==='POST'?{ok:true,json:async()=>({success:true,message:'完成',data:{success:1,fail:0}})}:{ok:true,json:async()=>({success:true,data:[{path:'/destination',name:'目标',isDirectory:true},{path:'/ignored.txt',name:'文件',isDirectory:false}]})};
 const context={window:{addEventListener(name,fn){listeners.set(name,[...(listeners.get(name)||[]),fn]);},RemoteToolOnReady(){}},document:{getElementById:id=>elements.get(id)||null,createElement:()=>element()},history,location,console,Date,Math,Promise,Set,Map,fetch:async(url,options)=>{if(options?.method==='POST')posts.push({url,body:JSON.parse(options.body)});return fetcher(url,options);}};
 vm.runInNewContext(fs.readFileSync('WebApplication/wwwroot/js/remote.js','utf8')+'\nthis.TestRemoteControl=RemoteControl;',context);
 const state=Object.assign(Object.create(context.TestRemoteControl.prototype),{token:'fixture',currentPath:'/source',files:[{path:'/source/a.txt',isDirectory:false},{path:'/source/folder',isDirectory:true}],selectedFiles:new Set(['/source/a.txt','/source/folder']),pickerCurrentPath:null,pickerLoadEpoch:0,pickerSubmitting:false,pickerReady:false,filePanelHistoryId:'fixture-panel',filePanelClosing:null});
 state.isPhoneFileLayout=()=>mobile;state.setRefreshState=()=>{};state.renderPickerList=rows=>{state.rendered=rows;};state.loadFiles=async path=>refreshes.push(path);state.showToast=(message,kind)=>toasts.push({message,kind});
 state.initFilePicker();
 return {state,elements,posts,refreshes,toasts,history,location,entries,setFetch(fn){fetcher=fn;},settle:()=>new Promise(setImmediate)};
}

for(const [type,destination] of [['copy','/'],['move','C:/'],['copy','//server/share/target']])test(`${type} uses the selected folder verbatim (${destination})`,async()=>{
 const h=fixture(),s=h.state;s.selectFileDestination(type,'/source/a.txt',false);await h.settle();
 assert.equal(s.pickerOptions.selectMode,'folder');assert.equal(s.rendered.length,1);assert.equal(h.posts.length,0);
 await s.loadPickerFiles(destination);await s.confirmFilePicker();
 assert.equal(h.posts.length,1);assert.equal(h.posts[0].url,`/api/files/${type}-batch`);assert.equal(h.posts[0].body.DestPath,destination);
 assert.deepEqual(h.posts[0].body.Items,[{Path:'/source/a.txt',IsDirectory:false}]);assert.equal(h.posts[0].body.Overwrite,type==='copy'?true:undefined);
 assert.equal(s.pickerOptions,null);assert.equal(h.location.href,'http://fixture/#/files?path=%2Fsource');
});

test('batch choice snapshots sources and types before selection changes',async()=>{
 const h=fixture(),s=h.state;s.handleMultiCopy();s.selectedFiles.clear();s.files=[];await h.settle();await s.loadPickerFiles('/destination');await s.confirmFilePicker();
 assert.deepEqual(h.posts[0].body.Items,[{Path:'/source/a.txt',IsDirectory:false},{Path:'/source/folder',IsDirectory:true}]);
});

test('duplicate confirmation submits once and Back cannot navigate during the request',async()=>{
 const h=fixture(),s=h.state;let finish;
 h.setFetch(async(_,options)=>options?.method==='POST'?await new Promise(resolve=>finish=resolve):{ok:true,json:async()=>({data:[]})});
 s.selectFileDestination('move','/source/a.txt',false);await h.settle();const pending=s.confirmFilePicker();await s.confirmFilePicker();
 assert.equal(h.posts.length,1);assert.equal(h.elements.get('picker-cancel-btn').disabled,true);h.history.back();await h.settle();assert.ok(h.history.state.remoteToolFilePanel);
 finish({ok:true,json:async()=>({success:true,data:{success:1,fail:0}})});await pending;assert.equal(s.pickerOptions,null);
});

test('lost transfer receipt stays explicit and cannot be replayed',async()=>{
 const h=fixture(),s=h.state;h.setFetch(async(_,options)=>{if(options?.method==='POST')throw Error('disconnected');return {ok:true,json:async()=>({data:[]})};});
 s.selectFileDestination('copy','/source/a.txt',false);await h.settle();await s.confirmFilePicker();await s.confirmFilePicker();
 assert.equal(h.posts.length,1);assert.match(h.elements.get('picker-message').textContent,/结果未确认/);assert.equal(h.elements.get('picker-confirm-btn').disabled,true);assert.equal(h.elements.get('picker-cancel-btn').disabled,false);await s.closeFilePicker();
});

test('all failed items remain in the picker while partial completion warns without replay',async()=>{
 const h=fixture(),s=h.state;let successes=0;
 h.setFetch(async(_,options)=>options?.method==='POST'?{ok:true,json:async()=>({success:true,message:'部分失败',data:{success:successes,fail:1}})}:{ok:true,json:async()=>({data:[]})});
 s.handleMultiMove();await h.settle();await s.confirmFilePicker();assert.ok(s.pickerOptions);assert.match(h.elements.get('picker-message').textContent,/部分失败/);
 successes=1;await s.confirmFilePicker();assert.equal(s.pickerOptions,null);assert.equal(h.toasts.at(-1).kind,'warning');
});

test('latest folder request wins and failed or virtual-root listings cannot be confirmed',async()=>{
 const h=fixture(),s=h.state;s.selectFileDestination('copy','/source/a.txt',false);await h.settle();let finish;
 h.setFetch(async url=>url.includes('old')?await new Promise(resolve=>finish=resolve):{ok:true,json:async()=>({data:[{path:'/new/child',isDirectory:true}]})});
 const old=s.loadPickerFiles('/old');await s.loadPickerFiles('/new');finish({ok:true,json:async()=>({data:[{path:'/old/child',isDirectory:true}]})});await old;
 assert.equal(s.pickerCurrentPath,'/new');assert.equal(s.rendered[0].path,'/new/child');
 await s.loadPickerFiles(null);assert.equal(h.elements.get('picker-confirm-btn').disabled,true);await s.confirmFilePicker();assert.equal(h.posts.length,0);
 h.setFetch(async()=>({ok:false,status:403}));await s.loadPickerFiles('/denied');assert.equal(s.pickerReady,false);assert.equal(h.elements.get('picker-confirm-btn').disabled,true);
});

test('Back closes the operation panel or picker without changing the file route; cancel consumes its entry',async()=>{
 const h=fixture(),s=h.state;h.elements.set('context-menu',{remove(){h.elements.delete('context-menu');}});s.startFilePanelHistory();h.history.back();await h.settle();
 assert.equal(h.elements.has('context-menu'),false);assert.equal(h.location.href,'http://fixture/#/files?path=%2Fsource');
 s.selectFileDestination('copy','/source/a.txt',false);await h.settle();h.history.back();await h.settle();assert.equal(s.pickerOptions,null);assert.equal(h.posts.length,0);
 s.selectFileDestination('move','/source/a.txt',false);await h.settle();await s.closeFilePicker();assert.equal(h.history.state.remoteToolFilePanel,undefined);assert.equal(h.posts.length,0);
});

test('breadcrumb and Up preserve Unix, drive and UNC roots',async()=>{
 const h=fixture(false),s=h.state;s.selectFileDestination('copy','/source/a.txt',false);await h.settle();const paths=[];s.loadPickerFiles=async path=>paths.push(path);
 s.updatePickerBreadcrumb('/var/data');h.elements.get('picker-breadcrumb').children.at(-1).events.click();assert.equal(paths.pop(),'/var/data');
 s.updatePickerBreadcrumb('//server/share/data');h.elements.get('picker-breadcrumb').children.at(-1).events.click();assert.equal(paths.pop(),'//server/share/data');
 for(const [path,parent] of [['/var','/'],['/var/data/','/var'],['C:/',null],['//server/share',null]]){s.pickerCurrentPath=path;s.pickerNavigateBack();assert.equal(paths.pop(),parent);}
});

test('existing folder callbacks run after the picker closes and file mode retains files',async()=>{
 const h=fixture(false),s=h.state;let selected;
 await s.openFilePicker({selectMode:'folder',initialPath:'/target',onSelect:path=>{assert.equal(s.pickerOptions,null);selected=path;}});await h.settle();await s.confirmFilePicker();assert.equal(selected,'/target');
 await s.openFilePicker({selectMode:'file',initialPath:'/file.txt',onSelect(){}});await h.settle();assert.equal(s.pickerCurrentPath,'/');assert.equal(s.rendered.length,2);assert.equal(h.elements.get('picker-confirm-btn').hidden,true);
});

test('actual file-row click previews media and keeps selection and menu clicks separate',()=>{
 const h=fixture(false),s=h.state,previews=[];
 const row={dataset:{path:'/source/photo.png',name:'photo.png',isDirectory:'false'},events:{},classList:{contains:()=>false},querySelector:()=>null,addEventListener(name,fn){this.events[name]=fn;}};
 const tbody={innerHTML:'',querySelectorAll:selector=>selector==='tr.file-row'?[row]:[]};h.elements.set('files-list',tbody);
 s.files=[{path:row.dataset.path,name:row.dataset.name,isDirectory:false,size:12}];s.fileSortColumn=null;s.multiSelectMode=false;s.escapeHtml=value=>value;s.previewFile=(path,name)=>previews.push({path,name});
 s.updateMultiSelectToolbar=()=>{};s.updateFilesToolbarState=()=>{};s.updateFileMobileChrome=()=>{};
 s.renderFilesList();const event={target:{closest:()=>null,classList:{contains:()=>false}}};row.events.click(event);assert.equal(previews.length,1);
 s.multiSelectMode=true;row.events.click(event);assert.equal(previews.length,1);assert.ok(s.selectedFiles.has(row.dataset.path));
 s.multiSelectMode=false;row.events.click({target:{closest:()=>({})}});assert.equal(previews.length,1);
 row.dataset.name='movie.mp4';row.events.click(event);assert.equal(previews.length,2);
 row.dataset.name='archive.zip';row.events.click(event);assert.equal(previews.length,2);
});
