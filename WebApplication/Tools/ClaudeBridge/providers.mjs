import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {BridgeError, validId} from './state.mjs';

const local = {id: 'anthropic', slug: 'anthropic', name: '本机 Claude 配置', official_provider: true, enabled: true};
const text = (value, limit) => typeof value === 'string' && value.length <= limit && !/[\x00-\x1f\x7f]/.test(value);

/** Named endpoints are passed to the SDK per session. Secrets never enter response DTOs. */
export class ClaudeProviders {
    constructor(root) { this.file = path.join(root, 'providers.json'); }
    read() { return fs.existsSync(this.file) ? JSON.parse(fs.readFileSync(this.file, 'utf8')) : []; }
    write(rows) {
        const temporary = this.file + '.' + randomUUID() + '.tmp';
        try {
            if (fs.existsSync(this.file)) fs.copyFileSync(this.file, this.file + '.bak');
            fs.writeFileSync(temporary, JSON.stringify(rows), {mode: 0o600});
            fs.renameSync(temporary, this.file);
        } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
    }
    endpoint(row) {
        const {api_key, ...visible} = row;
        return {...visible, slug: row.id, official_provider: false, has_api_key: !!api_key};
    }
    list(includeDisabled = false) {
        return {endpoints: [local, ...this.read().filter(row => includeDisabled || row.enabled !== false).map(row => this.endpoint(row))]};
    }
    get(id = 'anthropic') {
        if (id === 'anthropic') return null;
        const row = this.read().find(row => row.id === id);
        if (!row || row.enabled === false) throw new BridgeError('所选 Claude 供应商已删除或禁用，请重新选择', 409, {delivery: 'rejected'});
        return row;
    }
    save(body) {
        const id = body.id || randomUUID();
        if (!validId(id) || id === 'anthropic') throw new BridgeError('无效的供应商标识');
        if (!text(body.name, 80) || !body.name.trim() || !text(body.model, 200) || !body.model.trim()) throw new BridgeError('请填写供应商名称和模型 ID');
        let url;
        try { url = new URL(body.base_url); } catch { throw new BridgeError('API 地址无效'); }
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new BridgeError('请填写 HTTP(S) API 基础地址');
        if (body.api_mode && body.api_mode !== 'anthropic_messages') throw new BridgeError('Claude 供应商需兼容 Anthropic Messages API');
        if (body.api_key !== undefined && !text(body.api_key, 8192)) throw new BridgeError('API Key 无效');
        const rows = this.read(), previous = rows.find(row => row.id === id);
        const row = {id, name: body.name.trim(), base_url: url.href.replace(/\/+$/, ''), model: body.model.trim(),
            api_mode: 'anthropic_messages', enabled: previous?.enabled !== false, api_key: body.api_key?.trim() || previous?.api_key || ''};
        this.write([...rows.filter(row => row.id !== id), row]);
        return {endpoint: this.endpoint(row)};
    }
    change(body, remove = false) {
        if (!validId(body.id) || body.id === 'anthropic') throw new BridgeError('本机 Claude 配置由 Claude Code 管理');
        const rows = this.read(), row = rows.find(row => row.id === body.id);
        if (!row) throw new BridgeError('供应商不存在', 404);
        if (!remove && typeof body.enabled !== 'boolean') throw new BridgeError('供应商状态无效');
        if (remove) this.write(rows.filter(row => row.id !== body.id));
        else { row.enabled = body.enabled; this.write(rows); }
        return {success: true};
    }
    environment(row) {
        return {ANTHROPIC_BASE_URL: row.base_url, ANTHROPIC_API_KEY: row.api_key, ANTHROPIC_AUTH_TOKEN: row.api_key,
            ANTHROPIC_CUSTOM_HEADERS: '', CLAUDE_CODE_OAUTH_TOKEN: '', CLAUDE_CODE_USE_BEDROCK: '0', CLAUDE_CODE_USE_VERTEX: '0', CLAUDE_CODE_USE_FOUNDRY: '0'};
    }
}
