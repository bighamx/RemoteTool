(() => {
    const labels = {Read:'读取文件',Edit:'修改文件',Write:'写入文件',Bash:'执行命令',Glob:'查找文件',Grep:'搜索内容',Agent:'子任务',Task:'子任务',TodoWrite:'更新待办',ToolSearch:'查找工具',WebSearch:'搜索网页',WebFetch:'读取网页',AskUserQuestion:'询问用户',Skill:'使用技能'};
    const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
    const blocks = row => Array.isArray(row.blocks) ? row.blocks : typeof row.blocks === 'string' ? [{type:'text',text:row.blocks}] : [];
    let markdown;
    function renderer() {
        if (markdown || !window.markdownit) return markdown;
        markdown = window.markdownit({html:false,breaks:true,linkify:false}).disable('image');
        const link = markdown.renderer.rules.link_open || ((tokens, index, options, env, self) => self.renderToken(tokens,index,options));
        markdown.renderer.rules.link_open = (tokens,index,options,env,self) => {
            tokens[index].attrSet('target','_blank'); tokens[index].attrSet('rel','noopener noreferrer');
            return link(tokens,index,options,env,self);
        };
        for (const type of ['fence','code_block']) markdown.renderer.rules[type] = (tokens,index) => {
            const token=tokens[index], language=(token.info||'').trim().split(/\s/)[0].slice(0,40);
            const code=`<pre><code${language?` class="language-${escape(language)}"`:''}>${escape(token.content)}</code></pre>`;
            const lines=token.content.trimEnd().split('\n').length;
            return lines>16 || token.content.length>1600
                ? `<details class="claude-code-fold"><summary>代码${language?' · '+escape(language):''} · ${lines} 行</summary>${code}</details>` : code;
        };
        return markdown;
    }
    function html(value) {
        const md=renderer();
        return md ? md.render(String(value ?? '')) : `<p>${escape(value).replace(/\n/g,'<br>')}</p>`;
    }
    function resultText(value) {
        if (typeof value==='string') return value;
        if (!Array.isArray(value)) return '';
        return value.map(block => block.type==='text' ? block.text||'' : block.type==='image' ? '[图片结果]' : JSON.stringify(block,null,2)).join('\n');
    }
    function toolSummary(tool) {
        const input=tool.input||{};
        const value=input.description||input.file_path||input.path||input.command||input.pattern||input.query||input.url||input.skill||'';
        return typeof value==='string' ? value.replace(/\s+/g,' ').trim().slice(0,160) : '';
    }
    function project(history, live=[]) {
        const tools=new Map(), claimed=new Set(), seenRows=new Set();
        const source=[...history,...live];
        for (const row of source) for (const block of blocks(row)) if (block.type==='tool_use' && block.id && !tools.has(block.id)) {
            tools.set(block.id,{key:'tool:'+block.id,kind:'tool',name:labels[block.name]||block.name||'工具调用',input:block.input||{},results:[],complete:false,error:false});
        }
        for (const row of source) for (const block of blocks(row)) if (block.type==='tool_result') {
            const id=block.tool_use_id, tool=tools.get(id);
            if (tool) {tool.results.push(block);tool.complete=true;tool.error ||= block.is_error===true;}
        }
        function rows(input) {
            const output=[];
            for (const row of input) {
                if (row.id && seenRows.has(row.id)) continue;
                if (row.id) seenRows.add(row.id);
                const parts=[];
                const content=[...blocks(row)];
                if (row.content && !content.some(block=>block.type==='text')) content.unshift({type:'text',text:row.content});
                content.forEach((block,index) => {
                    const key=String(row.id)+':'+index;
                    if (block.type==='text' && typeof block.text==='string' && block.text.trim()) {
                        parts.push({kind:'text',key,text:block.text,long:!(/```|~~~/.test(block.text))&&(block.text.length>6000||block.text.split('\n').length>100)});
                    } else if (block.type==='tool_use' && tools.has(block.id) && !claimed.has(block.id)) {
                        claimed.add(block.id); parts.push(tools.get(block.id));
                    } else if (block.type==='tool_result' && !tools.has(block.tool_use_id)) {
                        parts.push({kind:'tool',key,name:'工具结果',input:null,results:[block],complete:true,error:block.is_error===true});
                    } else if (block.type==='image') parts.push({kind:'notice',key,text:'图片附件（暂不支持预览）'});
                });
                if (!parts.length) continue;
                const toolOnly=parts.every(part=>part.kind==='tool');
                const previous=output.at(-1);
                if (toolOnly && previous?.toolOnly) previous.parts.push(...parts);
                else output.push({id:row.id,role:toolOnly?'tool':row.role,toolOnly,parts});
            }
            return output;
        }
        return {history:rows(history),live:rows(live)};
    }
    window.RemoteToolClaudeContent={project,html,resultText,toolSummary};
})();
