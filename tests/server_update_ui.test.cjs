const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
process.env.TZ = 'Asia/Hong_Kong';
const source = fs.readFileSync(require('node:path').join(__dirname, '../WebApplication/wwwroot/js/server-update.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup(mode, supportsForce = true) {
  const elements = new Map(), requests = [], listeners = {}, timers = new Map();
  let timerId=0;
  const control={};
  const make = () => ({ hidden:false, disabled:false, value:'', textContent:'', checked:false,style:{},dataset:{},setAttribute(){},insertAdjacentHTML(){},insertAdjacentElement(position,node){if(position==='afterend')elements.set('[data-job]',node);else elements.set('[data-pull]',node);} });
  const element = name => elements.get(name) || (elements.set(name, make()), elements.get(name));
  const dialog = { style:{}, open:false, setAttribute(){}, contains: () => false,
    querySelector: element, showModal() { this.open=true; }, close() { this.open=false; listeners.close?.(); },
    addEventListener(name, fn) { listeners[name]=fn; } };
  const status = { supported:true, installed:true, mode, supportsForce, canRebuild:mode==='source', currentVersion:'abc123',
    check:{available:true, version:'1.2.1', releaseSequence:1, releaseNotes:'notes', error:null}, policy:{mode:'notify',startHourUtc:18,endHourUtc:22} };
  const button=make();button.hidden=true;
  const document = { activeElement:null, visibilityState:'visible',
    querySelector: selector => selector==='[data-server-update-entry]' ? button : null,
    createElement: type => type==='dialog' ? dialog : make(), body:{append() {}} };
  vm.runInNewContext(source, {document, location:{pathname:'/'}, Date,
    localStorage:{getItem:()=>null}, sessionStorage:{getItem:()=>null,setItem(){}},
    confirm:()=>true,setTimeout(fn,delay){timers.set(++timerId,{fn,delay});return timerId;},clearTimeout(id){timers.delete(id);},fetch:async(path, options)=>{
      requests.push({path, ...options, body:options.body && JSON.parse(options.body)});
      if(control.fetch)return control.fetch(path,options);
      return {ok:true,json:async()=>structuredClone(status)};
    }});
  return {element, dialog, status, requests, control, timers, button:()=>button,
    async tick(){const [id,timer]=timers.entries().next().value;timers.delete(id);timer.fn();await flush();}};
}
(async () => {
  const src=setup('source'); await flush();
  assert.equal(src.button().hidden,true,'Unknown source check must not advertise an update');
  assert.equal(src.dialog.open,false,'Source mode must not announce an unrelated package release');
  assert.equal(src.element('[data-policy]').hidden,true);
  assert.equal(src.element('[data-install]').hidden,true);
  assert.equal(src.element('[data-rebuild]').hidden,false);
  assert.equal(src.element('[data-force]').checked,false);
  src.element('[data-force]').checked=true; src.element('[data-rebuild]').onclick(); await flush();
  assert.deepEqual(src.requests.at(-1).body,{force:true});
  assert.equal(src.requests.at(-1).path,'/api/server-update/rebuild');
  assert.equal(src.element('[data-force]').checked,false,'Force must reset after accepting one job');
  src.element('[data-force]').checked=true; src.element('[data-close-header]').onclick(); assert.equal(src.element('[data-force]').checked,false);
  const legacy=setup('source',false); await flush();
  assert.equal(legacy.element('[data-force-row]').hidden,true);
  legacy.element('[data-force]').checked=true; legacy.element('[data-rebuild]').onclick(); await flush();
  assert.equal(legacy.requests.at(-1).body.force,false,'Unsupported server must never receive force=true');
  const release=setup('release'); await flush();
  assert.equal(release.button().hidden,false);
  assert.equal(release.dialog.open,true); assert.equal(release.element('[data-rebuild]').hidden,true);
  assert.equal(release.element('[data-start]').value,'02:00'); assert.equal(release.element('[data-end]').value,'06:00');
  release.element('select').value='automatic'; release.element('[data-start]').value='03:00'; release.element('[data-end]').value='07:00';
  release.element('[data-save]').onclick(); await flush();
  assert.deepEqual(release.requests.at(-1).body,{mode:'automatic',startHourUtc:19,endHourUtc:23});
  release.element('[data-install]').onclick(); await flush();
  assert.deepEqual(release.requests.at(-1).body,{sequence:1,force:false});
  release.status.job={phase:'rolled_back',message:'Response status code does not indicate success: 503 (Service Unavailable).'};
  release.element('[data-check]').onclick(); await flush();
  assert.equal(release.element('[data-job]').textContent,'服务端请求失败（HTTP 503）');
  release.status.check.available=false;
  release.element('[data-check]').onclick(); await flush();
  assert.equal(release.element('[data-status]').textContent,'已是最新版本');
  assert.equal(release.button().hidden,true,'Current release must hide the sidebar update entry');
  src.status.check={kind:'source',state:'up_to_date',message:'已是最新版本'};
  src.status.supportsSourcePull=true;
  src.element('[data-check]').onclick(); await flush();
  assert.equal(src.element('[data-status]').textContent,'已是最新版本');
  assert.equal(src.element('[data-rebuild]').hidden,true,'Latest source and running service need no update action');
  const beforeLatestClick=src.requests.length;
  src.element('[data-rebuild]').onclick(); await flush();
  assert.equal(src.requests.length,beforeLatestClick,'A hidden stale update action must not submit');
  src.status.check={kind:'source',state:'rebuild_required',available:true,localRevision:'6226b4b66cf5'};
  src.element('[data-check]').onclick(); await flush();
  assert.equal(src.element('[data-rebuild]').hidden,false);
  assert.equal(src.button().hidden,false,'Confirmed source update must show the sidebar entry');
  assert.equal(src.element('[data-rebuild]').textContent,'编译并重启');
  assert.match(src.element('[data-mode]').textContent,/6226b4b66cf5/);
  assert.match(src.element('[data-status]').textContent,/源码已是最新/);
  src.element('[data-rebuild]').onclick(); await flush();
  assert.deepEqual(src.requests.at(-1).body,{force:false},'Up-to-date local source must only compile, never pull');
  src.status.check={kind:'source',state:'source_out_of_sync'};
  src.element('[data-check]').onclick(); await flush();
  assert.equal(src.element('[data-rebuild]').textContent,'拉取并更新');
  src.element('[data-rebuild]').onclick(); await flush();
  assert.deepEqual(src.requests.at(-1).body,{force:false,pullSource:true});
  src.status.check={kind:'source',state:'source_changed'};
  src.element('[data-check]').onclick(); await flush();
  assert.equal(src.element('[data-rebuild]').textContent,'编译并重启','Dirty local source must not offer a pull');
  src.status.check={kind:'source',state:'failed',error:'network unavailable',available:true};
  src.element('[data-check]').onclick(); await flush();
  assert.match(src.element('[data-status]').textContent,/检查失败/);
  assert.equal(src.button().hidden,true);
  assert.equal(src.element('[data-rebuild]').hidden,true,'Check failure must not guess the next source operation');
  src.status.check={kind:'source',state:'source_out_of_sync',runningVersion:'older-server',available:true};
  src.element('[data-check]').onclick(); await flush();
  assert.equal(src.element('[data-rebuild]').hidden,true,'Stale checks must be refreshed before choosing an operation');
  assert.equal(src.button().hidden,true,'A stale available flag cannot advertise an update for a different running version');
  assert.equal(release.element('[data-notes]').textContent.includes('null'),false);
  const progress=setup('source'); await flush();
  let accept;
  progress.control.fetch=async()=>new Promise(resolve=>{accept=()=>resolve({ok:true,json:async()=>structuredClone(progress.status)});});
  progress.element('[data-rebuild]').onclick();
  assert.equal(progress.element('[data-job-title]').textContent,'正在提交编译并重启请求…','Immediate feedback before POST completes');
  assert.equal(progress.element('[data-progress]').hidden,false);
  assert.equal(progress.element('[data-rebuild]').disabled,true);
  progress.status.job={id:'new-job',phase:'waiting',message:'等待空闲：智能体运行中'};
  accept(); await flush(); progress.control.fetch=null;
  assert.equal(progress.element('[data-job-title]').textContent,'等待任务空闲');
  assert.match(progress.element('[data-job]').textContent,/智能体运行中/);
  assert.equal([...progress.timers.values()][0].delay,0,'Do not wait for the old 60s timer');
  for(const [phase,title] of [['pulling','正在拉取源码'],['building','正在编译并重启'],['verifying','正在验证更新结果']]){
    progress.status.job={id:'new-job',phase,message:phase}; await progress.tick();
    assert.equal(progress.element('[data-job-title]').textContent,title);
    assert.equal([...progress.timers.values()][0].delay,2000);
  }
  progress.control.fetch=async()=>{throw new Error('offline');}; await progress.tick();
  assert.match(progress.element('[data-job]').textContent,/正在重连/);
  assert.equal(progress.element('[data-progress]').hidden,false);
  progress.control.fetch=null;
  for(const phase of ['completed','deferred','rolled_back','failed','recovery_required']){
    progress.status.job={id:'new-job',phase,message:phase}; await progress.tick();
    assert.equal(progress.element('[data-progress]').hidden,true,'Terminal '+phase+' must stop progress');
    assert.equal(progress.element('[data-rebuild]').disabled,phase==='recovery_required');
    if(phase==='completed') assert.equal(progress.element('[data-job-title]').textContent,'本次更新：已完成');
  }
  progress.status.job={id:'new-job',phase:'completed',message:'old completion'};
  progress.element('[data-close]').onclick(); progress.button().onclick(); await flush();
  assert.equal(progress.element('[data-job-title]').textContent,'上次更新：已完成','Reopening must identify the previous result as historical');
  progress.status.job={id:'another-job',phase:'deferred',message:'old wait timeout'}; await progress.tick();
  assert.equal(progress.element('[data-job-title]').textContent,'上次更新：已延后');
  const closedDuringPost=setup('source'); await flush();
  let finishClosedPost;
  closedDuringPost.control.fetch=async()=>new Promise(resolve=>{finishClosedPost=()=>resolve({ok:true,json:async()=>({...structuredClone(closedDuringPost.status),job:{id:'closed-job',phase:'completed',message:'done'}})});});
  closedDuringPost.element('[data-rebuild]').onclick();
  closedDuringPost.element('[data-close]').onclick();
  finishClosedPost(); await flush();
  assert.equal(closedDuringPost.element('[data-job-title]').textContent,'上次更新：已完成','A POST completing after close must not restore the old dialog ownership');
  const race=setup('source'); await flush();
  let oldResponse;
  race.control.fetch=async(path,options)=>options.method==='GET' ? new Promise(resolve=>{oldResponse=resolve;}) : {ok:true,json:async()=>({...structuredClone(race.status),job:{phase:'waiting',message:'new job'}})};
  const pendingPoll=race.tick(); await flush(); race.element('[data-rebuild]').onclick(); await flush();
  oldResponse({ok:true,json:async()=>({...structuredClone(race.status),job:{phase:'completed',message:'old job'}})}); await pendingPoll; await flush();
  assert.equal(race.element('[data-job-title]').textContent,'等待任务空闲','An old in-flight GET must not overwrite the new POST job');
  const uncertain=setup('source'); await flush();
  uncertain.control.fetch=async()=>{throw new Error('timeout');};
  uncertain.element('[data-rebuild]').onclick(); await flush();
  assert.equal(uncertain.element('[data-job-title]').textContent,'正在核对更新任务是否已受理…');
  assert.equal(uncertain.element('[data-rebuild]').disabled,true);
  assert.match(uncertain.element('[data-error]').textContent,/请勿重复提交/);
  uncertain.control.fetch=null; uncertain.status.job={phase:'waiting',message:'accepted after timeout'}; await uncertain.tick();
  assert.equal(uncertain.element('[data-job-title]').textContent,'等待任务空闲');
  assert.equal(uncertain.requests.filter(request=>request.method==='POST').length,1,'Never replay an uncertain update');
  console.log('Server update Web UI: source/release actions, force scope, legacy capability, local time and error checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
