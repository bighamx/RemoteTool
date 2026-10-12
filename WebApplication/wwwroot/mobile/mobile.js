import {isActive,isFinal,readEvents,coordinates,windowsKey} from './mobile-core.mjs';
const $ = id => document.getElementById(id);
const id = value => encodeURIComponent(value);
const sleep = ms => new Promise(resolve => setTimeout(resolve,ms));
let platform, sessionId='', sessions=[], cursor='', runId='', source, runPoll, sessionRevision=0, imageTimer, imageUrl, inputSocket, terminalSocket;
let pending;
try { pending = JSON.parse(localStorage.getItem('rt.h5.pending') || 'null'); } catch { pending=null; localStorage.removeItem('rt.h5.pending'); }
let lastSubmissionAbsent = false, submitting = false, desktopOn = false, rightClick=false, displaySequence=0;
let installPrompt;
const standalone=matchMedia('(display-mode: standalone)').matches||navigator.standalone===true;
if(!standalone&&/iPad|iPhone|iPod/.test(navigator.userAgent))$('install-app').hidden=false;
window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();installPrompt=event;$('install-app').hidden=false;$('install-app').title='安装应用';$('install-app').setAttribute('aria-label','安装应用');});
action($('install-app'),async()=>{
    if(installPrompt){await installPrompt.prompt();installPrompt=null;$('install-app').hidden=true;return;}
    $('install-tip').textContent='在 Safari 中点“分享”→“添加到主屏幕”。';
    $('install-tip').hidden=false;
});
window.addEventListener('appinstalled',()=>{$('install-app').hidden=true;$('install-tip').hidden=true;});
if('serviceWorker' in navigator && (location.protocol==='https:'||location.hostname==='localhost'||location.hostname==='127.0.0.1'))
    navigator.serviceWorker.register('/mobile/sw.js?v=4',{scope:'/mobile/'}).catch(()=>{});
