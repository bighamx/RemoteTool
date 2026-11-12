import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {ClaudeUsage, usageSnapshot} from '../../WebApplication/Tools/ClaudeBridge/usage.mjs';
import {createApp, createServer} from '../../WebApplication/Tools/ClaudeBridge/server.mjs';
const method = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET';
const snapshot = () => ({rate_limits_available: true, rate_limits: {five_hour: {utilization: 0, resets_at: null}, seven_day: {utilization: 64, resets_at: '2026-11-13T02:00:00.292+00:00'}}});
const auth = async () => ({ready: true});

test('structured utilization is a percentage: zero and one are not missing or 100 percent', () => {
    const value = snapshot(); value.rate_limits.five_hour.utilization = 1;
    let result = usageSnapshot(value, 100);
    assert.equal(result.rateLimitsByLimitId.claude.primary.usedPercent, 1);
    value.rate_limits.five_hour.utilization = 0;
    result = usageSnapshot(value);
    assert.equal(result.rateLimitsByLimitId.claude.primary.usedPercent, 0);
    assert.equal(result.rateLimitsByLimitId.claude.secondary.usedPercent, 64);
    assert.equal(result.rateLimitsByLimitId.claude.secondary.resetsAt, Date.parse('2026-11-13T02:00:00Z') / 1000);
});
test('a status without numeric usage is unavailable; API-key accounts never fabricate subscription limits', () => {
    const result = usageSnapshot({rate_limits_available: true, rate_limits: {five_hour: {status: 'allowed', utilization: null}}});
    assert.equal(result.available, false); assert.equal(result.rateLimitsByLimitId.claude.primary.usedPercent, null);
    const api = usageSnapshot({...snapshot(), rate_limits_available: false});
    assert.equal(api.available, false); assert.deepEqual(api.rateLimitsByLimitId, {});
    assert.match(api.message, /不提供订阅额度/);
});
test('usage control sends no user message, disables persistence/project tools, closes its query and coalesces callers', async () => {
    let calls = 0, closes = 0, input;
    const sdk = {query({prompt, options}) {
        calls++; input = prompt.next();
        assert.equal(options.persistSession, false); assert.deepEqual(options.settingSources, []);
        assert.equal(options.strictMcpConfig, true); assert.deepEqual(options.tools, []);
        return {[method]: async args => { assert.equal(args.skipBehaviors, true); return snapshot(); }, close() { closes++; }};
    }};
    const usage = new ClaudeUsage({sdk, executable: 'claude', cwd: os.tmpdir(), auth});
    const [first, second] = await Promise.all([usage.read(), usage.read()]);
    assert.equal(first, second); assert.equal(calls, 1); assert.equal(closes, 1);
    assert.deepEqual(await input, {value: undefined, done: true});
    assert.equal((await usage.read()).available, true); assert.equal(calls, 1);
});
test('failure retains last real snapshot with a stale marker and backs off without leaking SDK errors', async () => {
    let now = 1000, calls = 0;
    const sdk = {query() { calls++; return {[method]: async () => { if (calls > 1) throw new Error('Bearer SECRET'); return snapshot(); }, close() {}}; }};
    const usage = new ClaudeUsage({sdk, executable: 'claude', cwd: os.tmpdir(), auth, now: () => now});
    const first = await usage.read(); now += 61000;
    const stale = await usage.read();
    assert.equal(stale.available, true); assert.equal(stale.stale, true);
    assert.equal(stale.observed_at, first.observed_at);
    assert.equal(stale.rateLimitsByLimitId.claude.secondary.usedPercent, 64);
    assert.ok(!JSON.stringify(stale).includes('SECRET'));
    await usage.read(); assert.equal(calls, 2);
});
test('a query timeout closes the ephemeral child and reports unknown rather than empty/full quota', async () => {
    let closes = 0;
    const sdk = {query() { return {[method]: () => new Promise(() => {}), close() { closes++; }}; }};
    const usage = new ClaudeUsage({sdk, executable: 'claude', cwd: os.tmpdir(), auth, timeout: 10});
    const result = await usage.read();
    assert.equal(result.available, false); assert.deepEqual(result.rateLimitsByLimitId, {}); assert.equal(closes, 1);
});
test('HTTP usage endpoint obtains official quota before any model turn exists', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-usage-test-'));
    const sdk = {query() { return {[method]: async () => snapshot(), close() {}}; }};
    const app = createApp({root, executable: 'claude', sdk, exec: async () => ({stdout: JSON.stringify({loggedIn: true, apiProvider: 'firstParty'})})});
    const token = 't'.repeat(48), server = createServer(app, token);
    try {
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const response = await fetch(`http://127.0.0.1:${server.address().port}/usage`, {headers: {Authorization: 'Bearer ' + token}});
        assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
        const result = await response.json(); assert.equal(result.available, true);
        assert.equal(result.rateLimitsByLimitId.claude.secondary.usedPercent, 64);
        assert.deepEqual(app.store.data.runs, {}); assert.deepEqual(app.store.data.receipts, {});
    } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); fs.rmSync(root, {recursive: true, force: true}); }
});
