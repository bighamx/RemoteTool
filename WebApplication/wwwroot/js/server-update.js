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
    dialog.innerHTML = '<h2>服务端更新</h2><p data-status></p><pre data-notes style="white-space:pre-wrap"></pre><p data-error role="alert"></p><label>更新方式 <select><option value="notify">仅提醒</option><option value="download">自动下载，手动安装</option><option value="automatic">空闲时自动更新并重启</option></select></label><p>自动安装时段（UTC） <input data-start type="number" min="0" max="23" style="width:55px"> 至 <input data-end type="number" min="0" max="23" style="width:55px"> 时</p><p><button data-save>保存设置</button> <button data-check>立即检查</button> <button data-install>更新并重启</button> <button data-rebuild hidden>重新编译当前源码</button> <button data-close>稍后</button></p>';
    document.body.append(dialog);
    let state, pending = false, reconnecting = false;
    const at = selector => dialog.querySelector(selector);
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
        const available = !!value.check?.available;
        button.textContent = available ? '发现服务端更新' : '服务端更新';
        at('[data-status]').textContent = value.job?.message || value.check?.error || (available ? `发现版本 ${value.check.version}` : value.check ? '当前没有可用更新' : '尚未检查更新');
        at('[data-notes]').textContent = value.check?.releaseNotes || value.message || '';
        at('select').value = value.policy?.mode || 'notify'; at('select').disabled = !value.installed;
        at('[data-start]').value = value.policy?.startHourUtc ?? 18; at('[data-end]').value = value.policy?.endHourUtc ?? 22;
        const updating = ['requested','downloading','waiting','building','switching','verifying','rolling_back','migrating','recovery_required'].includes(value.job?.phase);
        at('[data-install]').disabled = pending || !value.installed || !available || updating;
        at('[data-rebuild]').hidden = !value.canRebuild; at('[data-rebuild]').disabled = pending || updating;
        if (['completed','rolled_back','failed','recovery_required'].includes(value.job?.phase)) reconnecting = false;
        const key = 'remotetool:server-update:' + value.check?.releaseSequence;
        if (available && !sessionStorage.getItem(key)) { sessionStorage.setItem(key, '1'); if (!dialog.open) dialog.showModal(); }
    }
    async function action(work) {
        if (pending) return; pending = true; at('[data-error]').textContent = ''; if (state) render(state);
        try { render(await work()); } catch (error) { at('[data-error]').textContent = error.message; }
        finally { pending = false; if (state) render(state); }
    }
    button.onclick = () => { dialog.showModal(); action(() => request()); };
    at('[data-close]').onclick = () => dialog.close();
    at('[data-check]').onclick = () => action(() => request('/check', 'POST', {}));
    at('[data-save]').onclick = () => {
        const policy = { mode: at('select').value, startHourUtc: Number(at('[data-start]').value), endHourUtc: Number(at('[data-end]').value) };
        action(() => request('/settings', 'PUT', policy));
    };
    at('[data-install]').onclick = () => {
        if (!confirm('等待任务和连接空闲后，更新当前设备的服务端并重启？')) return;
        reconnecting = true; action(() => request('/install', 'POST', { sequence: state.check.releaseSequence }));
    };
    at('[data-rebuild]').onclick = () => {
        if (!confirm('等待空闲后停止 Web 和 codex-bridge，重新编译当前源码？不会拉取、切分支或重置 Git。')) return;
        reconnecting = true; action(() => request('/rebuild', 'POST', {}));
    };
    async function poll() {
        try { render(await request()); }
        catch { if (reconnecting) at('[data-status]').textContent = '服务端更新中，正在重连并核对结果…'; }
        finally { setTimeout(() => { if (document.visibilityState === 'visible' || reconnecting) poll(); else setTimeout(poll, 60000); }, reconnecting ? 3000 : 60000); }
    }
    poll();
})();
