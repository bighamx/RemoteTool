document.addEventListener('DOMContentLoaded', () => {
    const shellNav = document.getElementById('shell-nav');
    if (shellNav && window.Vue && window.ViewUIPlus) {
        const shell = Vue.createApp({ data: () => ({ groups: [
            { title: '概览', items: [{ key: 'home', href: '/', icon: '/icons/home.svg', label: '总览' }] },
            { title: '设备管理', items: [
                { key: 'controls', href: '/remote/index#tab=controls', icon: '/icons/speed.svg', label: '系统与监控' },
                { key: 'processes', href: '/remote/index#tab=processes', icon: '/icons/memory.svg', label: '进程' },
                { key: 'screenshot', href: '/remote/index#tab=screenshot', icon: '/icons/desktop_windows.svg', label: '远程桌面' },
                { key: 'terminal', href: '/remote/index#tab=terminal', icon: '/icons/terminal.svg', label: '终端' },
                { key: 'docker', href: '/remote/index#tab=docker', icon: '/icons/layers.svg', label: 'Docker' },
                { key: 'files', href: '/remote/index#tab=files', icon: '/icons/folder.svg', label: '文件' }
            ] },
            { title: '自动化与智能体', items: [
                { key: 'hangfire', href: '/hangfire', icon: '/icons/schedule.svg', label: '定时任务' },
                { key: 'codex', href: '/agents/codex', icon: '/icons/codex.png', label: 'Codex' },
                { key: 'hermes', href: '/agents/hermes', icon: '/icons/hermes.svg', label: 'Hermes' }
            ] }
        ] }) });
        shell.use(ViewUIPlus);
        shell.mount(shellNav);
    }
    const titleNode = document.getElementById('workspace-topbar-title');
    if (titleNode && window.Vue) {
        const titleApp = Vue.createApp({
            data: () => ({ currentTitle: titleNode.dataset.default || 'RemoteTool' }),
            methods: {
                sync() {
                    if (!location.pathname.toLowerCase().startsWith('/remote')) return;
                    const tab = new URLSearchParams(location.hash.slice(1)).get('tab') || 'controls';
                    this.currentTitle = ({ controls:'系统与监控', processes:'进程管理', screenshot:'远程桌面', terminal:'命令终端', docker:'Docker', images:'Docker', compose:'Docker', files:'文件管理' })[tab] || '远程管理';
                }
            },
            mounted() { this.sync(); window.addEventListener('hashchange', this.sync); window.addEventListener('remote-tab-change', this.sync); },
            beforeUnmount() { window.removeEventListener('hashchange', this.sync); window.removeEventListener('remote-tab-change', this.sync); }
        });
        titleApp.use(ViewUIPlus);
        titleApp.mount(titleNode);
    }
    // Theme Logic
    const themeToggle = document.getElementById('theme-toggle');
    const savedTheme = localStorage.getItem('theme') || 'dark';

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        if (themeToggle) {
            themeToggle.textContent = theme === 'dark' ? '☀️' : '🌙';
            themeToggle.title = theme === 'dark' ? '切换到浅色模式' : '切换到深色模式';
        }
    }
    applyTheme(savedTheme);
    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const currentTheme = document.documentElement.getAttribute('data-theme');
            const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
            localStorage.setItem('theme', newTheme);
            applyTheme(newTheme);
        });
    }

    // User Info & Logout
    const username = localStorage.getItem('username');
    const userInfo = document.getElementById('user-info');
    if (userInfo && username) userInfo.textContent = username;

    const logoutBtn = document.getElementById('logout-btn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', () => {
            localStorage.removeItem('token');
            localStorage.removeItem('username');
            window.location.href = '/Account/Logout';
        });
    }

    const sidebar = document.getElementById('app-sidebar');
    const scrim = document.getElementById('sidebar-scrim');
    const toggle = document.getElementById('sidebar-toggle');
    const closeSidebar = () => {
        sidebar?.classList.remove('open');
        if (scrim) scrim.hidden = true;
        toggle?.setAttribute('aria-expanded', 'false');
    };
    toggle?.addEventListener('click', () => {
        const open = sidebar.classList.toggle('open');
        if (scrim) scrim.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
    });
    scrim?.addEventListener('click', closeSidebar);
    document.addEventListener('keydown', event => { if (event.key === 'Escape') closeSidebar(); });
    document.querySelectorAll('.side-nav a').forEach(link => link.addEventListener('click', closeSidebar));

    function updateNavigation() {
        const path = location.pathname.toLowerCase();
        let current = 'home';
        if (path.startsWith('/agents/')) current = path.includes('hermes') ? 'hermes' : 'codex';
        else if (path.startsWith('/hangfire')) current = 'hangfire';
        else if (path.startsWith('/remote')) {
            const tab = new URLSearchParams(location.hash.slice(1)).get('tab') || 'controls';
            current = ['docker', 'images', 'compose'].includes(tab) ? 'docker' : tab;
        }
        document.querySelectorAll('.side-nav a').forEach(link => {
            const active = link.dataset.nav === current;
            link.classList.toggle('active', active);
            if (active) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
        });
    }
    window.addEventListener('hashchange', updateNavigation);
    window.addEventListener('remote-tab-change', updateNavigation);
    updateNavigation();
});
