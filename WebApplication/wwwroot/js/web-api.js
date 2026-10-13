window.RemoteToolApi = {
    async request(path, options = {}) {
        const headers = new Headers(options.headers || {});
        const token = localStorage.getItem('token');
        if (token) headers.set('Authorization', `Bearer ${token}`);
        if (options.body != null && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
        const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options, headers });
        if (response.status === 401) { location.href = '/Account/Login?ReturnUrl=' + encodeURIComponent(location.pathname + location.search + location.hash); throw new Error('登录已过期'); }
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw Object.assign(new Error(payload.message || payload.error || `请求失败 (${response.status})`), { status: response.status, code: payload.code, delivery: payload.delivery });
        return payload;
    },
    get(path) { return this.request(path); },
    post(path, body = {}, headers = {}) { return this.request(path, { method: 'POST', body: JSON.stringify(body), headers }); },
    patch(path, body = {}) { return this.request(path, { method: 'PATCH', body: JSON.stringify(body) }); },
    delete(path) { return this.request(path, { method: 'DELETE' }); },
    id(value) { return encodeURIComponent(String(value)); },
    time(value) { if (!value) return '—'; const date = new Date(typeof value === 'number' && value < 1e12 ? value * 1000 : value); return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN'); }
};
