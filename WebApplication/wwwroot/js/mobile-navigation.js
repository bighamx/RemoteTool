(() => {
    const key = 'remotetool:web:mobile-navigation:v1';
    const defaults = ['system', 'desktop', 'codex', 'hermes', 'files'];
    function normalize(value, items) {
        const available = new Set(items.map(item => item.id));
        const source = Array.isArray(value) ? value : defaults;
        return [...new Set([...source, ...defaults, ...items.map(item => item.id)])]
            .filter(id => typeof id === 'string' && available.has(id));
    }
    function move(order, from, to) {
        const result = [...order];
        if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || from >= result.length) return result;
        const [item] = result.splice(from, 1);
        result.splice(Math.max(0, Math.min(result.length, to)), 0, item);
        return result;
    }
    window.RemoteToolMobileNavigation = {normalize, move};
    document.addEventListener('DOMContentLoaded', () => {
        const nav = document.querySelector('.mobile-navigation');
        const more = nav?.querySelector('[data-mobile-nav=more]');
        const tools = document.getElementById('mobile-tools');
        const overflow = tools?.querySelector('.mobile-tools-list');
        const editor = document.getElementById('mobile-nav-editor');
        const list = document.getElementById('mobile-nav-editor-list');
        const status = document.getElementById('mobile-nav-editor-status');
        if (!nav || !more || !overflow || !editor || !list || !window.RemoteToolNavigation) return;
        const shortNames = {system:'系统', desktop:'远程', jobs:'任务', claude:'Claude'};
        const items = window.RemoteToolNavigation.map(item => ({
            ...item, id: item.href.match(/^\/#\/([^?]*)/)?.[1] || 'home'
        }));
        const byId = new Map(items.map(item => [item.id, item]));
        let order, draft = [], drag = null, scrolling = null;
        function read() {
            try { return normalize(JSON.parse(localStorage.getItem(key)), items); }
            catch { return normalize(null, items); }
        }
        function icon(item) {
            const image = document.createElement('img'); image.src = item.icon; image.alt = ''; return image;
        }
        function current() {
            const route = location.hash.match(/^#\/([^?]*)/)?.[1] || 'home';
            return route.startsWith('docker/') ? 'docker' : route;
        }
        function sync() {
            const route = current(), visible = order.slice(0, 5).includes(route) ? route : 'more';
            for (const node of nav.querySelectorAll('[data-mobile-nav]')) {
                if (node.dataset.mobileNav === visible) node.setAttribute('aria-current', 'page');
                else node.removeAttribute('aria-current');
            }
            for (const node of overflow.querySelectorAll('a[data-mobile-overflow]')) {
                if (node.dataset.mobileOverflow === route) node.setAttribute('aria-current', 'page');
                else node.removeAttribute('aria-current');
            }
        }
        function render() {
            nav.querySelectorAll('a').forEach(node => node.remove());
            overflow.querySelectorAll('a').forEach(node => node.remove());
            order.forEach((id, index) => {
                const item = byId.get(id), link = document.createElement('a'); link.href = item.href;
                link.append(icon(item));
                const label = document.createElement('span');
                if (index < 5) {
                    link.dataset.mobileNav = id; link.title = item.label;
                    label.textContent = shortNames[id] || item.label; link.append(label); nav.insertBefore(link, more);
                } else {
                    link.dataset.mobileOverflow = id;
                    const name = document.createElement('strong'); name.textContent = item.label; label.append(name); link.append(label);
                    const caret = document.createElement('span'); caret.textContent = '›'; caret.setAttribute('aria-hidden', 'true'); link.append(caret);
                    overflow.insertBefore(link, overflow.querySelector('button'));
                }
            });
            sync();
        }
        function button(label, action, title) {
            const node = document.createElement('button'); node.type = 'button'; node.textContent = label;
            node.setAttribute('aria-label', title); node.addEventListener('click', action); return node;
        }
        function arrange(from, to, focusId, focusAction) {
            draft = move(draft, from, to); renderEditor();
            status.textContent = `已将${byId.get(focusId).label}移至第 ${draft.indexOf(focusId) + 1} 位`;
            const row = [...list.children].find(node => node.dataset.navId === focusId);
            const target = row?.querySelector(`[data-move="${focusAction}"]:not(:disabled)`) || row?.querySelector('.mobile-nav-drag');
            target?.focus({preventScroll:true});
        }
        function renderEditor() {
            list.replaceChildren();
            draft.forEach((id, index) => {
                const item = byId.get(id), row = document.createElement('li'); row.className = 'mobile-nav-editor-row'; row.dataset.navId = id;
                const handle = button('⠿', () => {}, `拖动${item.label}调整顺序`); handle.className = 'mobile-nav-drag';
                handle.addEventListener('pointerdown', event => startDrag(event, id, handle)); row.append(handle, icon(item));
                const label = document.createElement('span'); label.className = 'mobile-nav-editor-label';
                const name = document.createElement('strong'); name.textContent = item.label;
                const hint = document.createElement('small'); hint.textContent = index < 5 ? `底栏 · 第 ${index + 1} 位` : '收纳在更多中';
                label.append(name, hint); row.append(label);
                if (index >= 5) {
                    const promote = button('加入底栏', () => arrange(index, 4, id, 'up'), `将${item.label}加入底栏`);
                    promote.className = 'mobile-nav-promote'; row.append(promote);
                }
                for (const [direction, arrow, offset] of [['up','↑',-1],['down','↓',1]]) {
                    const control = button(arrow, () => arrange(index, index + offset, id, direction), `${direction==='up'?'上移':'下移'}${item.label}`);
                    control.dataset.move = direction; control.disabled = index + offset < 0 || index + offset >= draft.length; row.append(control);
                }
                list.append(row);
            });
        }
        function markDrop() {
            if (!drag) return;
            const rows = [...list.children];
            let slot = rows.findIndex(row => drag.y < row.getBoundingClientRect().top + row.offsetHeight / 2);
            if (slot < 0) slot = rows.length;
            drag.slot = slot;
            rows.forEach(row => row.classList.remove('drop-before', 'drop-after'));
            if (slot < rows.length) rows[slot].classList.add('drop-before');
            else rows.at(-1)?.classList.add('drop-after');
        }
        function scrollDrag() {
            if (!drag) return;
            const bounds = list.getBoundingClientRect();
            if (drag.y < bounds.top + 40) list.scrollTop -= 10;
            else if (drag.y > bounds.bottom - 40) list.scrollTop += 10;
            markDrop(); scrolling = requestAnimationFrame(scrollDrag);
        }
        function finishDrag(cancelled) {
            if (!drag) return;
            const {id, pointer, handle, slot} = drag, from = draft.indexOf(id); drag = null;
            cancelAnimationFrame(scrolling); scrolling = null;
            try {handle.releasePointerCapture(pointer);} catch {}
            if (!cancelled && Number.isInteger(slot)) arrange(from, slot > from ? slot - 1 : slot, id, 'up');
            else renderEditor();
        }
        function startDrag(event, id, handle) {
            if (event.button !== 0 || drag) return;
            event.preventDefault();
            drag = {id, handle, pointer:event.pointerId, y:event.clientY, slot:draft.indexOf(id)};
            handle.setPointerCapture(event.pointerId); handle.closest('li').classList.add('dragging'); scrollDrag();
        }
        list.addEventListener('pointermove', event => {if (drag && event.pointerId === drag.pointer) {drag.y = event.clientY; markDrop();}});
        list.addEventListener('pointerup', event => {if (drag && event.pointerId === drag.pointer) finishDrag(false);});
        list.addEventListener('pointercancel', () => finishDrag(true));
        editor.addEventListener('close', () => {finishDrag(true); more.focus({preventScroll:true});});
        tools.querySelector('[data-mobile-action=customize]')?.addEventListener('click', () => {
            draft = [...order]; status.textContent = ''; renderEditor(); tools.close(); editor.showModal();
        });
        editor.querySelectorAll('[data-nav-cancel]').forEach(node => node.addEventListener('click', () => editor.close()));
        editor.querySelector('[data-nav-reset]').addEventListener('click', () => {
            draft = normalize(null, items); renderEditor(); status.textContent = '已恢复默认顺序，保存后生效';
        });
        editor.querySelector('[data-nav-save]').addEventListener('click', () => {
            try {localStorage.setItem(key, JSON.stringify(draft));}
            catch {status.textContent = '无法保存到当前浏览器，请检查隐私或存储设置'; return;}
            order = [...draft]; render(); editor.close();
        });
        window.addEventListener('storage', event => {if (event.key === key || event.key === null) {order = read(); render();}});
        for (const name of ['hashchange','workspace-route-change','remote-tab-change']) window.addEventListener(name, sync);
        const media = window.matchMedia('(max-width:760px), (max-width:1000px) and (max-height:500px) and (pointer:coarse)');
        media.addEventListener('change', () => {if (!media.matches && editor.open) editor.close();});
        order = read(); render();
    });
})();
