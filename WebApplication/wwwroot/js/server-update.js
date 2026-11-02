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
    let state, pending = false, reconnecting = false;
    const at = selector => dialog.querySelector(selector);
    const text = value => value == null || value === 'null' ? '' : String(value);
    const friendly = value => text(value).replace(/Response status code does not indicate success: (\d+) \([^)]+\)\.?/g, '服务端请求失败（HTTP $1）');
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
        at('[data-mode]').textContent = source ? '源码运行 · 使用当前磁盘源码编译，不自动拉取代码' : '正式部署 · 下载发行包更新';
        at('[data-status]').textContent = friendly(value.job?.message || (!source && value.check?.error) || (available ? `可更新至 ${text(value.check.version)}` : source ? '编译完成后会自动重启服务端' : value.check ? '当前没有可用更新' : '尚未检查更新'));
        at('[data-notes]').textContent = source ? '' : text(value.check?.releaseNotes);
        if (!value.installed) at('[data-status]').textContent = text(value.message);
        at('[data-policy]').hidden = source;
        if (!dialog.contains(document.activeElement)) at('select').value = value.policy?.mode || 'notify';
        at('select').disabled = pending || !value.installed;
        if (!dialog.contains(document.activeElement)) {
            at('[data-start]').value = localTime(value.policy?.startHourUtc ?? 18); at('[data-end]').value = localTime(value.policy?.endHourUtc ?? 22);
        }
        at('[data-window]').hidden = at('select').value !== 'automatic';
        const updating = ['requested','downloading','waiting','building','switching','verifying','rolling_back','migrating','recovery_required'].includes(value.job?.phase);
        at('[data-install]').hidden = source; at('[data-install]').disabled = pending || !value.installed || !available || updating;
        at('[data-rebuild]').hidden = !value.canRebuild; at('[data-rebuild]').disabled = pending || updating;
        at('[data-force-row]').hidden = !value.supportsForce;
        at('[data-force]').disabled = pending || updating;
        at('[data-save]').disabled = pending || !value.installed;
        if (['completed','rolled_back','failed','recovery_required'].includes(value.job?.phase)) reconnecting = false;
        const key = 'remotetool:server-update:' + value.check?.releaseSequence;
        if (available && !sessionStorage.getItem(key)) { sessionStorage.setItem(key, '1'); if (!dialog.open) dialog.showModal(); }
    }
    async function action(work) {
        if (pending) return; pending = true; at('[data-error]').textContent = ''; if (state) render(state);
        try { render(await work()); } catch (error) { at('[data-error]').textContent = friendly(error.message); }
        finally { pending = false; if (state) render(state); }
    }
    button.onclick = () => { dialog.showModal(); action(() => request()); };
    at('[data-close]').onclick = () => dialog.close();
    dialog.addEventListener('close', () => { at('[data-force]').checked = false; });
    at('[data-check]').onclick = () => action(() => request('/check', 'POST', {}));
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
        reconnecting = true; action(async () => { const result = await request('/install', 'POST', { sequence: state.check.releaseSequence, force: interrupt }); at('[data-force]').checked = false; return result; });
    };
    at('[data-rebuild]').onclick = () => {
        const interrupt = force();
        if (!confirm(interrupt ? '中断 RemoteTool 的 Codex 任务和连接，编译当前磁盘源码并重启？' : '等待空闲后，编译当前磁盘源码并重启？')) return;
        reconnecting = true; action(async () => { const result = await request('/rebuild', 'POST', { force: interrupt }); at('[data-force]').checked = false; return result; });
    };
    async function poll() {
        try { render(await request()); }
        catch { if (reconnecting) at('[data-status]').textContent = '服务端更新中，正在重连并核对结果…'; }
        finally { setTimeout(() => { if (document.visibilityState === 'visible' || reconnecting) poll(); else setTimeout(poll, 60000); }, reconnecting ? 3000 : 60000); }
    }
    poll();
})();
