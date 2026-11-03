(() => {
    if (location.pathname.toLowerCase().startsWith('/account/')) return;
    const host = document.querySelector('.header-right');
    if (!host) return;
    const button = document.createElement('button');
    button.hidden = true;
    button.className = 'btn btn-secondary btn-sm'; button.textContent = '服务端更新';
    button.type = 'button'; host.prepend(button);
    const dialog = document.createElement('dialog');
    dialog.style.cssText = 'max-width:560px;width:90%;border-radius:12px;padding:24px;background:var(--bg-color,#fff);color:var(--text-color,#222)';
    // All remote strings are assigned with textContent, never interpreted as HTML.
    dialog.innerHTML = '<h2>服务端更新</h2><p data-current></p><p data-mode></p><p data-status></p><pre data-notes style="white-space:pre-wrap"></pre><p data-error role="alert"></p><div data-policy><label>更新方式 <select><option value="notify">仅提醒</option><option value="download">自动下载，手动安装</option><option value="automatic">空闲时自动更新并重启</option></select></label><p data-window>自动安装时段（本地时间） <input data-start type="time" step="900"> 至 <input data-end type="time" step="900"></p><button data-save>保存设置</button></div><p data-force-row hidden><label><input data-force type="checkbox"> 强制中断任务</label><small style="display:block">仅本次生效：断开连接并中断 RemoteTool 的 Codex 任务；Hermes 独立任务继续运行。</small></p><p><button data-check>立即检查</button> <button data-install>更新并重启</button> <button data-rebuild hidden>编译并重启</button> <button data-close>稍后</button></p>';
    document.body.append(dialog);
    let state, pending = false, reconnecting = false, checking = false, submitting = '', pollTimer, polling = false, revision = 0, uncertain = false;
    const at = selector => dialog.querySelector(selector);
    const text = value => value == null || value === 'null' ? '' : String(value);
    const friendly = value => text(value).replace(/Response status code does not indicate success: (\d+) \([^)]+\)\.?/g, '服务端请求失败（HTTP $1）');
    const jobStatus = document.createElement('p'); at('[data-status]').insertAdjacentElement('afterend',jobStatus);
    jobStatus.setAttribute('aria-live','polite');
    jobStatus.insertAdjacentHTML('beforebegin','<div data-job-progress hidden role="status" aria-live="polite" style="padding:12px;border:1px solid #8886;border-radius:8px"><strong data-job-title></strong><progress data-progress style="display:block;width:100%;margin:10px 0"></progress><small data-progress-hint>进度自动刷新；服务重启期间会暂时断开并自动重连。</small></div>');
    const pull = document.createElement('button'); pull.textContent='拉取并更新'; pull.hidden=true; pull.dataset.pull=''; at('[data-rebuild]').insertAdjacentElement('beforebegin',pull);
    const activeJob = job => ['requested','downloading','waiting','pulling','building','switching','verifying','rolling_back','migrating'].includes(job?.phase);
    const jobTitle = job => ({requested:'准备更新',waiting:'等待任务空闲',pulling:'正在拉取源码',downloading:'正在下载并校验',building:'正在编译并重启',switching:'正在切换并重启',migrating:'正在切换并重启',verifying:'正在验证更新结果',rolling_back:'正在恢复上一版本',completed:'更新完成',downloaded:'已下载，等待安装',deferred:'更新已延后',rolled_back:'更新失败，已回滚',failed:'更新失败',recovery_required:'更新异常，需要检查恢复结果'})[job?.phase] || '';
    function checkText(value) {
        if (checking) return '正在检查更新…';
        const check = value.check;
        if (!check) return '尚未检查更新';
        if (value.mode === 'source' && check.kind !== 'source') return '尚未核对源码版本，请点击立即检查';
        if (text(check.runningVersion) && check.runningVersion !== value.currentVersion) return '版本已变化，请重新检查';
        if (text(check.error)) return '检查失败：' + friendly(check.error);
        if (value.mode === 'source') return text(check.message) || '尚未核对源码版本';
        return check.available ? `可更新至 ${text(check.version)}` : '已是最新版本';
    }
    function localTime(hour) {
        const date = new Date(); date.setUTCHours(hour, 0, 0, 0);
        return `${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')}`;
    }
    function utcHour(value) {
        if (!/^\d{2}:\d{2}$/.test(value)) throw new Error('请选择安装时段');
        const [hour, minute] = value.split(':').map(Number), date = new Date(); date.setHours(hour, minute, 0, 0);
        if (date.getUTCMinutes() !== 0) throw new Error('安装时段请按整小时调整');
        return date.getUTCHours();
    }
    async function request(path = '', method = 'GET', body) {
        const headers = { 'Content-Type': 'application/json' };
        const token = localStorage.getItem('token'); if (token) headers.Authorization = 'Bearer ' + token;
        const response = await fetch('/api/server-update' + path, { method, headers, credentials: 'same-origin', cache: 'no-store', body: body == null ? undefined : JSON.stringify(body) });
        const value = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(value.message || `请求失败 (${response.status})`);
        return value;
    }
    function render(value) {
        state = value;
        button.hidden = value.supported !== true;
        const source = value.mode === 'source', available = !source && !!value.check?.available;
        button.textContent = available ? '发现服务端更新' : '服务端更新';
        at('[data-current]').textContent = `当前版本 ${text(value.currentVersion)}`;
        at('[data-mode]').textContent = source ? (value.supportsSourcePull ? '源码运行 · 可拉取当前分支并更新' : '源码运行 · 编译当前磁盘源码') : '正式部署 · 下载发行包更新';
        at('[data-status]').textContent = checkText(value);
        at('[data-status]').style.fontWeight = at('[data-status]').textContent === '已是最新版本' ? '600' : '';
        jobStatus.textContent = submitting ? '' : friendly(value.job?.message);
        const progressTitle = submitting || (uncertain ? '正在核对更新任务是否已受理…' : jobTitle(value.job)), active = !!submitting || uncertain || activeJob(value.job);
        at('[data-job-progress]').hidden = !progressTitle;
        at('[data-job-title]').textContent = progressTitle;
        at('[data-progress]').hidden = !active;
        at('[data-progress-hint]').hidden = !active;
        at('[data-notes]').textContent = source ? '' : text(value.check?.releaseNotes);
        if (!value.installed) at('[data-status]').textContent = text(value.message);
        at('[data-policy]').hidden = source;
        if (!dialog.contains(document.activeElement)) at('select').value = value.policy?.mode || 'notify';
        at('select').disabled = pending || !value.installed;
        if (!dialog.contains(document.activeElement)) {
            at('[data-start]').value = localTime(value.policy?.startHourUtc ?? 18); at('[data-end]').value = localTime(value.policy?.endHourUtc ?? 22);
        }
        at('[data-window]').hidden = at('select').value !== 'automatic';
        const updating = uncertain || activeJob(value.job) || value.job?.phase === 'recovery_required';
        at('[data-install]').hidden = source; at('[data-install]').disabled = pending || !value.installed || !available || updating;
        at('[data-rebuild]').hidden = !value.canRebuild; at('[data-rebuild]').disabled = pending || updating;
        at('[data-rebuild]').textContent = value.supportsSourcePull ? '编译当前源码' : '编译并重启';
        pull.hidden = !source || !value.supportsSourcePull; pull.disabled = pending || updating || !value.installed;
        at('[data-force-row]').hidden = !value.supportsForce;
        at('[data-force]').disabled = pending || updating;
        at('[data-save]').disabled = pending || !value.installed;
        at('[data-check]').disabled = pending;
        if (!pending && !uncertain) reconnecting = activeJob(value.job);
        const key = 'remotetool:server-update:' + value.check?.releaseSequence;
        if (available && !sessionStorage.getItem(key)) { sessionStorage.setItem(key, '1'); if (!dialog.open) dialog.showModal(); }
    }
    async function action(work, progressText = '') {
        if (pending) return; pending = true; revision++; submitting = progressText; at('[data-error]').textContent = ''; if (state) render(state);
        try { render(await work()); } catch (error) {
            if (progressText) { uncertain = true; reconnecting = true; }
            at('[data-error]').textContent = friendly(error.message) + (progressText ? '；正在核对任务是否已受理，请勿重复提交' : '');
        }
        finally { pending = false; submitting = ''; if (state) render(state); schedulePoll(0); }
    }
    button.onclick = () => { dialog.showModal(); action(() => request()); };
    at('[data-close]').onclick = () => dialog.close();
    dialog.addEventListener('close', () => { at('[data-force]').checked = false; });
    at('[data-check]').onclick = () => { if(pending)return;checking=true; action(async () => {try{return await request('/check','POST',{});}finally{checking=false;}}); };
    at('[data-save]').onclick = () => {
        try {
            const policy = { mode: at('select').value, startHourUtc: utcHour(at('[data-start]').value), endHourUtc: utcHour(at('[data-end]').value) };
            action(() => request('/settings', 'PUT', policy));
        } catch (error) { at('[data-error]').textContent = friendly(error.message); }
    };
    at('select').onchange = () => { at('[data-window]').hidden = at('select').value !== 'automatic'; };
    const force = () => state.supportsForce === true && at('[data-force]').checked;
    pull.onclick = () => {
        const interrupt=force();
        if(!confirm(interrupt ? '中断任务，拉取当前分支最新源码，编译并重启？本地有未提交改动时会拒绝拉取。' : '等待空闲后拉取当前分支最新源码，编译并重启？本地有未提交改动时会拒绝拉取。'))return;
        reconnecting=true;action(async()=>{const result=await request('/rebuild','POST',{force:interrupt,pullSource:true});at('[data-force]').checked=false;return result;}, '正在提交拉取并更新请求…');
    };
    at('[data-install]').onclick = () => {
        const interrupt = force();
        if (!confirm(interrupt ? '中断 RemoteTool 的 Codex 任务和连接，立即更新并重启？' : '等待任务和连接空闲后，更新并重启？')) return;
        reconnecting = true; action(async () => { const result = await request('/install', 'POST', { sequence: state.check.releaseSequence, force: interrupt }); at('[data-force]').checked = false; return result; }, '正在提交更新请求…');
    };
    at('[data-rebuild]').onclick = () => {
        const interrupt = force();
        if (!confirm(interrupt ? '中断 RemoteTool 的 Codex 任务和连接，编译当前磁盘源码并重启？' : '等待空闲后，编译当前磁盘源码并重启？')) return;
        reconnecting = true; action(async () => { const result = await request('/rebuild', 'POST', { force: interrupt }); at('[data-force]').checked = false; return result; }, '正在提交编译并重启请求…');
    };
    function schedulePoll(delay) { clearTimeout(pollTimer); pollTimer = setTimeout(poll, delay); }
    async function poll() {
        if (pending || polling) { schedulePoll(250); return; }
        polling = true; const started = revision;
        try { const value = await request(); if (started === revision && !pending) { uncertain = false; render(value); } }
        catch { if (reconnecting && started === revision) jobStatus.textContent = '服务端更新中，正在重连并核对结果…'; }
        finally { polling = false; schedulePoll(pending ? 250 : reconnecting || dialog.open ? 2000 : 60000); }
    }
    poll();
})();
