(function (root) {
    const marker = /\uE200cite\uE202([^\uE200\uE201\r\n]*)\uE201/g;
    const prefix = '\uE200cite\uE202';
    function safeUrl(value) {
        if (typeof value !== 'string' || /[\u0000-\u0020<>\\]/.test(value)) return '';
        try {
            const url = new URL(value);
            return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : '';
        } catch { return ''; }
    }
    function references(body) {
        const values = body.split('\uE202');
        if (/^L\d+(?:-L\d+)?$/.test(values.at(-1))) values.pop();
        return values.length && values.every(value => /^[A-Za-z][A-Za-z0-9_-]{0,127}$/.test(value)) ? values : [];
    }
    function text(value, sources, state, escape) {
        let output = '', offset = 0;
        const rows = Array.isArray(sources) ? sources : [];
        const label = (key, source) => {
            if (!state.numbers.has(key)) state.numbers.set(key, state.numbers.size + 1);
            const number = state.numbers.get(key), url = safeUrl(source?.url);
            if (!url) { state.missing = true; return `<span class="agent-citation unavailable" title="引用链接未提供">[${number}]</span>`; }
            return `<a class="agent-citation" href="${escape(url)}" target="_blank" rel="noopener noreferrer" title="${escape(String(source.title || '打开引用来源'))}">[${number}]</a>`;
        };
        for (const match of value.matchAll(marker)) {
            output += escape(value.slice(offset, match.index));
            const ids = references(match[1]);
            if (!ids.length) {state.missing=true;output+='<span class="agent-citation unavailable">[引用]</span>';}
            else {
                const group = rows.filter(row => row?.marker === match[0] && safeUrl(row.url));
                if (group.length) {
                    const unique = new Map(group.map(row => [safeUrl(row.url), row]));
                    output += [...unique].map(([url, row]) => label('url:' + url, row)).join('');
                } else output += [...new Set(ids)].map(id => {
                    const source = rows.find(row => row?.reference_id === id && safeUrl(row.url));
                    return label(source ? 'url:' + safeUrl(source.url) : 'ref:' + id, source);
                }).join('');
            }
            offset = match.index + match[0].length;
        }
        const tail = value.slice(offset), last = tail.lastIndexOf('\uE200');
        if (last >= 0 && (prefix.startsWith(tail.slice(last)) || tail.slice(last).startsWith(prefix) && !tail.slice(last).includes('\uE201'))) {
            output += escape(tail.slice(0, last));
            if (!state.streaming) state.missing = true;
        } else output += escape(tail);
        return output;
    }
    const api = { text, safeUrl };
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.RemoteToolCitations = api;
})(typeof window !== 'undefined' ? window : globalThis);