const requestKey=()=>Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
function notice(text='') { $('notice').textContent=text; $('notice').hidden=!text; }
function saved() { if(pending) localStorage.setItem('rt.h5.pending',JSON.stringify(pending)); else localStorage.removeItem('rt.h5.pending'); }
async function api(path, options={}) {
    const response=await fetch(path,{credentials:'same-origin',...options,headers:{...(options.body?{'Content-Type':'application/json'}:{}),...options.headers},signal:options.signal || AbortSignal.timeout(30000)});
    const value=await response.json().catch(()=>({message:`HTTP ${response.status}`}));
    if(!response.ok) { const error=new Error(value.message||value.error||`HTTP ${response.status}`); Object.assign(error,{status:response.status,delivery:value.delivery}); if(response.status===401) showLogin(); throw error; }
    return value;
}
const post=(path,body={},key)=>api(path,{method:'POST',body:JSON.stringify(body),headers:key?{'Idempotency-Key':key}:{}});
function action(element,fn) { element.addEventListener('click',()=>Promise.resolve().then(fn).catch(e=>notice(e.message))); }
function showLogin() { $('app').hidden=true; $('login').hidden=false; stopWatching(); disconnectDesktop(); disconnectTerminal(); }
$('login-form').addEventListener('submit',async event=>{
    event.preventDefault(); $('login-error').textContent='';
    try { const user=await post('/api/auth/login',{username:$('username').value,password:$('password').value}); localStorage.removeItem('token');localStorage.setItem('username',user.username||''); $('password').value=''; await start(); }
    catch(e) { $('login-error').textContent=e.message; }
});
action($('logout'),async()=>{ await post('/api/auth/logout');localStorage.removeItem('token');localStorage.removeItem('username'); showLogin(); });
document.querySelectorAll('[data-page]').forEach(button=>action(button,async()=>{
    const page=button.dataset.page;
    document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('selected',b===button));
    for(const name of ['system','codex','desktop','terminal']) $(name+'-page').hidden=name!==page;
    if(page!=='desktop') disconnectDesktop();
    if(page==='codex' && !sessions.length) await loadSessions();
}));
async function refreshSystem() {
    const response=await api('/api/system/info'), info=response.data||response;
    $('host').textContent=info.machineName||'手机控制台'; $('metrics').replaceChildren();
    for(const [name,value] of [['CPU',`${info.cpuUsagePercent??'—'}%`],['内存',`${info.memoryUsagePercent??'—'}%`],['系统',info.platform||platform?.platform||'—']]) {
        const card=document.createElement('div'); card.className='card metric'; const label=document.createElement('small'), strong=document.createElement('strong');
        label.textContent=name; strong.textContent=value; card.append(label,strong); $('metrics').append(card);
    }
}
action($('refresh-system'),refreshSystem);
document.querySelectorAll('[data-power]').forEach(button=>action(button,async()=>{
    if(!confirm(`确定要${button.textContent}这台电脑？`)) return;
    const result=await post(`/api/system/${button.dataset.power}`); notice(result.message||'操作已提交');
}));
async function start() {
    await api('/api/auth/me'); $('login').hidden=true; $('app').hidden=false;
    platform=await api('/api/platform/capabilities');
    $('platform-info').textContent=`${platform.platform}\n桌面代理：${platform.desktopConfigured?'已配置':'未配置'}\n${platform.permissions?`屏幕录制：${platform.permissions.screenRecording?'已授权':'未授权'}\n辅助功能：${platform.permissions.accessibility?'已授权':'未授权'}${platform.permissions.sessionLocked?'\nMac 已锁屏，请先解锁':''}`:''}\n${platform.error||platform.desktopNote||''}`;
    $('connect-desktop').disabled=!platform.desktopConfigured||!!platform.permissions?.sessionLocked;
    await refreshSystem();
    if(pending) { sessionId=pending.session_id; $('prompt').value=pending.input||''; updateComposer(); notice('有上次的提交记录，请打开 Codex 核对结果。'); }
}
function renderSessions() {
    $('sessions').replaceChildren(); const query=$('session-search').value.toLowerCase();
    for(const row of sessions.filter(s=>`${s.title} ${s.preview}`.toLowerCase().includes(query))) {
        const button=document.createElement('button'); button.className='session'+(row.id===sessionId?' active':'');
        const title=document.createElement('span'), preview=document.createElement('small'); title.textContent=row.title||'未命名会话'; preview.textContent=row.preview||row.cwd||row.id;
        button.append(title,preview); action(button,()=>openSession(row.id)); $('sessions').append(button);
    }
    $('more-sessions').hidden=!cursor;
}
async function loadSessions(more=false) {
    const result=await api('/api/codex/sessions'+(more&&cursor?`?cursor=${id(cursor)}`:''));
    const byId=new Map((more?sessions:[]).map(s=>[s.id,s])); for(const row of result.data||[]) byId.set(row.id,row);
    sessions=[...byId.values()]; cursor=result.next_cursor||''; renderSessions();
    const projects=await api('/api/codex/projects').catch(()=>({data:[]}));
    $('project').replaceChildren(new Option('无项目（独立工作目录）',''));
    for(const project of projects.data||[]) $('project').add(new Option(project.name||project.id,project.id));
    if(pending && !runId) await openSession(pending.session_id);
}
action($('refresh-sessions'),()=>loadSessions()); action($('more-sessions'),()=>loadSessions(true)); $('session-search').addEventListener('input',renderSessions);
$('new-session').addEventListener('submit',async event=>{
    event.preventDefault();
    if(pending) return notice('请先核对或完成当前提交，再新建会话。');
    try { const project=$('project').value; const result=await post('/api/codex/sessions',{title:$('new-title').value||'手机 Codex 对话',project_mode:project?'existing':'none',project_id:project});
        await loadSessions(); await openSession(result.session.id);
    } catch(e) { notice(e.message); }
});
function addMessage(role,text) {
    const article=document.createElement('div'); article.className='message '+role; const label=document.createElement('small'), content=document.createElement('pre');
    label.textContent=role==='user'?'你':role==='assistant'?'Codex':'系统'; content.textContent=typeof text==='string'?text:JSON.stringify(text);
    article.append(label,content); $('messages').append(article);
}
async function history(revision=sessionRevision) {
    const result=await api(`/api/codex/sessions/${id(sessionId)}/messages?limit=100`);
    if(revision!==sessionRevision) return;
    $('messages').replaceChildren(); for(const message of result.data||[]) addMessage(message.role,message.content||'');
    $('messages').scrollTop=$('messages').scrollHeight;
}
async function openSession(value) {
    if(pending && pending.session_id!==value) return notice('当前提交尚未结束或结果未确认，请先处理后再切换会话。');
    $('codex-page').classList.add('chat-detail-open');
    stopWatching(); $('run-output').hidden=true; $('approval').hidden=true; $('tool-state').textContent=''; sessionId=value; const revision=++sessionRevision; renderSessions(); updateComposer(); notice();
    const info=await api(`/api/codex/sessions/${id(value)}`); if(revision!==sessionRevision)return;
    $('chat-title').textContent=info.session.title||'Codex'; $('chat-path').textContent=info.session.cwd||''; $('rename-session').disabled=false;
    await history(revision);
    if(pending) { await verify(); return; }
    const active=await api(`/api/codex/sessions/${id(value)}/active-run`); if(revision!==sessionRevision)return;
    if(active.run_id) { pending={session_id:value,run_id:active.run_id}; saved(); await watch(active.run_id); }
}
action($('back-sessions'),()=>$('codex-page').classList.remove('chat-detail-open'));
action($('rename-session'),async()=>{ const title=prompt('会话名称',$('chat-title').textContent); if(!title?.trim())return; await api(`/api/codex/sessions/${id(sessionId)}`,{method:'PATCH',body:JSON.stringify({title})}); $('chat-title').textContent=title; await loadSessions(); });
function updateComposer() {
    const blocked=!!pending||submitting||!sessionId;
    $('prompt').disabled=!sessionId; $('send').disabled=blocked;
    $('verify-run').hidden=!pending; $('dismiss-pending').hidden=!lastSubmissionAbsent;
}
$('composer').addEventListener('submit',async event=>{
    event.preventDefault(); if(pending||submitting||!sessionId)return;
    const input=$('prompt').value.trim(); if(!input)return;
    submitting=true; pending={session_id:sessionId,input,key:requestKey()}; saved(); updateComposer(); notice();
    try { const result=await post('/api/codex/runs',{session_id:sessionId,input},pending.key); pending.run_id=result.run_id; delete pending.input; saved(); $('prompt').value=''; await watch(result.run_id); }
    catch(e) {
        if(e.delivery==='rejected'||e.status>=400&&e.status<500) { pending=null; saved(); }
        notice(e.message+(pending?'；提交结果未确认，请核对，程序不会自动重发。':''));
    } finally { submitting=false; updateComposer(); }
});
async function verify() {
    if(!pending)return;
    lastSubmissionAbsent=false; updateComposer();
    if(pending.run_id) { await watch(pending.run_id); return; }
    const result=await api(`/api/codex/runs/lookup?key=${id(pending.key)}`);
    if(result.found) { pending.run_id=result.run_id; delete pending.input; saved(); $('prompt').value=''; await watch(result.run_id); }
    else { lastSubmissionAbsent=true; updateComposer(); notice('尚未找到任务记录。请检查电脑端历史；确认未执行后可恢复草稿。'); }
}
action($('verify-run'),verify);
action($('dismiss-pending'),()=>{ if(!lastSubmissionAbsent||!confirm('已经核对电脑会话，确认这条消息没有执行？'))return; $('prompt').value=pending.input||''; pending=null; saved(); lastSubmissionAbsent=false; updateComposer(); notice('草稿已恢复，点击发送会创建新的提交。'); });
function stopWatching() { source?.abort(); source=null; clearTimeout(runPoll); runId=''; $('stop-run').hidden=true; }
async function watch(value) {
    stopWatching(); displaySequence=0; runId=value; $('run-output').hidden=false; const revision=sessionRevision;
    const controller=source=new AbortController();
    const state=await api(`/api/codex/runs/${id(value)}`); if(controller.signal.aborted)return;
    applyRun(state); if(!runId)return;
    (async()=>{
        let last=String(state.seq||0);
        while(!controller.signal.aborted) {
            try {
                const response=await fetch(`/api/codex/runs/${id(value)}/events`,{credentials:'same-origin',headers:last?{'Last-Event-ID':last}:{},signal:controller.signal});
                if(!response.ok)throw new Error(`事件连接 HTTP ${response.status}`);
                for await(const event of readEvents(response.body)) {
                    if(controller.signal.aborted||revision!==sessionRevision)break;
                    if(event.id)last=event.id;
                    const data=event.data;
                    if(data.event==='message.delta' && Number(data.seq)>displaySequence) { $('live-text').textContent+=data.delta||''; displaySequence=Number(data.seq); }
                    if(data.event==='tool.started'||data.event==='tool.completed')$('tool-state').textContent=`${data.tool||'工具'} · ${data.event==='tool.started'?'执行中':'完成'} ${data.preview||''}`;
                    // Final truth and approval payloads always come from the run status endpoint.
                }
            } catch(e) { if(controller.signal.aborted)break; $('run-state').textContent='网络中断，正在重新核对任务状态'; }
            await sleep(1500);
        }
    })();
    const poll=async()=>{
        if(controller.signal.aborted||revision!==sessionRevision)return;
        try { applyRun(await api(`/api/codex/runs/${id(value)}`)); } catch(e) { notice(e.message); }
        if(!controller.signal.aborted)runPoll=setTimeout(poll,2000);
    };
    runPoll=setTimeout(poll,1500);
}
function applyRun(state) {
    if(Number(state.seq||0)>=displaySequence) { $('live-text').textContent=state.output||''; displaySequence=Number(state.seq||0); }
    $('run-state').textContent=`任务状态：${state.status||'未知'}`;
    $('stop-run').hidden=!isActive(state.status); showApproval(state.approval);
    if(isFinal(state.status)) {
        stopWatching(); pending=null; saved(); updateComposer(); history().catch(e=>notice(e.message));
        if(state.error)notice(state.error); else notice('本轮任务已结束。');
    } else if(!isActive(state.status)) { source?.abort(); notice('任务状态需要核对，请检查真实会话历史，勿重复发送。'); }
}
function showApproval(value) {
    const box=$('approval'); if(box.dataset.request===value?.request_id)return;
    box.replaceChildren(); box.hidden=!value; box.dataset.request=value?.request_id||''; if(!value)return;
    const title=document.createElement('p'); title.textContent=value.description||'Codex 需要你的回答'; box.append(title);
    if(value.kind==='user_input') {
        const form=document.createElement('form'), fields=[];
        for(const question of value.questions||[]) {
            const label=document.createElement('label'); label.textContent=question.question||question.header||question.id;
            const field=document.createElement('input'); field.type=question.isSecret?'password':'text'; field.required=true;
            const options=document.createElement('small'); options.textContent=(question.options||[]).map(o=>o.label).join(' / ');
            label.append(field,options); form.append(label); fields.push([question.id,field]);
        }
        const send=document.createElement('button'); send.textContent='提交回答'; form.append(send);
        form.addEventListener('submit',async e=>{e.preventDefault();send.disabled=true;try { const answers={};for(const [key,field]of fields)answers[key]={answers:[field.value]};await post(`/api/codex/runs/${id(runId)}/approval`,{request_id:value.request_id,answers});box.hidden=true;}catch(error){notice(error.message);}finally{send.disabled=false;}});box.append(form);
    } else for(const [text,choice]of [['允许本次','once'],['拒绝','decline']]) {const button=document.createElement('button');button.textContent=text;action(button,async()=>{button.disabled=true;try{await post(`/api/codex/runs/${id(runId)}/approval`,{request_id:value.request_id,choice});box.hidden=true;}finally{button.disabled=false;}});box.append(button);}
}
action($('stop-run'),async()=>{if(runId)await post(`/api/codex/runs/${id(runId)}/stop`);});
const socketUrl=path=>`${location.protocol==='https:'?'wss':'ws'}://${location.host}${path}`;
let queued=[], inputBusy=false;
function sendInput(message) {
    if(inputSocket?.readyState!==WebSocket.OPEN)return;
    if(message.type==='mouse-move'&&queued.at(-1)?.type==='mouse-move')queued[queued.length-1]=message;
    else if(queued.length<64)queued.push(message); else {disconnectDesktop();notice('输入队列已满，已断开，避免操作积压。');return;}
    flushInput();
}
function flushInput() {if(!inputBusy&&queued.length&&inputSocket?.readyState===WebSocket.OPEN){inputBusy=true;inputSocket.send(JSON.stringify({...queued.shift(),ack:true}));}}
function key(code,down) { const vkCode=windowsKey(code); if(platform.platform==='macOS')sendInput({type:'key',code,isKeyDown:down});else if(vkCode!==undefined)sendInput({type:'keyboard',vkCode,isKeyDown:down}); }
let held=new Set(), pointer;
function releaseInput() {for(const code of held)key(code,false);held.clear();if(pointer){sendInput({type:'mouse-up',...pointer});pointer=null;}}
function disconnectDesktop() {
    releaseInput();desktopOn=false;clearTimeout(imageTimer);inputSocket?.close();inputSocket=null;queued=[];inputBusy=false;
    $('connect-desktop').disabled=!platform?.desktopConfigured||!!platform.permissions?.sessionLocked; $('disconnect-desktop').disabled=true;
    if(imageUrl){URL.revokeObjectURL(imageUrl);imageUrl=null;}$('screen').removeAttribute('src');
}
async function frames() {
    if(!desktopOn)return;
    try { const response=await fetch('/api/system/screenshot',{credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(20000)});
        if(!response.ok||!response.headers.get('content-type')?.startsWith('image/'))throw new Error((await response.json()).message||'桌面画面暂不可用');
        const next=URL.createObjectURL(await response.blob()); if(!desktopOn){URL.revokeObjectURL(next);return;}
        const previous=imageUrl; imageUrl=next; $('screen').src=next; if(previous)URL.revokeObjectURL(previous);
    }catch(e){disconnectDesktop();notice(e.message);return;}
    imageTimer=setTimeout(frames,200);
}
action($('connect-desktop'),()=>{disconnectDesktop();desktopOn=true;$('connect-desktop').disabled=true;$('disconnect-desktop').disabled=false;inputSocket=new WebSocket(socketUrl('/ws/input'));inputSocket.onmessage=event=>{try{const ack=JSON.parse(event.data);if(ack.type==='input-ack'){inputBusy=false;if(!ack.ok)notice('电脑未执行输入，请检查辅助功能权限。');flushInput();}}catch{}};inputSocket.onclose=()=>{if(desktopOn){disconnectDesktop();notice('桌面输入连接已断开，请重新连接。');}};frames();});
action($('disconnect-desktop'),disconnectDesktop);
action($('right-click'),()=>{rightClick=!rightClick;$('right-click').textContent=`右键模式：${rightClick?'开':'关'}`;});
const screen=$('screen');
screen.addEventListener('pointerdown',event=>{if(!desktopOn||pointer)return;event.preventDefault();screen.focus();screen.setPointerCapture(event.pointerId);pointer={...coordinates(event,screen.getBoundingClientRect()),button:rightClick||event.button===2?2:0};sendInput({type:'mouse-down',...pointer});});
screen.addEventListener('pointermove',event=>{if(!desktopOn||!pointer)return;const pos=coordinates(event,screen.getBoundingClientRect());pointer={...pointer,...pos};sendInput({type:'mouse-move',...pointer});});
for(const name of ['pointerup','pointercancel','lostpointercapture'])screen.addEventListener(name,()=>{if(pointer){sendInput({type:'mouse-up',...pointer});pointer=null;}});
screen.addEventListener('contextmenu',e=>e.preventDefault());
screen.addEventListener('wheel',event=>{if(!desktopOn)return;event.preventDefault();sendInput({type:'mouse-wheel',...coordinates(event,screen.getBoundingClientRect()),delta:-Math.round(event.deltaY)});},{passive:false});
screen.addEventListener('keydown',event=>{if(event.isComposing)return;event.preventDefault();if(!event.repeat){held.add(event.code);key(event.code,true);}});
screen.addEventListener('keyup',event=>{event.preventDefault();held.delete(event.code);key(event.code,false);});screen.addEventListener('blur',releaseInput);
document.querySelectorAll('[data-shortcut]').forEach(button=>action(button,()=>{key(button.dataset.shortcut,true);key(button.dataset.shortcut,false);}));
action($('scroll-up'),()=>sendInput({type:'pointer-wheel',dy:120}));action($('scroll-down'),()=>sendInput({type:'pointer-wheel',dy:-120}));
$('desktop-text').addEventListener('submit',event=>{event.preventDefault();if(!desktopOn)return notice('请先连接桌面。');sendInput({type:'text',text:$('remote-text').value});$('remote-text').value='';});
function disconnectTerminal(){terminalSocket?.close();terminalSocket=null;$('command').disabled=true;$('terminal-send').disabled=true;$('connect-terminal').disabled=false;$('disconnect-terminal').disabled=true;}
action($('connect-terminal'),()=>{disconnectTerminal();const ws=terminalSocket=new WebSocket(socketUrl('/ws/terminal?type=shell'));$('connect-terminal').disabled=true;ws.onopen=()=>{$('command').disabled=false;$('terminal-send').disabled=false;$('disconnect-terminal').disabled=false;};ws.onmessage=e=>{$('terminal-output').textContent=($('terminal-output').textContent+e.data).slice(-100000);$('terminal-output').scrollTop=$('terminal-output').scrollHeight;};ws.onclose=()=>{if(terminalSocket===ws){disconnectTerminal();notice('终端连接已关闭。');}};});
action($('disconnect-terminal'),disconnectTerminal);
$('terminal-form').addEventListener('submit',event=>{event.preventDefault();if(terminalSocket?.readyState===WebSocket.OPEN){terminalSocket.send($('command').value);$('command').value='';}});
document.addEventListener('visibilitychange',()=>{if(document.hidden)disconnectDesktop();});
start().catch(error=>{if(error.status!==401)notice(error.message);});
