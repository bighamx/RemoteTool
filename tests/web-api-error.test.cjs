// 回归：网关 404 的错误体是 {"error":{"message":...}}，早期实现把整个对象塞进 Error.message，
// 界面渲染成 [object Object]。这里锁死「错误消息必须是字符串」。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadApi(status, body) {
    const source = fs.readFileSync(path.join(__dirname, '..', 'WebApplication', 'wwwroot', 'js', 'web-api.js'), 'utf8');
    const context = {
        window: {}, Headers, FormData,
        localStorage: { getItem: () => 'token' },
        location: { href: '', pathname: '/', search: '' },
        fetch: async () => ({ status, ok: status >= 200 && status < 300, json: async () => body })
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    return context.window.RemoteToolApi;
}

test('nested gateway error object becomes a readable string', async () => {
    const api = loadApi(404, { error: { message: 'Session not found: api_123', type: 'invalid_request_error', code: 'session_not_found' } });
    await assert.rejects(() => api.get('/api/hermes/sessions/api_123'), (error) => {
        assert.strictEqual(error.message, 'Session not found: api_123');
        assert.strictEqual(error.status, 404);
        assert.strictEqual(error.code, 'session_not_found');
        return true;
    });
});

test('plain message body keeps its text and object payloads never stringify to [object Object]', async () => {
    const plain = loadApi(400, { message: '参数不合法' });
    await assert.rejects(() => plain.post('/api/x', {}), (error) => error.message === '参数不合法');

    const nested = loadApi(500, { error: { detail: { deep: true } } });
    await assert.rejects(() => nested.get('/api/x'), (error) => {
        assert.strictEqual(error.message, '请求失败 (500)');
        assert.ok(!error.message.includes('[object Object]'));
        return true;
    });
});
