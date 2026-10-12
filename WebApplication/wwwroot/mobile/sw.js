self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
// Keep authenticated pages and API responses on the network; never cache private device data.
self.addEventListener('fetch',event=>{
    if(event.request.method!=='GET'||new URL(event.request.url).origin!==self.location.origin)return;
    if(event.request.mode==='navigate'){
        event.respondWith(fetch(event.request,{cache:'no-store'}).catch(()=>new Response('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RemoteTool 离线</title><body style="font:16px system-ui;background:#10151d;color:#fff;padding:2rem"><h1>电脑暂时无法连接</h1><p>检查手机网络和 Mac 服务后重试。</p><a href="/mobile/index.html" style="color:#64d6c7">重新连接</a></body></html>',{headers:{'Content-Type':'text/html; charset=utf-8'}})));
        return;
    }
    event.respondWith(fetch(event.request));
});
