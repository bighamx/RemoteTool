(() => {
    // The server still serves the older URLs for bookmarks and API clients. The workbench
    // keeps every mounted view in one document so navigating away preserves its state.
    const remoteTabs = {
        system: 'controls', processes: 'processes', desktop: 'screenshot', terminal: 'terminal',
        docker: 'docker', 'docker/images': 'images', 'docker/compose': 'compose', files: 'files'
    };
    const sources = { storage:'/storage', cloudflare: '/cloudflare', home: '/', remote: '/remote/index', jobs: '/hangfire', codex: '/agents/codex', hermes: '/agents/hermes', identity: '/settings/auth' };
    const assets = {
        storage: {styles:['workspace-pages.css','storage.css'],scripts:['web-api.js','storage.js']},
        cloudflare: { styles: ['workspace-pages.css', 'cloudflare.css'], scripts: ['web-api.js', 'cloudflare.js'] },
        remote: { styles: ['remote.css', 'performance.css', 'vendor/xterm/xterm.css'], scripts: ['remote-nav.js', 'mp4box.all.min.js', 'performance.js', 'media-preview.js', 'vendor/xterm/xterm.js', 'vendor/xterm/addon-fit.js', 'remote.js'] },
        jobs: { styles: ['workspace-pages.css'], scripts: ['web-api.js', 'jobs-page.js'] },
        codex: { styles: ['workspace-pages.css', 'agent-workspace.css'], scripts: ['web-api.js', 'web-sse.js', 'vendor/markdown-it/markdown-it.min.js', 'agent-history-cache.js', 'agent-workspace.js'] },
        hermes: { styles: ['workspace-pages.css', 'agent-workspace.css'], scripts: ['web-api.js', 'web-sse.js', 'vendor/markdown-it/markdown-it.min.js', 'agent-history-cache.js', 'agent-workspace.js'] },
        identity: { styles: ['workspace-pages.css', 'identity-settings.css'], scripts: ['web-api.js', 'oidc-settings.js'] }
    };
    const titles = { storage:'空间管理', cloudflare: 'Cloudflare', home: '总览', system: '系统与监控', processes: '进程', desktop: '远程桌面', terminal: '终端', docker: 'Docker', 'docker/images': '镜像', 'docker/compose': 'Compose', files: '文件', jobs: '定时任务', codex: 'Codex', hermes: 'Hermes', identity: '身份验证' };
    const loadedScripts = new Set([...document.scripts].map(script => new URL(script.src, location.href).pathname));
    const loadedStyles = new Set([...document.querySelectorAll('link[rel="stylesheet"]')].map(link => new URL(link.href, location.href).pathname));
    const pendingScripts = new Map();
    const pendingStyles = new Map();
    const modules = new Map();
    const main = document.getElementById('main-content');
    if (!main) return;
    window.RemoteToolOnReady = callback => document.readyState === 'loading'
        ? document.addEventListener('DOMContentLoaded', callback, { once: true }) : callback();

    const legacy = () => {
        const path = location.pathname.toLowerCase();
        if (path === '/storage') return 'storage';
        if (path === '/cloudflare') return 'cloudflare';
        if (path === '/settings/auth') return 'identity';
        if (path.startsWith('/agents/')) return path.includes('hermes') ? 'hermes' : 'codex';
        if (path === '/hangfire') return 'jobs';
        if (path.startsWith('/remote')) {
            const tab = new URLSearchParams(location.hash.slice(1)).get('tab') || 'controls';
            return Object.keys(remoteTabs).find(key => remoteTabs[key] === tab) || 'system';
        }
        return 'home';
    };
    const selectedRoute = () => {
        try {
            const route = decodeURIComponent(location.hash.match(/^#\/([^?]*)/)?.[1] || '');
            return Object.hasOwn(titles, route) ? route : 'home';
        } catch { return 'home'; }
    };
    const moduleKey = route => Object.hasOwn(remoteTabs, route) ? 'remote' : route;
    const openStyle = name => {
        const path = name.startsWith('vendor/') ? `/${name}` : `/css/${name}`;
        if (loadedStyles.has(path)) return pendingStyles.get(path) || Promise.resolve();
        loadedStyles.add(path);
        const loading = new Promise((resolve, reject) => {
            const link = document.createElement('link'); link.rel = 'stylesheet';
            // 与脚本一致带构建版本号：否则浏览器会长期复用旧的动态样式（改动不生效）。
            link.href = `${path}?v=${encodeURIComponent(document.body?.dataset.build || '')}`;
            link.onload = () => { pendingStyles.delete(path); resolve(); };
            link.onerror = () => { pendingStyles.delete(path); loadedStyles.delete(path); reject(new Error(`样式加载失败：${name}`)); };
            document.head.append(link);
        });
        pendingStyles.set(path, loading);
        return loading;
    };
    const openScript = (name, root) => {
        const path = name.startsWith('vendor/') ? `/${name}` : `/js/${name}`;
        if (loadedScripts.has(path) && name !== 'agent-workspace.js') return pendingScripts.get(path) || Promise.resolve();
        loadedScripts.add(path);
        const loading = new Promise((resolve, reject) => {
            const script = document.createElement('script');script.async = false;
            // Lazy-loaded scripts need the same cache version as styles after an update.
            script.src = `${path}?v=${encodeURIComponent(document.body?.dataset.build || '')}`;
            if (root) script.dataset.agentRootId = root.id;
            script.onload = () => { pendingScripts.delete(path); resolve(); };
            script.onerror = () => { pendingScripts.delete(path); loadedScripts.delete(path); reject(new Error(`脚本加载失败：${name}`)); };
            document.body.append(script);
        });
        if (name !== 'agent-workspace.js') pendingScripts.set(path, loading);
        return loading;
    };
    const page = async key => {
        if (modules.has(key)) return modules.get(key);
        const loading = (async () => {
            const warmed = warmPages.get(key); warmPages.delete(key);
            let text = warmed ? await warmed.catch(() => null) : null;
            if (text == null) {
                const response = await fetch(sources[key], { credentials: 'same-origin', cache: 'no-store' });
                if (response.redirected && new URL(response.url).pathname.toLowerCase().startsWith('/account/login')) {
                    location.assign(response.url); throw new Error('登录已过期');
                }
                if (!response.ok) throw new Error(`页面加载失败 (${response.status})`);
                text = await response.text();
            }
            const html = new DOMParser().parseFromString(text, 'text/html');
            const body = html.getElementById('main-content');
            if (!body) throw new Error('页面内容不可用');
            const section = document.createElement('section'); section.className = 'workspace-module';
            section.id = `workspace-${key}`; section.dataset.module = key; section.hidden = true;
            if (key === 'remote') {
                const tabs = html.getElementById('remote-nav-app');
                if (tabs) section.append(document.importNode(tabs, true));
            }
            for (const node of [...body.childNodes]) section.append(document.importNode(node, true));
            if (key === 'codex' || key === 'hermes') {
                const agent = section.querySelector('[data-agent]');
                if (agent) agent.id = `agent-${key}-app`;
            }
            main.append(section);
            try {
                await Promise.all((assets[key]?.styles || []).map(openStyle));
                await Promise.all((assets[key]?.scripts || []).map(script => openScript(script, section)));
            } catch (error) { section.remove(); throw error; }
            return section;
        })();
        modules.set(key, loading);
        try { return await loading; } catch (error) { modules.delete(key); throw error; }
    };
    const home = document.createElement('section'); home.id = 'workspace-home';
    home.className = 'workspace-module'; home.dataset.module = 'home'; home.hidden = true;
    while (main.firstChild) home.append(main.firstChild);
    main.append(home);
    modules.set('home', Promise.resolve(home));

    const pending = document.createElement('section');
    pending.className = 'workspace-module workspace-route-state'; pending.hidden = true;
    const pendingTitle = document.createElement('h1');
    const pendingStatus = document.createElement('p'); pendingStatus.setAttribute('role', 'status');
    const retry = document.createElement('button'); retry.type = 'button'; retry.textContent = '重试'; retry.hidden = true;
    pending.append(pendingTitle, pendingStatus, retry); main.append(pending);
    let generation = 0;
    const show = async () => {
        const current = ++generation;
        const route = selectedRoute();
        const key = moduleKey(route);
        document.title = `${titles[route]} · RemoteTool`;
        pendingTitle.textContent = titles[route]; pendingStatus.textContent = '正在加载…';
        pendingStatus.setAttribute('role', 'status'); retry.hidden = true;
        for (const element of main.querySelectorAll(':scope > .workspace-module')) element.hidden = element !== pending;
        main.setAttribute('aria-busy', 'true');
        document.body.classList.remove('workspace-fitted', 'workspace-files-visible', 'workspace-agent-visible', 'mobile-chat-active');
        if (key !== 'remote' && window.remoteControl) {
            window.remoteControl.stopSysInfoAutoRefresh();
            if (window.remoteControl.streamActive) window.remoteControl.stopStream();
        }
        window.dispatchEvent(new Event('workspace-route-change'));
        try {
            const section = await page(key);
            if (current !== generation) return;
            main.removeAttribute('aria-busy');
            for (const element of main.querySelectorAll(':scope > .workspace-module')) element.hidden = element !== section;
            document.body.classList.toggle('workspace-fitted', route === 'terminal' || route === 'desktop');
            document.body.classList.toggle('workspace-files-visible', route === 'files');
            document.body.classList.toggle('workspace-agent-visible', key === 'codex' || key === 'hermes');
            if (key === 'remote') {
                const tab = remoteTabs[route];
                const activate = () => {
                    if (selectedRoute() !== route || !window.remoteControl?.workspaceReady) return;
                    const params = new URLSearchParams(location.hash.split('?')[1] || '');
                    const filePath = route === 'files' ? params.get('path') : null;
                    if (window.remoteControl.currentTab !== tab)
                        window.remoteControl.switchTab(tab, true, !filePath);
                    if (filePath && filePath !== window.remoteControl.currentPath)
                        window.remoteControl.loadFiles(filePath);
                    if (route === 'docker/compose' && params.get('composePath')) {
                        const input = document.getElementById('compose-file-path-input');
                        if (input) { input.value = params.get('composePath'); window.remoteControl.loadComposeFile(); }
                    }
                };
                if (window.remoteControl?.workspaceReady) activate();
                else window.addEventListener('remote-control-ready', activate, { once: true });
            }
            document.title = `${titles[route]} · RemoteTool`;
            window.dispatchEvent(new Event('workspace-route-change'));
        } catch (error) {
            if (current === generation) {
                main.removeAttribute('aria-busy');
                pendingStatus.setAttribute('role', 'alert'); pendingStatus.textContent = error.message;
                retry.hidden = false;
                window.dispatchEvent(new Event('workspace-route-change'));
            }
        }
    };
    retry.addEventListener('click', show);
    window.RemoteToolWorkspace = {
        remote(tab, path) {
            const route = Object.keys(remoteTabs).find(key => remoteTabs[key] === tab) || 'system';
            const composePath = route === 'docker/compose' ? path || document.getElementById('compose-file-path-input')?.value.trim() : '';
            const query = route === 'files' && path ? `?path=${encodeURIComponent(path)}`
                : composePath ? `?composePath=${encodeURIComponent(composePath)}` : '';
            if (location.hash !== `#/${route}${query}`) location.hash = `/${route}${query}`;
        }
    };
    document.addEventListener('click', event => {
        const link = event.target.closest('a[href]');
        if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || link.target || link.hasAttribute('download')) return;
        const url = new URL(link.href, location.href);
        if (url.origin !== location.origin) return;
        if (url.pathname.toLowerCase() === '/remote/index') {
            const params = new URLSearchParams(url.hash.slice(1));
            event.preventDefault(); window.RemoteToolWorkspace.remote(params.get('tab') || 'controls', params.get('path') || params.get('composePath'));
        } else {
            const path = url.pathname.toLowerCase();
            const route = path === '/storage' ? 'storage' : path === '/cloudflare' ? 'cloudflare' : path === '/settings/auth' ? 'identity' : path === '/hangfire' ? 'jobs' : path.startsWith('/agents/') ? (path.includes('hermes') ? 'hermes' : 'codex') : null;
            if (route) { event.preventDefault(); location.hash = `/${route}`; }
        }
    });
    if (location.pathname !== '/' && location.pathname.toLowerCase() !== '/home/index') {
        const route = legacy();
        const params = new URLSearchParams(location.hash.slice(1));
        const filePath = route === 'files' ? params.get('path') : null;
        const composePath = route === 'docker/compose' ? params.get('composePath') : null;
        const session = route === 'codex' || route === 'hermes' ? new URLSearchParams(location.search).get('session') : null;
        const query = filePath ? `?path=${encodeURIComponent(filePath)}` : composePath ? `?composePath=${encodeURIComponent(composePath)}`
            : session ? `?session=${encodeURIComponent(session)}` : '';
        location.replace(`/#/${route}${query}`); return;
    }
    // Over a high-latency tunnel the first visit to a tab used to wait for its HTML, styles and scripts
    // in sequence. Once the current page is up, fetch the main tabs' markup and warm the HTTP cache for
    // their (immutable, versioned) assets in the background; nothing is mounted or executed early.
    const warmPages = new Map();
    const warmAsset = (name, as) => {
        const path = name.startsWith('vendor/') ? `/${name}` : `/${as === 'style' ? 'css' : 'js'}/${name}`;
        if ((as === 'style' ? loadedStyles : loadedScripts).has(path)) return;
        const link = document.createElement('link'); link.rel = 'prefetch'; link.as = as;
        link.href = `${path}?v=${encodeURIComponent(document.body?.dataset.build || '')}`;
        document.head.append(link);
    };
    const warm = () => {
        if (navigator.connection?.saveData) return;
        for (const key of ['codex', 'remote', 'hermes']) {
            if (modules.has(key) || warmPages.has(key)) continue;
            warmPages.set(key, fetch(sources[key], { credentials: 'same-origin', cache: 'no-store' })
                .then(response => response.ok && !response.redirected ? response.text() : null).catch(() => null));
            for (const style of assets[key]?.styles || []) warmAsset(style, 'style');
            for (const script of assets[key]?.scripts || []) warmAsset(script, 'script');
        }
    };
    const scheduleWarm = () => setTimeout(() => (window.requestIdleCallback || (callback => callback()))(warm, { timeout: 4000 }), 1200);
    window.addEventListener('hashchange', show);
    show().finally(() => document.readyState === 'complete' ? scheduleWarm() : window.addEventListener('load', scheduleWarm, { once: true }));
})();
