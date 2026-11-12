// Converts native Claude Code transcripts (SDK getSessionMessages) into the
// row contract shared by the Codex/Hermes workspace: user/assistant rows,
// interaction rows for AskUserQuestion, and one tool summary row per finished
// turn. Pure functions only; no I/O.

export const ATTACHMENT_MARKER = '[RemoteTool 附件]';
const COMPACT_PREFIX = 'This session is being continued from a previous conversation';
const INTERRUPTED = /^\[Request interrupted by user[^\]]*\]$/;

export const TOOL_LABELS = {
    Read: '读取文件', Edit: '修改文件', MultiEdit: '修改文件', Write: '写入文件', NotebookEdit: '修改笔记本',
    Bash: '执行命令', BashOutput: '读取命令输出', KillShell: '结束命令', Glob: '查找文件', Grep: '搜索内容',
    Agent: '子任务', Task: '子任务', TodoWrite: '更新待办', ToolSearch: '查找工具', WebSearch: '搜索网页',
    WebFetch: '读取网页', AskUserQuestion: '询问用户', Skill: '使用技能', ExitPlanMode: '提交计划',
};
const FILE_TOOLS = new Set(['Edit', 'MultiEdit', 'Write', 'NotebookEdit']);

export const toolLabel = name => TOOL_LABELS[name] || String(name || '工具');
export const isFileChange = name => FILE_TOOLS.has(name);

const clip = (value, size) => value.length > size ? value.slice(0, size) + '…' : value;
const stamp = value => { const parsed = Date.parse(value || ''); return Number.isFinite(parsed) ? parsed : null; };
const blocksOf = message => {
    const content = message?.message?.content;
    if (typeof content === 'string') return [{type: 'text', text: content}];
    return Array.isArray(content) ? content : [];
};

/** One-line description of a tool call's input, used for live progress and summaries. */
export function toolPreview(name, input) {
    const value = input && typeof input === 'object' ? input : {};
    const text = value.description || value.command || value.file_path || value.notebook_path || value.path ||
        value.pattern || value.query || value.url || value.skill || value.prompt || '';
    if (typeof text === 'string' && text.trim()) return clip(text.replace(/\s+/g, ' ').trim(), 160);
    const keys = Object.keys(value).slice(0, 4);
    return keys.length ? '参数：' + keys.join('、') : '';
}

export function resultText(content) {
    if (typeof content === 'string') return content;
    if (!Array.isArray(content)) return '';
    return content.map(block => block?.type === 'text' ? block.text || '' : block?.type === 'image' ? '[图片结果]' : '').filter(Boolean).join('\n');
}

