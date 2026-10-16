self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

// Only navigation gets an offline message; authenticated pages and APIs stay on the network.
self.addEventListener('fetch', event => {
    if (event.request.method !== 'GET' || event.request.mode !== 'navigate' ||
        new URL(event.request.url).origin !== self.location.origin) return;
    event.respondWith(fetch(event.request, { cache: 'no-store' }).catch(() => new Response(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#10151d"><title>RemoteTool 离线</title><body style="font:16px system-ui;background:#10151d;color:#edf4f3;padding:2rem"><h1>电脑暂时无法连接</h1><p>检查网络和 服务后重试。</p><a href="/" style="color:#61dbc4">重新连接</a></body></html>',
        { headers: { 'Content-Type': 'text/html; charset=utf-8' } }
    )));
});
