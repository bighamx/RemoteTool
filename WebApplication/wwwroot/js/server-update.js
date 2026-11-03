(() => {
    if (location.pathname.toLowerCase().startsWith('/account/')) return;
    const button = document.querySelector('[data-server-update-entry]');
    if (!button) return;
    const dialog = document.createElement('dialog');
    dialog.className = 'server-update-dialog';
    dialog.setAttribute('aria-labelledby', 'server-update-title');
    // All remote strings are assigned with textContent, never interpreted as HTML.
    dialog.innerHTML = `
        <header class="server-update-head">
            <div class="server-update-heading"><span class="server-update-mark" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12m-4-4 4 4 4-4M4 16v4h16v-4"/></svg></span><h2 id="server-update-title">服务端更新</h2><span class="server-update-badge" data-deploy-label></span></div>
            <button class="server-update-close" data-close-header type="button" aria-label="关闭服务端更新">×</button>
        </header>
        <div class="server-update-body">
            <dl class="server-update-versions"><div><dt>运行版本</dt><dd data-current></dd></div><div data-local-version><dt>本地源码</dt><dd data-mode></dd></div></dl>
            <div class="server-update-status" data-check-state role="status"><span class="server-update-dot" aria-hidden="true"></span><p data-status></p></div>
            <section class="server-update-job" data-job-progress hidden role="status" aria-live="polite"><strong data-job-title></strong><progress data-progress aria-label="更新任务正在进行"></progress><p data-job></p><small data-progress-hint>进度自动刷新；服务重启期间会暂时断开并自动重连。</small></section>
            <section class="server-update-notes" data-release hidden><h3>更新说明</h3><pre data-notes></pre></section>
            <p class="server-update-error" data-error role="alert"></p>
            <section class="server-update-policy" data-policy>
                <label class="server-update-field">更新方式<select><option value="notify">仅提醒</option><option value="download">自动下载，手动安装</option><option value="automatic">空闲时自动更新并重启</option></select></label>
                <div class="server-update-window" data-window><span>自动安装时段（本地时间）</span><div><input data-start type="time" step="900" aria-label="自动安装开始时间"><span>至</span><input data-end type="time" step="900" aria-label="自动安装结束时间"></div></div>
                <button class="btn btn-secondary btn-sm" data-save type="button">保存设置</button>
            </section>
            <div class="server-update-force" data-force-row hidden><label><input data-force type="checkbox"><span>强制中断任务</span></label><small>仅本次生效：断开连接并中断 RemoteTool 的 Codex 任务；Hermes 独立任务继续运行。</small></div>
        </div>
        <footer class="server-update-footer"><button class="btn btn-secondary" data-check type="button">立即检查</button><div class="server-update-actions"><button class="btn btn-secondary" data-close type="button">关闭</button><button class="btn btn-primary" data-install type="button">更新并重启</button><button class="btn btn-primary" data-rebuild type="button" hidden>编译并重启</button></div></footer>`;
    document.body.append(dialog);
    let state, pending = false, reconnecting = false, checking = false, submitting = '', pollTimer, polling = false, revision = 0, uncertain = false, currentJobId = '';
    const at = selector => dialog.querySelector(selector);
    const text = value => value == null || value === 'null' ? '' : String(value);
    const friendly = value => text(value).replace(/Response status code does not indicate success: (\d+) \([^)]+\)\.?/g, '服务端请求失败（HTTP $1）');
    const jobStatus = at('[data-job]');
    function sourceAction(value) {
        if (value.mode !== 'source' || !value.canRebuild) return '';
        const check = value.check;
        const valid = check?.kind === 'source' && !text(check.error) && (!text(check.runningVersion) || check.runningVersion === value.currentVersion);
        if (valid) {
            if (check.state === 'up_to_date') return '';
            if (['source_changed','rebuild_required'].includes(check.state)) return 'compile';
            if (check.state === 'source_out_of_sync') return value.supportsSourcePull ? 'pull' : 'compile';
        }
        return value.supportsSourcePull ? '' : 'compile';
    }
    const activeJob = job => ['requested','downloading','waiting','pulling','building','switching','verifying','rolling_back','migrating'].includes(job?.phase);
    const jobTitle = job => {
        const outcome = {completed:'已完成',deferred:'已延后',rolled_back:'失败，已回滚',failed:'失败'}[job?.phase];
        if (outcome) return `${currentJobId && text(job.id) === currentJobId ? '本次' : '上次'}更新：${outcome}`;
        return ({requested:'准备更新',waiting:'等待任务空闲',pulling:'正在拉取源码',downloading:'正在下载并校验',building:'正在编译并重启',switching:'正在切换并重启',migrating:'正在切换并重启',verifying:'正在验证更新结果',rolling_back:'正在恢复上一版本',downloaded:'已下载，等待安装',recovery_required:'更新异常，需要检查恢复结果'})[job?.phase] || '';
    };
    function checkText(value) {
        if (checking) return '正在检查更新…';
        const check = value.check;
        if (!check) return '尚未检查更新';
        if (value.mode === 'source' && check.kind !== 'source') return '尚未核对源码版本，请点击立即检查';
        if (text(check.runningVersion) && check.runningVersion !== value.currentVersion) return '版本已变化，请重新检查';
        if (text(check.error)) return '检查失败：' + friendly(check.error);
        if (value.mode === 'source') return ({up_to_date:'已是最新版本',rebuild_required:'源码已是最新，需编译并重启服务端',source_changed:'本地源码有未提交改动，将仅编译本地源码',source_out_of_sync:'本地源码与远端不同，更新将先拉取源码'})[check.state] || text(check.message) || '尚未核对源码版本';
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
        if (dialog.open && activeJob(value.job) && text(value.job.id)) currentJobId = text(value.job.id);
        const source = value.mode === 'source', available = !source && !!value.check?.available;
        const verified = value.check && !text(value.check.error) &&
            (!text(value.check.runningVersion) || value.check.runningVersion === value.currentVersion);
        button.hidden = value.supported !== true || !value.installed || !verified || value.check.available !== true ||
            (source && (value.check.kind !== 'source' || !sourceAction(value)));
        at('[data-current]').textContent = text(value.currentVersion);
        at('[data-deploy-label]').textContent = source ? '源码部署' : '发行包部署';
        const localRevision = source && value.check?.kind === 'source' ? text(value.check.localRevision) : '';
        at('[data-local-version]').hidden = !localRevision;
        at('[data-mode]').textContent = localRevision.slice(0,12);
        at('[data-status]').textContent = checkText(value);
        at('[data-check-state]').dataset.state = checking ? 'checking' : value.check?.error || !value.installed ? 'error' : at('[data-status]').textContent === '已是最新版本' ? 'latest' : sourceAction(value) || available ? 'available' : 'unknown';
        jobStatus.textContent = submitting ? '' : friendly(value.job?.message);
        const progressTitle = submitting || (uncertain ? '正在核对更新任务是否已受理…' : jobTitle(value.job)), active = !!submitting || uncertain || activeJob(value.job);
        at('[data-job-progress]').hidden = !progressTitle;
        at('[data-job-title]').textContent = progressTitle;
        at('[data-progress]').hidden = !active;
        at('[data-progress-hint]').hidden = !active;
        at('[data-notes]').textContent = source ? '' : text(value.check?.releaseNotes);
        at('[data-release]').hidden = source || !text(value.check?.releaseNotes);
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
        const next = sourceAction(value);
        at('[data-rebuild]').hidden = !next; at('[data-rebuild]').disabled = pending || updating || !value.installed;
        at('[data-rebuild]').textContent = next === 'pull' ? '拉取并更新' : '编译并重启';
        at('[data-force-row]').hidden = !value.supportsForce || !(source ? next : available);
        at('[data-force]').disabled = pending || updating;
        at('[data-save]').disabled = pending || !value.installed;
        at('[data-check]').disabled = pending;
        if (!pending && !uncertain) reconnecting = activeJob(value.job);
        const key = 'remotetool:server-update:' + value.check?.releaseSequence;
        if (available && !sessionStorage.getItem(key)) { sessionStorage.setItem(key, '1'); if (!dialog.open) dialog.showModal(); }
    }
    async function action(work, progressText = '') {
        if (pending) return; pending = true; const requestRevision = ++revision; submitting = progressText; at('[data-error]').textContent = ''; if (state) render(state);
        try {
            const result = await work();
            if (progressText && requestRevision === revision) currentJobId = text(result.job?.id);
            render(result);
        } catch (error) {
            if (progressText) { uncertain = true; reconnecting = true; }
            at('[data-error]').textContent = friendly(error.message) + (progressText ? '；正在核对任务是否已受理，请勿重复提交' : '');
        }
        finally { pending = false; submitting = ''; if (state) render(state); schedulePoll(0); }
    }
    button.onclick = () => { currentJobId = ''; dialog.showModal(); action(() => request()); };
    at('[data-close]').onclick = () => dialog.close();
    at('[data-close-header]').onclick = () => dialog.close();
    dialog.addEventListener('close', () => { revision++; at('[data-force]').checked = false; currentJobId = ''; });
    at('[data-check]').onclick = () => { if(pending)return;checking=true; action(async () => {try{return await request('/check','POST',{});}finally{checking=false;}}); };
    at('[data-save]').onclick = () => {
        try {
            const policy = { mode: at('select').value, startHourUtc: utcHour(at('[data-start]').value), endHourUtc: utcHour(at('[data-end]').value) };
            action(() => request('/settings', 'PUT', policy));
        } catch (error) { at('[data-error]').textContent = friendly(error.message); }
    };
    at('select').onchange = () => { at('[data-window]').hidden = at('select').value !== 'automatic'; };
    const force = () => state.supportsForce === true && at('[data-force]').checked;
    at('[data-install]').onclick = () => {
        const interrupt = force();
        if (!confirm(interrupt ? '中断 RemoteTool 的 Codex 任务和连接，立即更新并重启？' : '等待任务和连接空闲后，更新并重启？')) return;
        reconnecting = true; action(async () => { const result = await request('/install', 'POST', { sequence: state.check.releaseSequence, force: interrupt }); at('[data-force]').checked = false; return result; }, '正在提交更新请求…');
    };
    at('[data-rebuild]').onclick = () => {
        const next = sourceAction(state);
        if (!next || pending || uncertain || activeJob(state.job) || state.job?.phase === 'recovery_required' || !state.installed) return;
        const interrupt = force(), pullSource = next === 'pull';
        const operation = pullSource ? '拉取当前分支最新源码，编译并重启？本地有未提交改动时会拒绝拉取。' : '编译当前本地源码并重启？';
        if (!confirm((interrupt ? '中断 RemoteTool 的 Codex 任务和连接，' : '等待空闲后，') + operation)) return;
        reconnecting = true; action(async () => { const result = await request('/rebuild', 'POST', { force: interrupt, ...(pullSource ? {pullSource:true} : {}) }); at('[data-force]').checked = false; return result; }, pullSource ? '正在提交拉取并更新请求…' : '正在提交编译并重启请求…');
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