const stripAnsi = text => text.replace(/\u001b\[[0-9;]*[A-Za-z]/g, '');

/** Splits RemoteTool's attachment note (added by runs.mjs) away from user text. */
export function userPrompt(message) {
    const parts = [], paths = [];
    let images = 0;
    for (const block of blocksOf(message)) {
        if (block?.type === 'text' && typeof block.text === 'string') {
            if (block.text.startsWith(ATTACHMENT_MARKER)) {
                for (const line of block.text.split('\n')) if (line.startsWith('附件：')) paths.push(line.slice(3).trim());
            } else parts.push(block.text);
        } else if (block?.type === 'image') images++;
    }
    return {text: parts.join('\n').trim(), paths, images};
}

function commandText(text) {
    const name = text.match(/<command-name>([\s\S]*?)<\/command-name>/)?.[1]?.trim();
    if (!name) return null;
    const args = text.match(/<command-args>([\s\S]*?)<\/command-args>/)?.[1]?.trim();
    return (name.startsWith('/') ? name : '/' + name) + (args ? ' ' + args : '');
}

function askAnswers(text) {
    const answers = {};
    for (const match of String(text || '').matchAll(/"([^"]+)"="([^"]*)"/g)) answers[match[1]] = match[2];
    return answers;
}

const isPrompt = message => message.type === 'user' && !message.parent_tool_use_id &&
    blocksOf(message).some(block => block?.type === 'text' || block?.type === 'image') &&
    !blocksOf(message).some(block => block?.type === 'tool_result');

/**
 * @param {Array} messages native SessionMessage list (any order the SDK returns: chain order)
 * @param {{activeSince?: number|null}} options activeSince: start time (ms) of a live run; its turn is not summarised
 * @returns {{rows: Array, tools: Map<string, Array>, active: Array, usage: object, context: object|null}}
 */
export function project(messages, options = {}) {
    const rows = [], tools = new Map(), calls = new Map();
    const usage = {input_tokens: 0, output_tokens: 0, cache_read_tokens: 0, cache_write_tokens: 0, message_count: 0, tool_call_count: 0, model: null};
    const seenUsage = new Set();
    let context = null, turn = {id: 'head', tools: [], rows: 0, started: 0}, lastAssistant = null, previousEntry = null;
    const turns = [turn];
    const closeTurn = () => {
        if (!turn.tools.length) return;
        tools.set('tools-' + turn.id, turn.tools);
    };
    const summary = finished => {
        if (!finished.tools.length) return;
        const changed = finished.tools.filter(tool => tool.category === 'file_change').length;
        rows.push({id: 'tools-' + finished.id, role: 'system', type: 'toolSummary', tool_summary_id: 'tools-' + finished.id,
            content: `工具 ${finished.tools.length - changed} 次 · 文件修改 ${changed} 次`, tool_count: finished.tools.length,
            tools: finished.tools.length - changed, file_changes: changed, timestamp: finished.last ?? null, editable: false});
    };
    for (const message of messages || []) {
        if (!message || message.parent_tool_use_id || message.parent_agent_id) continue;
        const time = stamp(message.timestamp);
        if (message.type === 'user' && isPrompt(message)) {
            const prompt = userPrompt(message);
            const raw = prompt.text;
            if (raw.startsWith(COMPACT_PREFIX)) {
                rows.push({id: message.uuid, role: 'system', type: 'contextCompaction', content: '上下文已压缩', timestamp: time, editable: false});
                previousEntry = message.uuid; continue;
            }
            if (raw.includes('<local-command-caveat>')) { previousEntry = message.uuid; continue; }
            if (raw.includes('<local-command-stdout>') || raw.includes('<local-command-stderr>')) {
                const output = stripAnsi(raw.replace(/<\/?local-command-(stdout|stderr)>/g, '')).trim();
                if (output) rows.push({id: message.uuid, role: 'system', type: 'commandOutput', content: clip(output, 4000), timestamp: time, editable: false});
                previousEntry = message.uuid; continue;
            }
            if (INTERRUPTED.test(raw)) {
                rows.push({id: message.uuid, role: 'system', type: 'interrupted', content: '已停止', timestamp: time, editable: false});
                previousEntry = message.uuid; continue;
            }
            closeTurn(); summary(turn);
            turn = {id: message.uuid, tools: [], started: time ?? 0, last: time};
            turns.push(turn);
            const text = commandText(raw) || raw || (prompt.images ? `[图片 ${prompt.images} 张]` : '');
            rows.push({id: message.uuid, role: 'user', content: text, timestamp: time, attachment_paths: prompt.paths,
                // Editing truncates the native chain at the entry before this prompt.
                editable: !!previousEntry, rewind_at: previousEntry});
            usage.message_count++;
            previousEntry = message.uuid; continue;
        }
        previousEntry = message.uuid;
        if (time != null) turn.last = time;
        if (message.type === 'assistant') {
            const api = message.message || {};
            if (api.usage && api.id && !seenUsage.has(api.id)) {
                seenUsage.add(api.id);
                usage.input_tokens += api.usage.input_tokens || 0; usage.output_tokens += api.usage.output_tokens || 0;
                usage.cache_read_tokens += api.usage.cache_read_input_tokens || 0; usage.cache_write_tokens += api.usage.cache_creation_input_tokens || 0;
                context = {tokens: (api.usage.input_tokens || 0) + (api.usage.cache_read_input_tokens || 0) + (api.usage.cache_creation_input_tokens || 0) + (api.usage.output_tokens || 0), model: api.model || null};
            }
            if (api.model && api.model !== '<synthetic>') usage.model = api.model;
            for (const block of blocksOf(message)) {
                if (block?.type === 'text' && block.text?.trim()) {
                    if (lastAssistant && lastAssistant.apiId && lastAssistant.apiId === api.id && rows.at(-1) === lastAssistant.row) {
                        lastAssistant.row.content += '\n\n' + block.text; lastAssistant.row.last_uuid = message.uuid;
                    } else {
                        const row = {id: message.uuid, role: 'assistant', content: block.text, timestamp: time, model: api.model || null};
                        rows.push(row); lastAssistant = {apiId: api.id, row};
                        usage.message_count++;
                    }
                } else if (block?.type === 'tool_use' && block.id) {
                    if (block.name === 'AskUserQuestion') {
                        const questions = Array.isArray(block.input?.questions) ? block.input.questions : [];
                        const row = {id: block.id, role: 'assistant', type: 'interaction', content: questions.map(q => q.question).join('\n'), timestamp: time, editable: false,
                            interactions: questions.map(q => ({question: q.question || '', choices: (q.options || []).map(option => option.label), answer: null, status: 'pending'}))};
                        rows.push(row); calls.set(block.id, {interaction: row});
                        continue;
                    }
                    const tool = {id: block.id, tool: toolLabel(block.name), name: block.name, category: isFileChange(block.name) ? 'file_change' : 'tool',
                        preview: toolPreview(block.name, block.input), input: block.input, status: 'running', output: ''};
                    turn.tools.push(tool); calls.set(block.id, {tool});
                    usage.tool_call_count++;
                }
            }
        } else if (message.type === 'user') {
            for (const block of blocksOf(message)) {
                if (block?.type !== 'tool_result') continue;
                const call = calls.get(block.tool_use_id);
                if (call?.interaction) {
                    const answers = askAnswers(resultText(block.content));
                    for (const entry of call.interaction.interactions) {
                        entry.answer = answers[entry.question] ?? null;
                        entry.status = block.is_error ? 'declined' : entry.answer ? 'answered' : 'unknown';
                    }
                } else if (call?.tool) {
                    call.tool.status = block.is_error ? 'failed' : 'completed';
                    call.tool.output = resultText(block.content);
                }
            }
        }
    }
    // The live turn keeps its tools out of history; clients render them from events/tools/active.
    const activeSince = options.activeSince ?? null;
    const live = activeSince != null && turn.id !== 'head' && (turn.started ?? 0) >= activeSince - 5000;
    closeTurn();
    if (!live) summary(turn);
    for (const finished of turns) for (const tool of finished.tools) if (tool.status === 'running' && !(live && finished === turn)) tool.status = 'unknown';
    return {rows, tools, active: live ? turn.tools : [], usage, context, lastEntry: previousEntry};
}

export function toolDetail(tool) {
    const head = tool.preview || '';
    const output = tool.output ? clip(String(tool.output).trim(), 2000) : '';
    return {id: tool.id, tool: tool.tool, category: tool.category, status: tool.status,
        description: [head, output].filter(Boolean).join('\n\n') || '暂无参数详情'};
}

export function toolPage(list, offset = 0, running = false) {
    const items = list || [], start = Math.max(0, Math.min(Number(offset) || 0, items.length)), size = 50;
    const changed = items.filter(tool => tool.category === 'file_change').length;
    return {data: items.slice(start, start + size).map(toolDetail), total: items.length, tools: items.length - changed,
        file_changes: changed, running, has_more: start + size < items.length};
}

const visible = row => row.role === 'user' || row.role === 'assistant' || row.type === 'contextCompaction';

/** Same windowing semantics as AgentHistoryWindow.Select on the C# side. */
export function windowRows(rows, limit = 80, olderBefore = null) {
    const size = Math.max(1, Math.min(Number(limit) || 80, 500));
    const anchor = olderBefore ? rows.findIndex(row => String(row.id) === String(olderBefore)) : -1;
    const end = anchor < 0 ? rows.length : anchor;
    let start = end, remaining = size;
    while (start > 0 && remaining > 0) { start--; if (visible(rows[start])) remaining--; }
    const data = rows.slice(start, end);
    return {data, has_more: rows.slice(0, start).some(visible), oldest_id: data[0]?.id ?? null};
}

/** Context window size: native result metadata when known, otherwise the model family default. */
export function contextLimit(model, known, used = 0) {
    if (Number(known) > 0 && Number(known) >= used) return {limit: Number(known), estimated: false};
    if (used > 200000) return {limit: 1000000, estimated: true};
    if (typeof model === 'string' && /\[1m\]|-1m\b/i.test(model)) return {limit: 1000000, estimated: true};
    return {limit: 200000, estimated: true};
}
