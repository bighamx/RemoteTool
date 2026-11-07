window.RemoteToolApi = {
    async request(path, options = {}) {
        const headers = new Headers(options.headers || {});
        const token = localStorage.getItem('token');
        if (token) headers.set('Authorization', `Bearer ${token}`);
        if (options.body != null && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
        const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options, headers });
        if (response.status === 401) { location.href = '/Account/Login?ReturnUrl=' + encodeURIComponent(location.pathname + location.search + location.hash); throw new Error('登录已过期'); }
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
            // 网关/服务端的错误体有三种形态：{message}、{error:{message,type}}、{error:"..."}；
            // 直接取 payload.error 会把对象塞进 Error.message，界面显示成 [object Object]。
            const detail = payload.message ?? payload.error?.message ?? payload.error ?? payload.title;
            const text = typeof detail === 'string' ? detail
                : detail && typeof detail === 'object' && typeof detail.message === 'string' ? detail.message : '';
            throw Object.assign(new Error(text || `请求失败 (${response.status})`), { status: response.status, code: payload.code ?? payload.error?.code, delivery: payload.delivery, payload });
        }
        return payload;
    },
    get(path) { return this.request(path); },
    post(path, body = {}, headers = {}) { return this.request(path, { method: 'POST', body: JSON.stringify(body), headers }); },
    patch(path, body = {}) { return this.request(path, { method: 'PATCH', body: JSON.stringify(body) }); },
    delete(path) { return this.request(path, { method: 'DELETE' }); },
    id(value) { return encodeURIComponent(String(value)); },
    time(value) { if (!value) return '—'; const date = new Date(typeof value === 'number' && value < 1e12 ? value * 1000 : value); return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN'); }
};
