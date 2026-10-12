export const isActive = status => ['started', 'submitting'].includes(status);
export const isFinal = status => ['completed', 'failed', 'interrupted'].includes(status);
export function parseEvent(block) {
    let id = '', event = 'message'; const data = [];
    for (const line of block.split('\n')) {
        if (line.startsWith(':')) continue;
        const colon = line.indexOf(':');
        const key = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
        if (key === 'data') data.push(value);
        if (key === 'id') id = value;
        if (key === 'event') event = value;
    }
    return data.length ? {id, event, data: JSON.parse(data.join('\n'))} : null;
}
export async function* readEvents(stream) {
    const reader = stream.getReader(), decoder = new TextDecoder(); let pending = '';
    try {
        while (true) {
            const {value, done} = await reader.read();
            if (done) break;
            pending += decoder.decode(value, {stream:true});
            // Normalize CRLF only after buffering so a split CR/LF cannot create a phantom event.
            pending = pending.replace(/\r\n/g, '\n');
            let end;
            while ((end = pending.indexOf('\n\n')) >= 0) {
                const block = pending.slice(0, end); pending = pending.slice(end + 2);
                const parsed = parseEvent(block); if (parsed) yield parsed;
            }
            if (pending.length > 2_000_000) throw new Error('事件过大');
        }
    } finally { reader.releaseLock(); }
}
export function coordinates(event, rect) {
    return {x:Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width)), y:Math.max(0,Math.min(1,(event.clientY-rect.top)/rect.height))};
}
export const windowsKey = code => {
    if (/^Key[A-Z]$/.test(code)) return code.charCodeAt(3);
    if (/^Digit[0-9]$/.test(code)) return code.charCodeAt(5);
    return {Enter:13,Escape:27,Backspace:8,Tab:9,Space:32,MetaLeft:91,MetaRight:92,ControlLeft:17,ControlRight:17,AltLeft:18,AltRight:18,ShiftLeft:16,ShiftRight:16,ArrowLeft:37,ArrowUp:38,ArrowRight:39,ArrowDown:40,Delete:46,Home:36,End:35,PageUp:33,PageDown:34}[code];
};
