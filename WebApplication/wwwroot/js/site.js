document.addEventListener('DOMContentLoaded', () => {
    const shellNav = document.getElementById('shell-nav');
    if (shellNav && window.Vue && window.ViewUIPlus) {
        const shell = Vue.createApp({ data: () => ({ groups: [
            { title: '概览', items: [{ key: 'home', href: '/#/', icon: '/icons/home.svg', label: '总览' }] },
            { title: '设备管理', items: [
                { key: 'controls', href: '/#/system', icon: '/icons/speed.svg', label: '系统与监控' },
                { key: 'phone-tools', href: '/#/phone-tools', icon: '/icons/smartphone.svg', label: '手机工具' },
                { key: 'processes', href: '/#/processes', icon: '/icons/memory.svg', label: '进程' },
                { key: 'screenshot', href: '/#/desktop', icon: '/icons/desktop_windows.svg', label: '远程桌面' },
                { key: 'terminal', href: '/#/terminal', icon: '/icons/terminal.svg', label: '终端' },
                { key: 'docker', href: '/#/docker', icon: '/icons/layers.svg', label: 'Docker' },
                { key: 'files', href: '/#/files', icon: '/icons/folder.svg', label: '文件' }
            ] },
            { title: '自动化与智能体', items: [
                { key: 'hangfire', href: '/#/jobs', icon: '/icons/schedule.svg', label: '定时任务' },
                { key: 'codex', href: '/#/codex', icon: '/icons/codex.png', label: 'Codex' },
                { key: 'hermes', href: '/#/hermes', icon: '/icons/hermes.svg', label: 'Hermes' }
            ] },
            { title: '设置', items: [
                { key: 'storage', href: '/#/storage', icon: '/icons/folder.svg', label: '空间管理' },
                { key: 'cloudflare', href: '/#/cloudflare', icon: '/icons/layers.svg', label: 'Cloudflare' },
                { key: 'identity', href: '/#/identity', icon: '/icons/shield.svg', label: '身份验证' }
            ] }
        ] }) });
        shell.use(ViewUIPlus);
        const navigation = shell.mount(shellNav);
        window.RemoteToolNavigation = navigation.groups.flatMap(group => group.items).map(item => ({...item}));
    }
    const titleNode = document.getElementById('workspace-topbar-title');
    if (titleNode && window.Vue) {
        const titleApp = Vue.createApp({
            data: () => ({ currentTitle: titleNode.dataset.default || 'RemoteTool' }),
            methods: {
                sync() {
                    const route = location.hash.match(/^#\/([^?]*)/)?.[1] || 'home';
                    this.currentTitle = ({ 'phone-tools':'手机工具', storage:'空间管理', home:'总览', cloudflare:'Cloudflare', system:'系统与监控', processes:'进程管理', desktop:'远程桌面', terminal:'命令终端', docker:'Docker', 'docker/images':'Docker 镜像', 'docker/compose':'Docker Compose', files:'文件管理', jobs:'定时任务', codex:'Codex', hermes:'Hermes', identity:'身份验证' })[route] || 'RemoteTool';
                }
            },
            mounted() { this.sync(); window.addEventListener('hashchange', this.sync); window.addEventListener('workspace-route-change', this.sync); },
            beforeUnmount() { window.removeEventListener('hashchange', this.sync); window.removeEventListener('workspace-route-change', this.sync); }
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

    const installButton = document.getElementById('install-app-btn');
    let installPrompt;
    window.addEventListener('beforeinstallprompt', event => {
        event.preventDefault();
        installPrompt = event;
        if (installButton) installButton.hidden = false;
    });
    installButton?.addEventListener('click', async () => {
        if (!installPrompt) return;
        await installPrompt.prompt();
        installPrompt = null;
        installButton.hidden = true;
    });
    window.addEventListener('appinstalled', () => { if (installButton) installButton.hidden = true; });
    if ('serviceWorker' in navigator && (location.protocol === 'https:' || ['localhost', '127.0.0.1'].includes(location.hostname)))
        navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => {});

    const logoutBtn = document.getElementById('logout-btn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', () => {
            localStorage.removeItem('token');
            localStorage.removeItem('username');
            window.location.href = '/Account/Logout';
        });
    }

    const mobileMedia=window.matchMedia('(max-width:760px), (max-width:1000px) and (max-height:500px) and (pointer:coarse)');
    const tools=document.getElementById('mobile-tools');
    const closeTools=()=>{if(tools?.open)tools.close();};
    document.querySelector('[data-mobile-nav="more"]')?.addEventListener('click',()=>tools?.showModal());
    tools?.querySelector('[data-close-tools]')?.addEventListener('click',closeTools);
    tools?.addEventListener('click',event=>{if(event.target.closest('a'))closeTools();});
    tools?.querySelector('[data-mobile-action="theme"]')?.addEventListener('click',()=>themeToggle?.click());
    tools?.querySelector('[data-mobile-action="logout"]')?.addEventListener('click',()=>logoutBtn?.click());
    const viewport=window.visualViewport;
    const virtualKeyboard=navigator.virtualKeyboard;
    let viewportWidth=innerWidth,viewportBaseline=innerHeight,viewportFrame=null,keyboardPoll=null,composerFocusedAt=0,composerMeasuredKeyboard=false;
    const keyboardSamples=[];
    const sampleKeyboard=reason=>{
        const composer=document.querySelector('.workspace-module:not([hidden]) .agent-compose-input-row');
        const bounds=composer?.getBoundingClientRect();
        const rect=virtualKeyboard?.boundingRect;
        const sample={reason,at:Date.now(),fallback:document.body.classList.contains('mobile-keyboard-unreported'),inner:[innerWidth,innerHeight],client:[document.documentElement.clientWidth,document.documentElement.clientHeight],visual:viewport?{height:viewport.height,top:viewport.offsetTop,scale:viewport.scale}:null,keyboard:rect?{y:rect.y,height:rect.height,overlays:virtualKeyboard.overlaysContent}:null,shellHeight:document.querySelector('.app-shell')?.getBoundingClientRect().height,composer:bounds?{top:bounds.top,bottom:bounds.bottom}:null,editing:!!document.activeElement?.matches('input,textarea,[contenteditable="true"]')};
        const signature=JSON.stringify({...sample,reason:null,at:null});
        if(reason!=='poll'||keyboardSamples.at(-1)?.signature!==signature){keyboardSamples.push({signature,sample});if(keyboardSamples.length>40)keyboardSamples.shift();}
    };
    const diagnostics=document.getElementById('keyboard-diagnostics');
    tools?.querySelector('[data-mobile-action="reload"]')?.addEventListener('click',()=>location.reload());
    tools?.querySelector('[data-mobile-action="keyboard"]')?.addEventListener('click',()=>{
        sampleKeyboard('report');
        document.getElementById('keyboard-diagnostics-text').value=JSON.stringify({version:'keyboard-unreported-4',browser:navigator.userAgent,standalone:matchMedia('(display-mode:standalone)').matches,samples:keyboardSamples.map(row=>row.sample)},null,2);
        closeTools();diagnostics?.showModal();
    });
    diagnostics?.querySelector('[data-close-keyboard]')?.addEventListener('click',()=>diagnostics.close());
    diagnostics?.querySelector('[data-copy-keyboard]')?.addEventListener('click',async event=>{
        const field=document.getElementById('keyboard-diagnostics-text');
        try{await navigator.clipboard.writeText(field.value);event.target.textContent='已复制';}catch{field.focus();field.select();}
    });
    const syncViewport=()=>{
        viewportFrame=null;
        try{if(virtualKeyboard&&virtualKeyboard.overlaysContent)virtualKeyboard.overlaysContent=false;}catch{}
        if(!mobileMedia.matches){closeTools();document.body.classList.remove('mobile-keyboard-open','mobile-keyboard-unreported');document.documentElement.style.removeProperty('--mobile-height');document.documentElement.style.removeProperty('--mobile-top');document.documentElement.style.removeProperty('--mobile-bottom-offset');return;}
        if(viewportWidth!==innerWidth){viewportWidth=innerWidth;viewportBaseline=innerHeight;}
        viewportBaseline=Math.max(viewportBaseline,innerHeight);
        const top=Math.max(0,viewport?.offsetTop||0);
        const keyboard=virtualKeyboard?.boundingRect;
        const keyboardTop=keyboard?.height>0?keyboard.y:Infinity;
        // Some Android hosts resize only the layout viewport, others overlay the keyboard.
        const height=Math.max(0,Math.min(innerHeight,document.documentElement.clientHeight||innerHeight,viewport?.height||innerHeight,keyboardTop-top));
        const editing=!!document.activeElement?.matches('input,textarea,[contenteditable="true"]');
        const measuredKeyboard=keyboard?.height>0||(editing&&viewportBaseline-height>120);
        const composerFocused=document.activeElement?.matches('.agent-compose-input-row textarea')&&document.activeElement.getClientRects().length>0;
        if(composerFocused&&measuredKeyboard)composerMeasuredKeyboard=true;
        // Some mobile launchers report no keyboard geometry at all. Keep editing above it.
        const continuing=document.body.classList.contains('mobile-keyboard-unreported')&&document.activeElement?.closest('.agent-compose-input-row,.mobile-finish-input');
        const unreported=!!(!measuredKeyboard&&(continuing||(composerFocused&&composerFocusedAt&&Date.now()-composerFocusedAt>=500&&!composerMeasuredKeyboard&&matchMedia('(pointer:coarse)').matches)));
        document.body.classList.toggle('mobile-keyboard-unreported',unreported);
        document.body.classList.toggle('mobile-keyboard-open',measuredKeyboard||unreported);
        document.documentElement.style.setProperty('--mobile-height',Math.round(height)+'px');
        document.documentElement.style.setProperty('--mobile-top',Math.round(top)+'px');
        document.documentElement.style.setProperty('--mobile-bottom-offset',Math.max(0,Math.round(innerHeight-height-top))+'px');
    };
    const queueViewport=()=>{if(viewportFrame===null)viewportFrame=requestAnimationFrame(syncViewport);};
    virtualKeyboard?.addEventListener('geometrychange',queueViewport);
    viewport?.addEventListener('resize',queueViewport);viewport?.addEventListener('scroll',queueViewport);
    window.addEventListener('resize',queueViewport);
    document.addEventListener('focusin',()=>{
        composerFocusedAt=document.activeElement?.matches('.agent-compose-input-row textarea')?Date.now():0;composerMeasuredKeyboard=false;
        queueViewport();sampleKeyboard('focus');
        if(keyboardPoll===null&&mobileMedia.matches)keyboardPoll=setInterval(()=>{queueViewport();requestAnimationFrame(()=>sampleKeyboard('poll'));},150);
    });
    document.addEventListener('focusout',()=>{
        composerFocusedAt=0;
        queueViewport();sampleKeyboard('blur');
        setTimeout(()=>{if(!document.activeElement?.matches('input,textarea,[contenteditable="true"]')){clearInterval(keyboardPoll);keyboardPoll=null;queueViewport();sampleKeyboard('settled');}},500);
    });
    mobileMedia.addEventListener('change',queueViewport);syncViewport();

    const sidebar = document.getElementById('app-sidebar');
    const scrim = document.getElementById('sidebar-scrim');
    const toggle = document.getElementById('sidebar-toggle');
    const closeSidebar = () => {
        sidebar?.classList.remove('open');
        if (scrim) scrim.hidden = true;
        toggle?.setAttribute('aria-expanded', 'false');
    };
    toggle?.addEventListener('click', () => {
        if(mobileMedia.matches){tools?.showModal();return;}
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
        const route = location.hash.match(/^#\/([^?]*)/)?.[1];
        if (route) current = ({home:'home','phone-tools':'phone-tools',storage:'storage',cloudflare:'cloudflare',identity:'identity',system:'controls',processes:'processes',desktop:'screenshot',terminal:'terminal',docker:'docker','docker/images':'docker','docker/compose':'docker',files:'files',jobs:'hangfire',codex:'codex',hermes:'hermes'})[route] || 'home';
        else if (path.startsWith('/agents/')) current = path.includes('hermes') ? 'hermes' : 'codex';
        else if (path.startsWith('/hangfire')) current = 'hangfire';
        else if (path.startsWith('/remote')) {
            const tab = new URLSearchParams(location.hash.slice(1)).get('tab') || 'controls';
            current = ['docker', 'images', 'compose'].includes(tab) ? 'docker' : tab;
        }
        if(!['codex','hermes'].includes(route))document.body.classList.remove('mobile-chat-active');
        closeTools();
        document.querySelectorAll('.side-nav a').forEach(link => {
            const active = link.dataset.nav === current;
            link.classList.toggle('active', active);
            if (active) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
        });
    }
    window.addEventListener('hashchange', updateNavigation);
    window.addEventListener('workspace-route-change', updateNavigation);
    window.addEventListener('remote-tab-change', updateNavigation);
    updateNavigation();
});
