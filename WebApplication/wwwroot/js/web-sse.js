window.RemoteToolSse = async function ({ url, lastId = 0, signal, onEvent, onConnected }) {
    const headers = new Headers({ Accept: 'text/event-stream' });
    const token = localStorage.getItem('token');
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (lastId > 0) headers.set('Last-Event-ID', String(lastId));
    const response = await fetch(url, { headers, credentials: 'same-origin', cache: 'no-store', signal });
    if (!response.ok) throw Object.assign(new Error(`事件流不可用 (${response.status})`), { status: response.status });
    if (!response.body) throw new Error('浏览器不支持事件流');
    onConnected?.();
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '', data = [], id = '';
    const line = value => {
        if (!value) {
            if (data.length) {
                const raw = data.join('\n');
                try { onEvent(JSON.parse(raw), id); } catch (error) { if (!(error instanceof SyntaxError)) throw error; }
            }
            data = []; id = ''; return;
        }
        if (value.startsWith('data:')) data.push(value.slice(5).trimStart());
        else if (value.startsWith('id:')) id = value.slice(3).trim();
    };
    try {
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let end;
            while ((end = buffer.indexOf('\n')) >= 0) { const part = buffer.slice(0,end).replace(/\r$/, ''); buffer = buffer.slice(end+1); line(part); }
            if (buffer.length > 1024 * 1024) throw new Error('事件帧过大');
        }
    } finally { reader.releaseLock(); }
};
