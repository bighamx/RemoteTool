const launchCache = 'remotetool-public-launch-v1';
const launchPath = '/app-start.html';
async function freshLaunch() {
    const response = await fetch(launchPath + self.location.search, {cache:'no-store', credentials:'same-origin', redirect:'error'});
    if (!response.ok || !(await response.clone().text()).includes('name="remotetool-launch"')) throw new Error('Launch page unavailable');
    await (await caches.open(launchCache)).put(launchPath, response.clone());
    return response;
}
self.addEventListener('install', event => event.waitUntil(freshLaunch().then(() => self.skipWaiting())));
self.addEventListener('activate', event => event.waitUntil(Promise.all([
    self.clients.claim(),
    caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('remotetool-public-launch-') && key !== launchCache).map(key => caches.delete(key))))
])));
self.addEventListener('fetch', event => {
    const request = event.request, url = new URL(request.url);
    if (request.method !== 'GET' || request.mode !== 'navigate' || url.origin !== self.location.origin) return;
    // Also handle older desktop shortcuts whose saved start URL is still '/'.
    if (url.pathname === launchPath || (url.pathname === '/' && !url.searchParams.has('rt-start'))) {
        event.respondWith((async () => {
            const cached = await (await caches.open(launchCache)).match(launchPath);
            if (cached) {event.waitUntil(freshLaunch().catch(() => {})); return cached;}
            return freshLaunch();
        })());
        return;
    }
    // Authenticated HTML and all APIs stay on the network; never store conversations or login state.
    event.respondWith(fetch(request, {cache:'no-store'}).catch(() => new Response(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RemoteTool 离线</title><body style="font:16px system-ui;background:#10151d;color:#edf4f3;padding:2rem"><h1>电脑暂时无法连接</h1><p>检查网络和服务后重试。</p><a href="/app-start.html" style="color:#8bd5ca">重新连接</a></body></html>',
        {headers:{'Content-Type':'text/html; charset=utf-8'}}
    )));
});
