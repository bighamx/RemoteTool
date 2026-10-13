(() => {
    // The server still serves the older URLs for bookmarks and API clients. The workbench
    // keeps every mounted view in one document so navigating away preserves its state.
    const remoteTabs = {
        system: 'controls', processes: 'processes', desktop: 'screenshot', terminal: 'terminal',
        docker: 'docker', 'docker/images': 'images', 'docker/compose': 'compose', files: 'files'
    };
    const sources = { home: '/', remote: '/remote/index', jobs: '/hangfire', codex: '/agents/codex', hermes: '/agents/hermes' };
    const assets = {
        remote: { styles: ['remote.css', 'performance.css', 'vendor/xterm/xterm.css'], scripts: ['remote-nav.js', 'mp4box.all.min.js', 'performance.js', 'media-preview.js', 'vendor/xterm/xterm.js', 'vendor/xterm/addon-fit.js', 'remote.js'] },
        jobs: { styles: ['workspace-pages.css'], scripts: ['web-api.js', 'jobs-page.js'] },
        codex: { styles: ['workspace-pages.css', 'agent-workspace.css'], scripts: ['web-api.js', 'web-sse.js', 'agent-workspace.js'] },
        hermes: { styles: ['workspace-pages.css', 'agent-workspace.css'], scripts: ['web-api.js', 'web-sse.js', 'agent-workspace.js'] }
    };
    const titles = { home: '总览', system: '系统与监控', processes: '进程', desktop: '远程桌面', terminal: '终端', docker: 'Docker', 'docker/images': '镜像', 'docker/compose': 'Compose', files: '文件', jobs: '定时任务', codex: 'Codex', hermes: 'Hermes' };
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
            const link = document.createElement('link'); link.rel = 'stylesheet'; link.href = path;
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
            const script = document.createElement('script');
            // agent-workspace.js 由路由按需加载、不进服务端布局，没有 asp-append-version 时间戳；
            // 带上构建时间，避免部署后浏览器仍执行缓存的旧脚本（曾导致改了 JS 刷新无效、报错行号对不上）。
            script.src = name === 'agent-workspace.js' ? `${path}?v=${encodeURIComponent(document.body?.dataset.build || '')}` : path;
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
            const response = await fetch(sources[key], { credentials: 'same-origin', cache: 'no-store' });
            if (response.redirected && new URL(response.url).pathname.toLowerCase().startsWith('/account/login')) {
                location.assign(response.url); throw new Error('登录已过期');
            }
            if (!response.ok) throw new Error(`页面加载失败 (${response.status})`);
            const html = new DOMParser().parseFromString(await response.text(), 'text/html');
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
                for (const style of assets[key]?.styles || []) await openStyle(style);
                for (const script of assets[key]?.scripts || []) await openScript(script, section);
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

    let generation = 0;
    const show = async () => {
        const current = ++generation;
        const route = selectedRoute();
        const key = moduleKey(route);
        try {
            const section = await page(key);
            if (current !== generation) return;
            for (const element of main.querySelectorAll(':scope > .workspace-module')) element.hidden = element !== section;
            document.body.classList.toggle('workspace-fitted', route === 'terminal' || route === 'desktop');
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
            } else if (window.remoteControl) {
                window.remoteControl.stopSysInfoAutoRefresh();
                if (window.remoteControl.streamActive) window.remoteControl.stopStream();
            }
            document.title = `${titles[route]} · RemoteTool`;
            window.dispatchEvent(new Event('workspace-route-change'));
        } catch (error) {
            if (current === generation) {
                home.hidden = false;
                document.body.classList.remove('workspace-fitted');
                document.body.classList.remove('workspace-agent-visible');
                for (const element of main.querySelectorAll(':scope > .workspace-module')) if (element !== home) element.hidden = true;
                const alert = document.createElement('p'); alert.className = 'workspace-alert'; alert.textContent = error.message;
                home.prepend(alert); window.dispatchEvent(new Event('workspace-route-change'));
            }
        }
    };
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
            const route = path === '/hangfire' ? 'jobs' : path.startsWith('/agents/') ? (path.includes('hermes') ? 'hermes' : 'codex') : null;
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
    window.addEventListener('hashchange', show);
    show();
})();
