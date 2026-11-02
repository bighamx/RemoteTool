const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
process.env.TZ = 'Asia/Hong_Kong';
const source = fs.readFileSync(require('node:path').join(__dirname, '../WebApplication/wwwroot/js/server-update.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup(mode, supportsForce = true) {
  const elements = new Map(), requests = [], listeners = {};
  const make = () => ({ hidden:false, disabled:false, value:'', textContent:'', checked:false,style:{},dataset:{},insertAdjacentElement(position,node){if(position==='afterend')elements.set('[data-job]',node);else elements.set('[data-pull]',node);} });
  const element = name => elements.get(name) || (elements.set(name, make()), elements.get(name));
  const dialog = { style:{}, open:false, contains: () => false,
    querySelector: element, showModal() { this.open=true; }, close() { this.open=false; listeners.close?.(); },
    addEventListener(name, fn) { listeners[name]=fn; } };
  const status = { supported:true, installed:true, mode, supportsForce, canRebuild:mode==='source', currentVersion:'abc123',
    check:{available:true, version:'1.2.1', releaseSequence:1, releaseNotes:'notes', error:null}, policy:{mode:'notify',startHourUtc:18,endHourUtc:22} };
  let button;
  const document = { activeElement:null, visibilityState:'visible',
    querySelector: () => ({prepend(value) { button=value; }}),
    createElement: type => type==='dialog' ? dialog : make(), body:{append() {}} };
  vm.runInNewContext(source, {document, location:{pathname:'/'}, Date,
    localStorage:{getItem:()=>null}, sessionStorage:{getItem:()=>null,setItem(){}},
    confirm:()=>true,setTimeout(){},fetch:async(path, options)=>{
      requests.push({path, ...options, body:options.body && JSON.parse(options.body)});
      return {ok:true,json:async()=>structuredClone(status)};
    }});
  return {element, dialog, status, requests, button:()=>button};
}
(async () => {
  const src=setup('source'); await flush();
  assert.equal(src.dialog.open,false,'Source mode must not announce an unrelated package release');
  assert.equal(src.element('[data-policy]').hidden,true);
  assert.equal(src.element('[data-install]').hidden,true);
  assert.equal(src.element('[data-rebuild]').hidden,false);
  assert.equal(src.element('[data-force]').checked,false);
  src.element('[data-force]').checked=true; src.element('[data-rebuild]').onclick(); await flush();
  assert.deepEqual(src.requests.at(-1).body,{force:true});
  assert.equal(src.requests.at(-1).path,'/api/server-update/rebuild');
  assert.equal(src.element('[data-force]').checked,false,'Force must reset after accepting one job');
  src.element('[data-force]').checked=true; src.dialog.close(); assert.equal(src.element('[data-force]').checked,false);
  const legacy=setup('source',false); await flush();
  assert.equal(legacy.element('[data-force-row]').hidden,true);
  legacy.element('[data-force]').checked=true; legacy.element('[data-rebuild]').onclick(); await flush();
  assert.equal(legacy.requests.at(-1).body.force,false,'Unsupported server must never receive force=true');
  const release=setup('release'); await flush();
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
  src.status.check={kind:'source',state:'up_to_date',message:'已是最新版本'};
  src.status.supportsSourcePull=true;
  src.element('[data-check]').onclick(); await flush();
  assert.equal(src.element('[data-status]').textContent,'已是最新版本');
  assert.equal(src.element('[data-pull]').hidden,false);
  src.element('[data-pull]').onclick(); await flush();
  assert.deepEqual(src.requests.at(-1).body,{force:false,pullSource:true});
  src.status.check={kind:'source',state:'failed',error:'network unavailable'};
  src.element('[data-check]').onclick(); await flush();
  assert.match(src.element('[data-status]').textContent,/检查失败/);
  assert.equal(release.element('[data-notes]').textContent.includes('null'),false);
  console.log('Server update Web UI: source/release actions, force scope, legacy capability, local time and error checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
