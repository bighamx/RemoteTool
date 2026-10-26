(() => {
    const api = window.RemoteToolApi;
    const root = document.getElementById(document.currentScript?.dataset.agentRootId)?.querySelector('[data-agent]') || document.getElementById('agent-app');
    const agent = root?.dataset.agent;
    if (!['codex','hermes'].includes(agent)) return;
    const profilePreference = 'remotetool:web:hermes:profile';
    const requestedProfile = new URLSearchParams(location.search).get('profile') || localStorage.getItem(profilePreference) || 'default';
    const profile = agent === 'hermes' && /^[a-z0-9][a-z0-9_-]{0,63}$/.test(requestedProfile) ? requestedProfile : 'default';
    const storageAgent = agent === 'hermes' && profile !== 'default' ? `${agent}:profile:${profile}` : agent;
    const base = agent === 'hermes' && profile !== 'default' ? `/api/hermes/profiles/${encodeURIComponent(profile)}` : `/api/${agent}`;
    const receiptKey = id => `remotetool:web:pending:${storageAgent}:${id}`;
    const echoKey = id => `remotetool:web:delivered:${storageAgent}:${id}`;
    const draftKey = id => `remotetool:web:draft:${storageAgent}:${id}`;
    const draftFilesKey = id => `remotetool:web:draft-files:${storageAgent}:${id}`;
    const selectedSessionKey = `remotetool:web:selected:${storageAgent}`;
    // Web Crypto's randomUUID only exists in a secure context (https or localhost).
    // Over the LAN plain http address it is undefined, which broke send/compact/fork/queue
    // with "crypto.randomUUID is not a function"; fall back to getRandomValues, then to
    // Math.random. Keys only need to be unique per device, not cryptographically strong.
    const newKey = () => {
        try { if (window.crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID(); } catch (e) {}
        try {
            if (window.crypto && typeof crypto.getRandomValues === 'function') {
                const bytes = crypto.getRandomValues(new Uint8Array(16));
                bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
                const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
                return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
            }
        } catch (e) {}
        return `k${Date.now().toString(16)}${Math.random().toString(16).slice(2, 10)}${Math.random().toString(16).slice(2, 6)}`;
    };
    const terminal = new Set(['completed','failed','cancelled','interrupted','acceptance_unknown']);
    const previewSupport = new Map();
    // 两个 agent 都启用 markdown 渲染（hermes assistant 消息同为 markdown 输出；
    // html:false 转义裸 HTML，安全面不变）。
    const markdown = window.markdownit
        ? window.markdownit({ html: false, breaks: true, linkify: true }) : null;
    const markdownCache = new Map();
    if (markdown) {
        markdown.disable('image');
        markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
            tokens[index].attrSet('target', '_blank');
            tokens[index].attrSet('rel', 'noopener noreferrer');
            return renderer.renderToken(tokens, index, options);
        };
    }
    let streamAbort = null;
    let timelineResizeObserver = null;
    let scrollRestoreTimer = null;
    let adminTimer = null;
    const readVersions = new Map();
    const app = Vue.createApp({
        data: () => ({ agent, profile, profiles: [], profileError: '', sessionDetails: null, markdownReady: !!markdown, inspectorOpen: false, sessions: [], selected: null, selectedId: '', search: '', listError: '', error: '', loadingList: false, sessionStatusRefreshing: false, sessionStatusRefreshAt: 0, selectedStatusKnown: false, hasMore: false, nextCursor: null, offset: 0, sessionsOpen: false,
            adminOpen:false,adminFiles:[],adminDoc:null,adminText:'',adminOriginal:'',adminPreview:false,adminMode:'files',adminBusy:false,adminError:'',adminConflict:false,adminGateway:{},adminSettings:{},adminProvider:{},adminEndpoints:[],adminSettingsOriginal:'',adminProviderOriginal:'',adminLogText:'',adminLogKey:'',adminLogOffset:0,adminLogFollow:true,
            codexUsage:null,usageLoading:false,usageError:'',usageEpoch:0,showWorkspaceDialog:false,workspaces:{},workspaceUsages:{},workspaceLoading:false,workspaceError:'',workspaceEpoch:0,switchingWorkspace:'',usageTimer:null,
            messages: [], historyLoading: false, historyHasMore: false, oldestId: null, olderLoading: false, followLatest: true, showJumpToLatest: false, lastTimelineTop: 0, lastTimelineHeight: 0, scrollFrame: null, draft: '', attachments: [], sessionFiles: [], mediaSignature: '', filesRefreshAt: 0, uploading: false, sending: false, pending: null, delivered: [], schedules: [], showSchedule: false, scheduleText: '', scheduleAt: '', scheduleError: '', scheduleBusy: false,
            activeRun: null, runStatus: '', approval: null, context: {}, queue: [], models: [], modelSelection: { provider: '', model: '', effort: '', tier: '' }, projects: [], accounts: {}, selectedAccount: '', dialog: null, toolOffset: 0, timer: null, epoch: 0, streamHistoryIds: [],
            groupByProject: localStorage.getItem(`remotetool:web:groupByProject:${agent}`) === '1',
            collapsedGroups: (() => { try { return JSON.parse(localStorage.getItem(`remotetool:web:collapsedGroups:${agent}`) || '[]'); } catch { return []; } })(),
            showMoveDialog: false, moveTarget: null, moveMoving: false, moveSearch: '', moveError: '',
            showCreateDialog: false, createTitle: '', createProjectId: '', createMode: 'existing', createRoot: '', createRoots: [], createProjectName: '', createProjectSearch: '', createBrowser: false, createBrowserPath: '', createBrowserDirs: [], createBrowserLoading: false, createBrowserError: '', createBrowserNew: '', createBrowserMkdir: false, creating: false, createError: '', showEditDialog: false, editText: '', editTarget: null, editFiles: [], editing: false, editError: '', messageMenu: null, sessionMenu: null,
            liveSegments: [], liveTools: [], streamRun: null, streamSeq: 0, streamConnected: false, streamGone: false, toolTimer: null, externalRunning: false, externalActivity: null, toolsCollapsed: true, apiServer: null, enablingApiServer: false }),
        computed: {
            adminGatewayStatus() {
                const live=this.adminGateway.gateway;
                if(this.adminGateway.read_error || !live)return '状态不可用';
                if(!live.running)return '已停止';
                if(!live.fresh)return '进程存在，心跳已过期';
                return ({running:'运行中',draining:'排空中',starting:'启动中',stopping:'停止中'}[live.state] || live.state || '状态未知') + ` · 活跃任务 ${live.active} · PID ${live.pid}`;
            },
            headerQuotaWindows() {const windows=this.quotaGroups(this.codexUsage).flatMap(group=>group.windows);return [[300,'5 小时'],[10080,'周额度']].map(([minutes,label])=>({minutes,label,remaining:windows.find(w=>w.minutes===minutes)?.remaining??null}));},
            currentWorkspaceName() {return this.workspaceName((this.workspaces.data||[]).find(row=>row.id===this.workspaces.current)||(this.accounts.data||[]).find(row=>row.id===this.accounts.current)||this.accounts.current_identity);},
            agentName() { return this.agent==='codex'?'Codex':'Hermes'; },
            // 上下文条上的模型徽标：像 App 一样显示当前模型 + 推理强度，点击打开会话信息侧栏。
            modelChipLabel() {
                const model=this.selected?.model||this.modelSelection.model||'默认模型';
                const effort=this.modelSelection.effort||this.selected?.reasoning_effort||'';
                return effort?`${model} · ${effort}`:model;
            },
            modelChipTitle() {
                const provider=(this.providerChoices.find(item=>item.id===this.modelSelection.provider)||{}).name;
                return `提供方：${provider||'默认'}（点击修改模型）`;
            },
            filteredSessions() { const q=this.search.trim().toLowerCase(); return q?this.sessions.filter(s=>`${s.title||''} ${s.preview||''} ${s.cwd||''}`.toLowerCase().includes(q)):this.sessions; },
            displayedGroups() {
                const rows=this.filteredSessions;
                if(!(this.groupByProject&&this.agent==='codex')) return [{key:'__all__',name:'',rows}];
                // 与 App 一致：已存档会话不留在原项目里，统一沉底到「已存档」组。
                const groups=[];
                const map=new Map();
                for(const s of rows){
                    if(s.archived) continue;
                    const name=s.group_name||(s.cwd?String(s.cwd).split(/[\\/]/).filter(Boolean).pop():'')||'未分组';
                    if(!map.has(name))map.set(name,[]);
                    map.get(name).push(s);
                }
                for(const [name,list] of map) groups.push({key:name,name,rows:list});
                const archived=rows.filter(s=>s.archived);
                if(archived.length) groups.push({key:'__archived__',name:'已存档',rows:archived});
                return groups;
            },
            filteredMoveProjects() { const q=(this.moveSearch||'').trim().toLowerCase(); const rows=this.projects||[]; return q?rows.filter(p=>`${p.name||''} ${this.projectRootsOf(p).join(' ')}`.toLowerCase().includes(q)):rows; },
            filteredCreateProjects() { const q=(this.createProjectSearch||'').trim().toLowerCase(); const rows=this.projects||[]; return q?rows.filter(p=>`${p.name||''} ${this.projectRootsOf(p).join(' ')}`.toLowerCase().includes(q)):rows; },
            createProjectRoots() { return this.projectRootsOf((this.projects||[]).find(p=>p.id===this.createProjectId)); },
            createValid() { if(this.agent!=='codex'||this.createMode==='none')return true; if(this.createMode==='existing')return !!this.createProjectId&&!!this.createRoot; if(this.createMode==='new')return this.createRoots.length>0&&!!this.createRoot&&!!this.createProjectName.trim(); return true; },
            displayMessages() {const rows=this.messages.filter(m=>this.hasRenderableContent(m));const echoes=this.delivered.filter(row=>!row.canonicalId).map(row=>this.localMessage(row));if(this.pending&&Date.now()-(this.pending.createdAt||0)<=120000)echoes.push(this.localMessage(this.pending));for(const echo of echoes){const next=rows.findIndex(row=>this.messageStamp(row)>echo.timestamp);if(next<0)rows.push(echo);else rows.splice(next,0,echo);}return rows;},
            timeline() {
                // 历史消息与实时旁白按时间合并成单一时间线，插话气泡落在旁白之间的正确位置。
                const items=[];
                for (const message of this.displayMessages) items.push({kind:'message', at:this.messageStamp(message)||0, message});
                for (const segment of this.visibleLiveSegments) items.push({kind:'live', at:segment.createdAt||0, segment});
                items.sort((a,b)=>a.at-b.at);
                return items;
            },
            visibleLiveSegments() {
                // Must stay pure. Merging used to write covered.text=segment.text on a liveSegments
                // item - a reactive object this computed also reads - which invalidated the computed
                // from inside itself and made Vue's reactivity recurse (trigger -> triggerEffects ->
                // trigger) until "RangeError: Maximum call stack size exceeded". Merge on local copies
                // instead: real merging still happens in pushLiveSegment, which may mutate state.
                const out=[];
                for (const segment of this.liveSegments) {
                    const text=this.asText(segment.text);
                    if (!text.trim()) continue;
                    // 流式期间的多次 interim 各自落成一条历史消息，而 snapshot/completed 产生的是
                    // 它们的拼接段；拼接段不被任何单条消息覆盖，会与历史气泡重复显示。
                    // 因此除单条覆盖外，再对窗口内助手消息拼接后的整段做一次覆盖判断。
                    // 不能用时间窗过滤：累积的实时段包含更早的旁白，而那些行早已落库（时间窗会漏掉）。
                    const recent=this.messages.filter(m=>{if(m.role!=='assistant')return false;const native=m.native_item_id||m.item_id;if(native&&segment.nativeItem)return String(native)===String(segment.id);return Array.isArray(segment.historyIds)?!segment.historyIds.includes(String(m.id)):this.messageStamp(m)>=(segment.createdAt||0)-30000;}).slice(-400);
                    if (recent.some(m=>this.narrationCovers(this.messageText(m),text))) continue;
                    if (this.narrationCovers(recent.map(m=>this.messageText(m)).join('\n'), text)) continue;
                    // 拼接段里已经落成历史气泡的行不再重复显示，只留尚未落地的尾巴；
                    // 逐行剔除（而不是整段判断）才能覆盖"部分落库"的常见中间态。
                    const display=text.split('\n').map(line=>line.trim())
                        .filter(line=>line&&!recent.some(m=>this.narrationCovers(this.messageText(m),line)))
                        .join('\n').trim();
                    if (!display) continue;
                    const covered=out.find(prev=>this.sameNarration(prev.text,display));
                    if (covered) { if (display.length>this.asText(covered.text).trim().length) covered.text=display; continue; }
                    out.push({...segment, text:display});
                }
                return out;
            },
            statusText() { if (this.activeRun) return this.runStatus || '正在执行'; if(this.externalRunning)return this.externalActivity?.kind==='compact'?'其他客户端正在压缩上下文':'其他客户端正在执行'; const value=this.selected?.status; return (typeof value==='object'?value?.type:value) || '空闲'; },
            contextPercent() { const tokens=Number(this.context.context?.tokens||0);const limit=Number(this.context.context?.limit||0);return limit>0?Math.min(100,Math.max(0,Math.round(tokens/limit*100))):0; },
            providerChoices() { return this.models.map(p=>({id:p.slug||p.id||'',name:p.name||p.slug||p.id})); },
            modelChoices() { const found=this.models.find(p=>(p.slug||p.id)===this.modelSelection.provider); return (found?.models||[]).map(m=>typeof m==='string'?{id:m,name:m}:{id:m.model||m.id,name:m.displayName||m.name||m.model||m.id}); },
            modelMetadata() { const provider=this.models.find(p=>(p.slug||p.id)===this.modelSelection.provider); return (provider?.models||[]).find(m=>typeof m==='object'&&(m.model||m.id)===this.modelSelection.model)||{}; },
            effortChoices() { return this.agent==='codex'?(this.modelMetadata.supportedReasoningEfforts||[]).map(x=>x.reasoningEffort).filter(Boolean):['none','minimal','low','medium','high','xhigh','max','ultra']; },
            tierChoices() { return (this.modelMetadata.serviceTiers||[]).map(x=>x.id).filter(Boolean); },
            questionText() { const q=this.context.question; return q?.questions?.map(x=>x.question||x.title).join(' · ')||q?.question||'请回答会话中的问题'; },
            approvalText() { const a=this.approval; return a?.reason||a?.message||a?.description||'当前任务请求确认'; }
        },
        methods: {
            async adminAction(action) {
                if (this.adminBusy) return;
                this.adminBusy = true; this.adminError = '';
                try { await action(); }
                catch (error) { this.adminError = error.message; if (error.code === 'file_changed') this.adminConflict = true; }
                finally { this.adminBusy = false; }
            },
            editAdminProvider(endpoint = null) {
                if (this.adminBusy || this.adminProviderOriginal && JSON.stringify(this.adminProvider)!==this.adminProviderOriginal && !confirm('放弃提供方未保存的修改？')) return;
                this.adminProvider=endpoint?{...endpoint,api_key:''}:{name:'',base_url:'',model:'',api_key:'',api_mode:''};
                this.adminProviderOriginal=JSON.stringify(this.adminProvider);
            },
            adminDirty() { return this.adminText !== this.adminOriginal || !!this.adminSettingsOriginal && JSON.stringify(this.adminSettings)!==this.adminSettingsOriginal || !!this.adminProviderOriginal && JSON.stringify(this.adminProvider)!==this.adminProviderOriginal; },
            adminMayLeave() { return !this.adminBusy && (!this.adminDirty() || confirm('放弃未保存的修改？')); },
            guardAdminUnload(event) { if (this.adminOpen && this.adminDirty()) { event.preventDefault(); event.returnValue = ''; } },
            async openHermesAdmin() {
                this.closeSessionMenu(); this.closeSessionMenus(); this.adminOpen = true;
                window.addEventListener('beforeunload', this.guardAdminUnload);
                await this.adminAction(async () => { this.adminFiles = (await api.get(`${base}/admin/files`)).data || []; });
                this.pollAdminGateway();
            },
            closeHermesAdmin() { if (!this.adminMayLeave()) return; this.adminOpen=false;this.adminDoc=null;this.adminText='';this.adminOriginal='';this.adminSettings={};this.adminProvider={};this.adminSettingsOriginal='';this.adminProviderOriginal='';clearTimeout(adminTimer);window.removeEventListener('beforeunload',this.guardAdminUnload); },
            async pollAdminGateway() {
                clearTimeout(adminTimer);
                if (!this.adminOpen) return;
                try {
                    this.adminGateway = await api.get(`${base}/admin/gateway`);
                    const chunk=await api.get(`${base}/admin/gateway/log?offset=${this.adminLogOffset}&key=${api.id(this.adminLogKey)}`);
                    if(chunk.key!==this.adminLogKey){this.adminLogKey=chunk.key;this.adminLogText='';}
                    this.adminLogText=(this.adminLogText+(chunk.text||'')).slice(-120000);this.adminLogOffset=chunk.offset||0;
                    await this.$nextTick();if(this.adminLogFollow&&this.$refs.adminLog)this.$refs.adminLog.scrollTop=this.$refs.adminLog.scrollHeight;
                } catch(error) { this.adminGateway = {...this.adminGateway,read_error:error.message}; }
                if (this.adminOpen) adminTimer = setTimeout(()=>this.pollAdminGateway(),2000);
            },
            acceptAdminDoc(doc) { this.adminDoc=doc;this.adminText=doc.content||'';this.adminOriginal=this.adminText;this.adminConflict=false;this.adminPreview=false; },
            async openAdminFile(file) { if(!this.adminMayLeave())return;await this.adminAction(async()=>{this.acceptAdminDoc(await api.get(`${base}/admin/files/${api.id(file.id)}`));this.adminMode='files';this.adminSettingsOriginal='';this.adminProviderOriginal='';this.adminSettings={};this.adminProvider={};}); },
            async saveAdminFile(overwrite=false,backup=null) {
                if (!this.adminDoc) return;
                if ((overwrite||backup) && !confirm(backup?'恢复此备份？当前文件会先备份，未保存的编辑将被替换。':'覆盖电脑上的最新版本？覆盖前会备份。')) return;
                await this.adminAction(async()=>{this.acceptAdminDoc(await api.post(`${base}/admin/files/${api.id(this.adminDoc.id)}`,{revision:this.adminDoc.revision,content:this.adminText,overwrite,backup_id:backup}));this.$Message.success('已保存');});
            },
            async openAdminSettings(mode) {
                if (!this.adminMayLeave()) return;
                await this.adminAction(async()=>{
                    this.adminDoc=null;this.adminText='';this.adminOriginal='';
                    if(mode==='models') {const data=await api.get(`${base}/default-model`);this.adminSettings={provider:data.provider||'',model:data.model||'',reasoning_effort:data.reasoning_effort||''};this.models=(await api.get(`${base}/model-options`)).providers||[];const endpoints=await api.get(`${base}/providers`);this.adminEndpoints=endpoints.endpoints||[];this.adminProvider={name:'',base_url:'',model:'',api_key:'',api_mode:''};}
                    if(mode==='title') {this.adminSettings={...await api.get(`${base}/title-model`),api_key:''};}
                    this.adminMode=mode;
                    this.adminSettingsOriginal=mode==='models'||mode==='title'?JSON.stringify(this.adminSettings):'';this.adminProviderOriginal=mode==='models'?JSON.stringify(this.adminProvider):'';
                });
            },
            async saveAdminSettings() { await this.adminAction(async()=>{const result=await api.post(`${base}/${this.adminMode==='models'?'default-model':'title-model'}`,this.adminSettings);if(result.confirm_required){this.adminError='模型配置需要额外确认，请在电脑上完成。';return;}this.adminSettings.api_key='';this.adminSettingsOriginal=JSON.stringify(this.adminSettings);this.$Message.success('已保存');}); },
            async saveAdminProvider() {await this.adminAction(async()=>{const result=await api.post(`${base}/providers`,Object.fromEntries(Object.entries({...this.adminProvider,make_default:false}).filter(([key,value])=>key!=='api_key'||!!value)));if(result.confirm_required){this.adminError='配置需要额外确认，请在电脑上完成。';return;}this.adminProvider.api_key='';this.adminProviderOriginal=JSON.stringify(this.adminProvider);this.adminEndpoints=(await api.get(`${base}/providers`)).endpoints||[];this.models=(await api.get(`${base}/model-options`)).providers||[];this.$Message.success('已保存');});},
            async restartAdminGateway(wait) { if(!confirm(wait?'暂停接收新任务，等待全部 Profile 空闲后重启？':'重启当前设备的网关？将影响全部 Profile。'))return;await this.adminAction(async()=>{this.adminGateway=await api.post(`${base}/admin/gateway/restart`,{wait});}); },
            async cancelAdminGateway() {await this.adminAction(async()=>{this.adminGateway=await api.post(`${base}/admin/gateway/cancel`,{});});},
            async updateAdminHermes() {if(!confirm('等待全部 Profile 空闲后按官方流程更新并重启？将自动确认恢复本地修改。'))return;await this.adminAction(async()=>{this.adminGateway=await api.post(`${base}/admin/gateway/update`,{});});},
            async copySessionId(target = null) {
                const session = target?.id ? target : this.selected;
                this.closeSessionMenu(); this.closeSessionMenus();
                if (!session?.id) return;
                try {
                    const text = String(session.id);
                    let copied = false;
                    try { if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); copied = true; } } catch {}
                    if (!copied) {
                        const focused = document.activeElement;
                        const input = document.createElement('textarea');
                        input.value = text; input.readOnly = true;
                        input.style.cssText = 'position:fixed;left:0;top:0;opacity:0;pointer-events:none';
                        document.body.appendChild(input);
                        try { input.focus(); input.select(); input.setSelectionRange(0, text.length); copied = document.execCommand('copy'); }
                        finally { input.remove(); focused?.focus?.({preventScroll:true}); }
                    }
                    if (!copied) throw new Error('复制失败，请在会话详情中选择 ID 复制');
                    this.$Message.success('已复制会话 ID');
                } catch (error) { this.$Message.error(error.message || '复制失败'); }
            },
            async showSessionDetails(target = null) {
                const session = target?.id ? target : this.selected;
                this.closeSessionMenu(); this.closeSessionMenus();
                if (!session?.id) return;
                const key = newKey();
                this.sessionDetails = {key, session: {...session}, loading: true, error: '', sections: this.sessionDetailSections(session)};
                const path = `${base}/sessions/${api.id(session.id)}`;
                const results = await Promise.allSettled([api.get(path), api.get(`${path}/context`), api.get(`${path}/activity`)]);
                if (this.sessionDetails?.key !== key) return;
                const value = index => results[index].status === 'fulfilled' ? results[index].value : null;
                const detail = {...session, ...(value(0)?.session || value(0) || {})};
                this.sessionDetails.session = detail;
                this.sessionDetails.sections = this.sessionDetailSections(detail, value(1), value(2));
                this.sessionDetails.error = results.some(row => row.status === 'rejected') ? '部分信息读取失败，可重试。' : '';
                this.sessionDetails.loading = false;
            },
            sessionDetailSections(session, response = null, activity = null) {
                const sections = [];
                const section = (title, rows) => { const fields = rows.filter(([,value]) => value !== undefined && value !== null && value !== ''); if (fields.length) sections.push({title, fields}); };
                const stamp = raw => { if (raw == null || raw === '') return null; const number = Number(raw); const date = Number.isFinite(number) ? new Date(number < 1e12 ? number * 1000 : number) : new Date(raw); return Number.isNaN(date.getTime()) ? null : date.toLocaleString(); };
                const tokens = value => value == null || value === '' || !Number.isFinite(Number(value)) ? null : Number(value).toLocaleString();
                const flag = value => value == null ? null : value ? '是' : '否';
                const cost = value => value == null || value === '' || !Number.isFinite(Number(value)) ? null : '$' + Number(value).toFixed(6);
                let config = session.model_config || {}; if (typeof config === 'string') { try { config = JSON.parse(config); } catch { config = {}; } }
                const lock = config.browser_model_lock || {};
                const status = typeof session.status === 'object' ? session.status?.type : session.status;
                section('基本信息', [['标题',session.title], ['会话 ID',session.id], ['Profile',agent==='hermes'?profile:null], ['来源',session.source], ['originator',session.originator], ['工作目录',session.cwd], ['项目 ID',session.project_id], ['创建时间',stamp(session.started_at||session.created_at)], ['最后活跃',stamp(session.last_active||session.updated_at)], ['结束时间',stamp(session.ended_at)], ['结束原因',session.end_reason], ['父会话 ID',session.parent_session_id], ['置顶',flag(session.pinned)], ['归档',flag(session.archived)]]);
                section('模型与运行状态', [['模型',lock.model||session.model], ['提供方',session.modelProvider||session.provider||lock.provider], ['推理强度',session.reasoning_effort||lock.model_options?.reasoning?.effort], ['服务等级',session.service_tier], ['状态',activity?.available?(activity.running?'运行中':'空闲'):status], ['任务开始',stamp(activity?.started_at)], ['写入状态',({mobile:'手机端持有',busy:'其他客户端正在运行',writer_held:'其他客户端持有写入权',available:'可写入'})[session.control?.state]], ['写入说明',session.control?.message]]);
                const context = agent === 'codex' ? response?.context : response;
                section('上下文', [['已用 Token',tokens(context?.tokens)], ['上限',tokens(context?.limit)], ['用量性质',context?.available?(context.estimated?'估算':'已记录'):null]]);
                const usage = agent === 'codex' ? response?.usage || session.usage || {} : session;
                const sum = values => { const numbers = values.filter(value => value != null && value !== '' && Number.isFinite(Number(value))).map(Number); return numbers.length ? numbers.reduce((a,b)=>a+b,0) : null; };
                const hit = agent === 'codex' ? usage.cached_input_tokens : usage.cache_read_tokens;
                const miss = agent === 'codex' ? (usage.input_tokens != null && hit != null ? Math.max(0,Number(usage.input_tokens)-Number(hit)) : null) : sum([usage.input_tokens,usage.cache_write_tokens]);
                const input = agent === 'codex' ? usage.input_tokens : sum([usage.input_tokens,usage.cache_read_tokens,usage.cache_write_tokens]);
                section('累计用量', [['输入 Token',tokens(input)], ['缓存命中',tokens(hit)], ['未命中',tokens(miss)], ['输出 Token',tokens(usage.output_tokens)], ['消息数',tokens(session.message_count)], ['工具调用数',tokens(session.tool_call_count)], ['API 调用数',tokens(session.api_call_count)], ['预估费用',cost(session.estimated_cost_usd)], ['实际费用',cost(session.actual_cost_usd)]]);
                return sections;
            },
            async loadProfiles() {
                if (agent !== 'hermes') return;
                try { this.profiles = (await api.get('/api/hermes/profiles')).data || []; this.profileError = ''; }
                catch (error) { this.profileError = error.message; }
            },
            switchProfile(name) {
                const target = this.profiles.find(row => row.id === name);
                if (!target?.available || name === profile || this.sending || this.uploading) return;
                if (this.selectedId) {
                    localStorage.setItem(draftKey(this.selectedId), this.draft);
                    localStorage.setItem(draftFilesKey(this.selectedId), JSON.stringify(this.attachments));
                }
                localStorage.setItem(profilePreference, name);
                const url = new URL(location.href);
                url.searchParams.set('profile', name); url.searchParams.delete('session');
                if (window.RemoteToolWorkspace) url.hash = '#/hermes';
                // A new page owns immutable routing and subscriptions; old responses cannot enter it.
                this.stopEventStream();
                location.assign(url.href);
            },
            // Session epoch protects navigation; per-resource versions protect concurrent reads
            // within the same session (SSE, timers and manual refresh may overlap).
            startRead(kind, epoch=this.epoch) {
                const version=(readVersions.get(kind)||0)+1;
                readVersions.set(kind,version);
                return ()=>this.epoch===epoch && readVersions.get(kind)===version;
            },
            // These helpers take arguments and mutate live state; they must not be Vue computed properties.
            projectRootsOf(project) { return (project?.roots || []).map(root => root?.path).filter(Boolean); },
            asText(value) {
                try {
                    if (typeof value === 'string') return value;
                    if (value === null || value === undefined) return '';
                    if (typeof value !== 'object') return String(value);
                    if (Array.isArray(value)) return value.map(part => this.asText(part)).filter(Boolean).join('\n');
                    return this.asText(value.text !== undefined ? value.text : value.content);
                } catch (_) { return ''; }
            },
            narrationNormalized(text) { return this.asText(text).replace(/^\s*#{1,6}\s*/gm,'').replace(/\s+/g,' ').trim(); },
            narrationCovers(full,part) { const f=this.narrationNormalized(full),s=this.narrationNormalized(part); return !!f&&!!s&&(f===s||(s.length>=8&&f.includes(s))||(s.length>=4&&/[\u3400-\u9fff]/.test(s)&&(f.startsWith(s)||f.endsWith(s)))); },
            sameNarration(a,b) { return this.narrationCovers(a,b)||this.narrationCovers(b,a); },
            narrationOverlap(prev,next) { const a=this.asText(prev).split('\n').map(x=>x.trim()).filter(Boolean),b=this.asText(next).split('\n').map(x=>x.trim()).filter(Boolean); for(let k=Math.min(a.length,b.length,4);k>=1;k--){ if(a.slice(-k).join('\n')===b.slice(0,k).join('\n')) return k; } return 0; },
            pushLiveSegment(segment) {
                const text=this.asText(segment.text).trim(); if (!text) return;
                for (const existing of this.liveSegments) {
                    if (!existing.text || !existing.text.trim()) continue;
                    if (this.sameNarration(existing.text, text)) { if (text.length>existing.text.trim().length) existing.text=segment.text; existing.complete=existing.complete||segment.complete; return; }
                    const k=this.narrationOverlap(existing.text, text);
                    if (k>0) { const rest=this.asText(segment.text).split('\n').map(x=>x.trim()).filter(Boolean).slice(k).join('\n'); existing.text=existing.text.replace(/\s+$/,'')+(rest?('\n'+rest):''); existing.complete=existing.complete||segment.complete; return; }
                }
                this.liveSegments.push(segment);
            },
            hasRenderableContent(m) { return m.type==='interaction' || !!m.tool_summary_id || (m.attachments||[]).length>0 || !!this.messageBody(m).trim() || this.mediaFiles(m).length>0 || this.unresolvedMedia(m).length>0; },
            time: api.time,
            quotaGroups(usage) { const all=usage?.rateLimitsByLimitId;const entries=all&&Object.keys(all).length?Object.entries(all):usage?.rateLimits?[['codex',usage.rateLimits]]:[];return entries.map(([key,limit])=>({name:limit.limitName||limit.limitId||key,windows:['primary','secondary'].filter(k=>limit[k]).map(k=>{const w=limit[k],mins=Number(w.windowDurationMins);const used=w.usedPercent==null?NaN:Number(w.usedPercent);return {label:mins===300?'5 小时':mins===10080?'周额度':mins>0?`${mins/60} 小时`:k==='primary'?'短期额度':'长期额度',minutes:mins,remaining:Number.isFinite(used)?Math.floor(Math.min(100,Math.max(0,100-used))):null,resetsAt:w.resetsAt};})})); },
            quotaSummary(usage) { const windows=this.quotaGroups(usage).flatMap(group=>group.windows);return [[300,'5 小时'],[10080,'周额度']].map(([mins,label])=>{const w=windows.find(x=>x.minutes===mins);return `${label} · ${w?.remaining!=null?'剩余 '+w.remaining+'%':'暂无数据'}`;}).join(' / '); },
            workspaceName(row) {return row?.workspace_name||row?.name||row?.email||row?.id||'当前工作空间';},
            async loadCurrentUsage() {if(this.agent!=='codex'||this.usageLoading||this.switchingWorkspace)return;const epoch=this.usageEpoch;this.usageLoading=true;try{const spaces=await api.get(`${base}/workspaces`);if(epoch!==this.usageEpoch)return;this.workspaces=spaces;const usage=await api.get(`${base}/usage`);if(epoch===this.usageEpoch){this.codexUsage=usage;this.usageError='';}}catch(e){if(epoch===this.usageEpoch){this.codexUsage=null;this.usageError=e.message;}}finally{if(epoch===this.usageEpoch)this.usageLoading=false;}},
            async openWorkspaceDialog() {this.showWorkspaceDialog=true;await this.refreshWorkspaces();},
            async refreshWorkspaces(force=false) {if(this.workspaceLoading||this.switchingWorkspace)return;this.workspaceLoading=true;this.workspaceError='';const epoch=++this.workspaceEpoch;try{this.workspaces=await api.get(`${base}/workspaces`);this.workspaceUsages={};await Promise.all((this.workspaces.data||[]).map(async row=>{try{const result=await api.get(`${base}/workspaces/${api.id(row.id)}/usage${force?'?refresh=true':''}`);if(epoch!==this.workspaceEpoch)return;if(result.workspace_id!==row.id||(result.available&&result.chatgpt_account_id&&result.chatgpt_account_id!==row.workspace_id))throw new Error('返回的工作空间身份不匹配，请刷新');this.workspaceUsages[row.id]=result;if(row.id===this.workspaces.current&&result.available){this.codexUsage=result.usage;this.usageError='';}}catch(e){if(epoch===this.workspaceEpoch)this.workspaceUsages[row.id]={available:false,error:e.message};}}));}catch(e){if(epoch===this.workspaceEpoch)this.workspaceError=e.message;}finally{if(epoch===this.workspaceEpoch)this.workspaceLoading=false;}},
            async useWorkspace(row) {if(this.switchingWorkspace||row.id===this.workspaces.current)return;if(this.activeRun||this.externalRunning||this.sending||this.pending){this.workspaceError='请等待当前任务和消息发送结束后再切换工作空间';return;}if(!confirm(`切换到 ${this.workspaceName(row)}？桌面 Codex 也会同步切换。`))return;this.switchingWorkspace=row.id;this.workspaceError='';++this.usageEpoch;++this.workspaceEpoch;this.usageLoading=false;this.workspaceLoading=false;this.codexUsage=null;this.workspaceUsages={};try{await api.post(`${base}/workspaces/${api.id(row.id)}/use`);await this.loadCodexAdmin();this.workspaces=await api.get(`${base}/workspaces`);this.$Message.success('工作空间已切换');await this.refreshSessions();await this.loadModels();}catch(e){this.workspaceError=e.message;}finally{const switchError=this.workspaceError;this.switchingWorkspace='';await this.loadCurrentUsage();await this.refreshWorkspaces();if(switchError)this.workspaceError=switchError;}},
            contextTokens(value) { const n=(value!==null&&typeof value==='object')?NaN:Number(value); if(value==null||!Number.isFinite(n)||n<0)return '—'; if(n>=1e6)return `${(n/1e6).toFixed(2).replace(/\.?0+$/,'')}M`; if(n>=1e3)return `${(n/1e3).toFixed(1).replace(/\.0$/,'')}K`; return String(Math.round(n)); },
            closeSessionMenus(event) { document.querySelectorAll('.agent-session-actions[open]').forEach(menu=>{if(!event||!menu.contains(event.target)||event.target.closest('button'))menu.open=false;}); },
            messageStamp(m) {const raw=m.timestamp||0;const value=(raw!==null&&typeof raw==='object')?NaN:Number(raw);if(Number.isFinite(value)&&value>0)return value<1e12?value*1000:value;return typeof raw==='string'?Date.parse(raw)||0:0;}, sameText(a,b) { const norm=v=>this.asText(v).replace(/\s+/g,' ').trim(); return norm(a)===norm(b); },
            messageKey(m) { return m.localKey||m.id||`${m.role}-${m.timestamp}-${this.asText(m.content).slice(0,24)}`; },
            messageText(m) { return this.unwrapOob(this.messageTextRaw(m)); },
            // 插话在运行中被网关投递为 OOB 消息并整段落库（含附件说明），展示与本地回显匹配前先解包。
            unwrapOob(text) {
                const raw=this.asText(text); const open=raw.indexOf('[OUT-OF-BAND USER MESSAGE');
                if (open<0) return raw;
                const start=raw.indexOf(']', open); const close=raw.lastIndexOf('[/OUT-OF-BAND USER MESSAGE]');
                if (start<0||close<=start) return raw;
                let inner=raw.slice(start+1, close).trim();
                const note=inner.indexOf('[ChuckieHelper'); if (note>0) inner=inner.slice(0, note).trim();
                return inner||raw;
            },
            messageTextRaw(m) {if (typeof m.content==='string') { if (m.content.startsWith('[{')) { try { const value=JSON.parse(m.content); if (Array.isArray(value)) return value.map(x=>this.asText(x)).filter(Boolean).join('\n')||m.content; } catch {} } return m.content; } if (Array.isArray(m.content)) return m.content.map(x=>this.asText(x)).filter(Boolean).join('\n'); return this.asText(m.text); },
            mediaMarkers(text) {const lines=text.replaceAll('\r\n','\n').split('\n');let fence=null,size=0;const markers=[];for(const [index,line] of lines.entries()){const delimiter=line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);if(fence){if(delimiter&&delimiter[1][0]===fence&&delimiter[1].length>=size&&!delimiter[2].trim())fence=null;continue;}if(delimiter){fence=delimiter[1][0];size=delimiter[1].length;continue;}const match=line.match(/^ {0,3}MEDIA:[ \t]*(.+?)[ \t]*$/i);if(match)markers.push({index,path:match[1].trim().replace(/^['"]|['"]$/g,'')});}return markers;},
            normalPath(path) {return this.asText(path).replaceAll('\\','/').toLowerCase();},
            mediaMatches(message) {if(message.role!=='assistant')return[];const byId=new Map();for(const file of [...(message.attachments||[]),...this.sessionFiles.filter(file=>file.outgoing)])if(file.id&&!byId.has(file.id))byId.set(file.id,file);const candidates=[...byId.values()];return this.mediaMarkers(this.messageText(message)).map(marker=>{const path=this.normalPath(marker.path);const exact=candidates.filter(file=>file.mediaPath&&this.normalPath(file.mediaPath)===path);const name=path.split('/').at(-1);const named=candidates.filter(file=>String(file.name||'').toLowerCase()===name);const found=exact.length===1?exact[0]:named.length===1?named[0]:null;return {...marker,file:found};});},
            mediaFiles(message) {const ids=new Set();return this.mediaMatches(message).filter(marker=>marker.file&&!ids.has(marker.file.id)&&ids.add(marker.file.id)).map(marker=>marker.file);},
            unresolvedMedia(message) {return this.mediaMatches(message).filter(marker=>!marker.file);},
            linkedMediaIds(message) {return new Set(this.mediaFiles(message).map(file=>file.id));},
            messageBody(message) {const text=this.messageText(message);const markers=this.mediaMarkers(text);if(message.role!=='assistant'||!markers.length)return text;const lines=new Set(markers.map(marker=>marker.index));return text.replaceAll('\r\n','\n').split('\n').filter((_,index)=>!lines.has(index)).join('\n').trim();},
            renderMarkdown(text) {if(!markdown)return '';if(!markdownCache.has(text)){if(markdownCache.size>=200)markdownCache.clear();markdownCache.set(text,markdown.render(text));}return markdownCache.get(text);},
            mediaName(path) {return this.asText(path).replaceAll('\\','/').split('/').at(-1)||'附件';},
            mediaKind(file) {const mime=this.asText(file?.mime);return mime.startsWith('audio/')?'音频':mime.startsWith('video/')?'视频':mime.startsWith('image/')?'图片':'文件';},
            canPreviewFile(file) {const mime=this.asText(file?.mime).split(';')[0].trim().toLowerCase();if(['image/png','image/jpeg','image/gif','image/webp','image/avif','text/plain','application/json','application/pdf'].includes(mime))return true;if(!mime.startsWith('video/')&&!mime.startsWith('audio/'))return false;if(!previewSupport.has(mime))previewSupport.set(mime,!!document.createElement(mime.startsWith('video/')?'video':'audio').canPlayType(mime));return previewSupport.get(mime);},
            mediaIcon(file) {return ({'音频':'♫','视频':'▶','图片':'▧','文件':'▤'})[this.mediaKind(file)];},
            formatBytes(size) {const n=Number(size)||0;return n>=1048576?`${(n/1048576).toFixed(1)} MB`:n>=1024?`${(n/1024).toFixed(1)} KB`:`${n} B`;},
            roleLabel(m) { return m.type==='interaction'?'提问':m.role==='user'?'你':m.role==='assistant'?this.agentName:m.role==='tool'?'工具':m.role==='system'?'系统':m.role; },
            queueText(item) { return item.input?.map?.(x=>x.text||'').join(' ')||item.text||item.id||'待发送'; },
            sessionRunning(item) {
                if (item.id===this.selectedId && this.selectedStatusKnown) return !!(this.activeRun||this.externalRunning);
                const status=typeof item.status==='object'?item.status?.type:item.status;
                return status==='active'||status==='running';
            },
            async refreshSessionStatuses() {
                if (this.loadingList||this.sessionStatusRefreshing) return;
                this.sessionStatusRefreshing=true; this.sessionStatusRefreshAt=Date.now();
                try {
                    const result=await api.get(`${base}/sessions`);
                    const statuses=new Map((result.data||[]).filter(row=>row?.id).map(row=>[String(row.id),row.status]));
                    for (const row of this.sessions) if (statuses.has(String(row.id)) && !(row.id===this.selectedId && this.selectedStatusKnown)) row.status=statuses.get(String(row.id));
                } catch (_) { /* Keep the last known status; the main list reports explicit refresh errors. */ }
                finally { this.sessionStatusRefreshing=false; }
            },
            async refreshSessions(keepSelection=true) {
                this.loadingList=true; this.listError='';
                try {
                    const result=await api.get(`${base}/sessions`);
                    this.sessions=result.data||[]; this.offset=this.sessions.length; this.nextCursor=result.next_cursor||null; this.hasMore=!!result.has_more; this.sessionStatusRefreshAt=Date.now();
                    const fromUrl=window.RemoteToolWorkspace
                        ? (location.hash.startsWith(`#/${agent}?`) ? new URLSearchParams(location.hash.split('?')[1]||'').get('session') : null)
                        : new URLSearchParams(location.search).get('session');
                    const remembered=agent==='hermes'?localStorage.getItem(selectedSessionKey):null;
                    const wanted=keepSelection?this.selectedId||fromUrl||remembered:fromUrl||remembered;
                    const selected=this.sessions.find(s=>s.id===wanted)||this.sessions[0];
                    if (selected && selected.id!==this.selectedId) await this.select(selected);
                    else if (selected) this.selected=selected;
                    void this.fetchMissingContext();
                } catch(e) { this.listError=e.message; } finally { this.loadingList=false; }
            },
            // 会话列表的上下文进度条（与 App 同源字段）：codex 行由服务端 rollout 扫描自带；
            // hermes 缺数据的行走 context-bulk 批量补齐（40/块，服务端有 TTL 缓存与预算控制）。
            async fetchMissingContext() {
                if (this.agent !== 'hermes') return;
                const ids = this.sessions.filter(s => !(Number(s.context_window) > 0)).map(s => s.id).slice(0, 80);
                if (!ids.length) return;
                const merged = {};
                for (let i = 0; i < ids.length; i += 40) {
                    const chunk = ids.slice(i, i + 40);
                    try {
                        const answered = await api.get(`${base}/sessions/context-bulk?ids=` + encodeURIComponent(chunk.join(',')));
                        for (const id of chunk) {
                            const entry = answered && answered[id];
                            if (entry && Number(entry.context_used) > 0 && Number(entry.context_window) > 0) merged[id] = { used: Number(entry.context_used), window: Number(entry.context_window) };
                        }
                    } catch (e) { /* 单块失败不影响其余 */ }
                }
                if (Object.keys(merged).length) this.sessions = this.sessions.map(s => merged[s.id] ? { ...s, context_used: merged[s.id].used, context_window: merged[s.id].window } : s);
            },
            contextPct(item) { const tokens=Number(item.context_used||0); const limit=Number(item.context_window||0); return limit>0?Math.min(100,Math.max(0,Math.round(tokens/limit*100))):0; },
            contextBarTitle(item) { const pct=this.contextPct(item); return pct>0?`${this.contextTokens(Number(item.context_used||0))} / ${this.contextTokens(Number(item.context_window||0))} tokens · ${pct}%`:''; },
            async moreSessions() { if (this.loadingList||!this.hasMore) return; this.loadingList=true; try { const query=this.agent==='codex'?`cursor=${api.id(this.nextCursor||'')}`:`offset=${this.offset}`; const result=await api.get(`${base}/sessions?${query}`); const ids=new Set(this.sessions.map(x=>x.id)); this.sessions.push(...(result.data||[]).filter(x=>!ids.has(x.id))); this.offset+=result.data?.length||0; this.nextCursor=result.next_cursor||null; this.hasMore=!!result.has_more; } catch(e) { this.listError=e.message; } finally { this.loadingList=false; } },
            async select(item) {
                if (agent === 'hermes' && this.selectedId) {
                    localStorage.setItem(draftKey(this.selectedId), this.draft);
                    localStorage.setItem(draftFilesKey(this.selectedId), JSON.stringify(this.attachments));
                }
                this.stopEventStream();
                this.olderLoading=false;
                if(this.scrollFrame!==null){cancelAnimationFrame(this.scrollFrame);this.scrollFrame=null;}
                const current=++this.epoch; if(scrollRestoreTimer){clearTimeout(scrollRestoreTimer);scrollRestoreTimer=null;} this.selected=item; this.selectedId=item.id; this.selectedStatusKnown=false; this.sessionsOpen=false;this.dialog=null;this.showEditDialog=false;this.messages=[];this.historyLoading=true; this.historyHasMore=false; this.oldestId=null; this.followLatest=true;this.showJumpToLatest=false;this.lastTimelineTop=0; this.activeRun=null; this.externalRunning=false;this.externalActivity=null;this.approval=null; this.context={}; this.queue=[]; this.attachments=[]; this.sessionFiles=[];this.mediaSignature='';this.filesRefreshAt=0;this.error='';this.liveSegments=[];this.liveTools=[];this.streamSeq=0;this.streamGone=false;
                this.pending=this.restorePending(item.id);this.delivered=this.restoreDelivered(item.id);if(this.pending?.delivery==='已发送')this.acceptPending(item.id,this.pending.key); this.draft=localStorage.getItem(draftKey(item.id))||'';
                if (agent === 'hermes') localStorage.setItem(selectedSessionKey, item.id);
                try { this.attachments = JSON.parse(localStorage.getItem(draftFilesKey(item.id)) || '[]'); } catch { this.attachments = []; }
                if (window.RemoteToolWorkspace) { const route=location.hash.match(/^#\/(codex|hermes)(?=\?|$)/)?.[1]; if(route===agent){const query=new URLSearchParams(location.hash.split('?')[1]||'');query.set('session',item.id);history.replaceState(null,'',`${location.pathname}${location.search}#/${agent}?${query}`);} } else {const url=new URL(location.href);url.searchParams.set('session',item.id);if(agent==='hermes')url.searchParams.set('profile',profile);history.replaceState(null,'',url.href);}
                try { await this.refreshAll(current); if(current===this.epoch){await this.$nextTick();this.scrollToLatest();} } finally { if(current===this.epoch)this.historyLoading=false; }
            },
            async refreshAll(epoch=this.epoch) { if (!this.selectedId) return;
                const current=this.startRead('overview',epoch);
                const versions=new Map(readVersions);
                const unchanged=kind=>current()&&readVersions.get(kind)===versions.get(kind);
                // Overview 一次往返取齐历史/状态/上下文/详情，替代 5 个并行请求；
                // 服务端单项失败返回 null，此处按需回退到单独请求。
                try {
                    const overview=await api.get(`${base}/sessions/${api.id(this.selectedId)}/overview`);
                    if(!current())return;
                    await Promise.all([
                        unchanged('history')&&this.loadHistory(epoch,overview.history,overview.files),
                        unchanged('status')&&this.loadStatus(epoch,overview.active_run,overview.run,overview.activity),
                        unchanged('context')&&this.loadContext(epoch,overview.context),
                        unchanged('queue')&&this.loadQueue(epoch,overview.queue),
                        unchanged('detail')&&this.loadSessionDetail(epoch,overview.detail)]);
                } catch (error) { if(!current())return;
                    await Promise.all([unchanged('history')&&this.loadHistory(epoch),unchanged('status')&&this.loadStatus(epoch),unchanged('context')&&this.loadContext(epoch),unchanged('queue')&&this.loadQueue(epoch),unchanged('detail')&&this.loadSessionDetail(epoch)]); }
            },
            async loadSessionDetail(epoch=this.epoch, result=null) {const current=this.startRead('detail',epoch);try{result=result||await api.get(`${base}/sessions/${api.id(this.selectedId)}`);if(!current())return;const detail=result.session||result;this.selected={...this.selected,...detail};let config=detail.model_config||{};if(typeof config==='string'){try{config=JSON.parse(config);}catch{config={};}}const lock=config.browser_model_lock||{};this.modelSelection={provider:lock.provider||detail.modelProvider||detail.provider||this.modelSelection.provider,model:lock.model||detail.model||this.modelSelection.model,effort:lock.model_options?.reasoning?.effort||detail.reasoning_effort||this.modelSelection.effort,tier:detail.service_tier||this.modelSelection.tier};}catch{}},
            async loadHistory(epoch=this.epoch, result=null, files=null) {
                const id=this.selectedId, current=this.startRead('history',epoch);
                try {
                    result=result||await api.get(`${base}/sessions/${api.id(id)}/messages?limit=80`);
                    if(!current())return;
                    if(!Array.isArray(result.data))throw new Error('会话历史格式无效，请重试');
                    const recent=result.data, previous=this.messages, tail=previous.at(-1)?.id;
                    const continuous=tail!=null&&recent.some(row=>String(row.id)===String(tail));
                    const ids=new Set(recent.map(row=>String(row.id)));
                    const earlier=continuous?previous.filter(row=>row.id!=null&&!ids.has(String(row.id))):[];
                    this.messages=[...earlier,...recent];
                    this.historyHasMore=earlier.length?this.historyHasMore:!!result.has_more;
                    this.oldestId=this.messages[0]?.id||result.oldest_id;
                    const used=this.reconcileDelivered();this.reconcilePending(used);
                    const signature=this.messages.filter(m=>m.role==='assistant'&&this.messageText(m).includes('MEDIA:')).map(m=>m.id).join('|');
                    if(Array.isArray(files?.data)){
                        this.startRead('files',epoch);
                        this.sessionFiles=files.data;this.mediaSignature=signature;this.filesRefreshAt=Date.now();
                    } else if(signature&&(signature!==this.mediaSignature||Date.now()-this.filesRefreshAt>30000)){
                        this.mediaSignature=signature;this.filesRefreshAt=Date.now();this.loadFiles(epoch);
                    }
                    this.queueFollowLatest();this.scheduleScrollRestore(id);
                }catch(e){if(current())this.error=e.message;}
            },
            async loadFiles(epoch=this.epoch) {const current=this.startRead('files',epoch);try{const result=await api.get(`${base}/sessions/${api.id(this.selectedId)}/files`);if(current())this.sessionFiles=result.data||[];return result.data||[];}catch{return[];}},
            async loadOlder() {if(!this.oldestId||this.olderLoading)return;this.olderLoading=true;this.followLatest=false;this.showJumpToLatest=true;const epoch=this.epoch;const el=this.$refs.timeline;const oldHeight=el?.scrollHeight||0;const oldTop=el?.scrollTop||0;try{const result=await api.get(`${base}/sessions/${api.id(this.selectedId)}/messages?limit=80&older_before=${api.id(this.oldestId)}`);if(epoch!==this.epoch)return;const ids=new Set(this.messages.map(m=>String(m.id)));this.messages=[...(result.data||[]).filter(m=>!ids.has(String(m.id))),...this.messages];this.historyHasMore=!!result.has_more;this.oldestId=result.oldest_id||this.messages[0]?.id;await this.$nextTick();if(el)el.scrollTop=oldTop+el.scrollHeight-oldHeight;}catch(e){if(epoch===this.epoch)this.error=e.message;}finally{if(epoch===this.epoch)this.olderLoading=false;}},
            onTimelineScroll(e) {const el=e.target;const top=el.scrollTop;const height=el.scrollHeight;const distance=Math.max(0,height-el.clientHeight-top);const shrank=height<this.lastTimelineHeight;this.lastTimelineHeight=height;if(!shrank){if(top<this.lastTimelineTop-2)this.followLatest=false;else if(distance<=24&&top>this.lastTimelineTop+2)this.followLatest=true;}this.lastTimelineTop=top;this.showJumpToLatest=!this.followLatest&&!!this.selectedId&&height>el.clientHeight+4;if(!this.historyLoading&&top<80&&this.historyHasMore&&!this.olderLoading)this.loadOlder();},
            scrollToLatest(force) {const el=this.$refs.timeline;if(!el)return;if(force||this.followLatest){el.scrollTop=el.scrollHeight;this.followLatest=true;}this.lastTimelineTop=el.scrollTop;this.lastTimelineHeight=el.scrollHeight;this.showJumpToLatest=false;},
            scheduleScrollRestore(id) {if(!this.followLatest)return;if(scrollRestoreTimer)clearTimeout(scrollRestoreTimer);const started=Date.now();const tick=()=>{if(this.selectedId!==id||!this.followLatest){scrollRestoreTimer=null;return;}this.scrollToLatest();scrollRestoreTimer=Date.now()-started<1800?setTimeout(tick,250):null;};scrollRestoreTimer=setTimeout(tick,150);},
            jumpToLatest() {this.followLatest=true;this.showJumpToLatest=false;this.$nextTick(()=>this.scrollToLatest());},
            queueFollowLatest() {if(!this.followLatest||this.scrollFrame!==null)return;this.$nextTick(()=>{if(!this.followLatest||this.scrollFrame!==null)return;this.scrollFrame=requestAnimationFrame(()=>{this.scrollFrame=null;if(this.followLatest)this.scrollToLatest();});});},
            onWorkspaceRouteChange() {const host=this.$el?.nodeType===1?this.$el:this.$el?.parentElement;const module=host?.closest?.('.workspace-module');if((!module||!module.hidden)&&this.followLatest)this.queueFollowLatest();},
            async loadCapabilities() {if(this.agent!=='hermes')return;try{const result=await api.get(`${base}/capabilities`);this.apiServer=result.api_server||null;}catch{this.apiServer=null;}},
            async enableApiServer(restart=false) {if(this.agent!=='hermes'||this.enablingApiServer)return;
                if(restart&&!confirm('将修改本机 Hermes 的 .env 并重启 Hermes 网关。正在运行的 Hermes 会话会被中断。继续？'))return;
                this.enablingApiServer=true;this.error='';
                try{const result=await api.post(`${base}/api-server`,{restart});const d=result.data||result;this.apiServer=d;
                    this.$Message.success(restart?'已写入配置并请求重启 Hermes 网关':'已写入配置；请重启 Hermes 网关后生效');}
                catch(e){this.error=e.message;}finally{this.enablingApiServer=false;}},
            async loadStatus(epoch=this.epoch, state=null, runDoc=null, activityState=null) {
                const id=this.selectedId;
                const current=this.startRead('status',epoch);
                try {
                    state=state||await api.get(`${base}/sessions/${api.id(id)}/active-run`);
                    if(!current())return;
                    const runId=state.run_id||this.activeRun;
                    if(runId){
                        let run;
                        try {run=runDoc||await api.get(`${base}/runs/${api.id(runId)}`);} catch(error){if(error.status!==404)throw error;run={status:'missing'};}
                        if(!current())return;
                        if(terminal.has(run.status)||run.status==='missing'){
                            this.stopEventStream();this.liveSegments.forEach(s=>s.complete=true);this.activeRun=null;this.approval=null;this.runStatus=run.status;
                            await this.loadHistory(epoch);
                            if(!current())return;
                        }else{
                            this.activeRun=runId;this.externalRunning=false;this.externalActivity=null;this.selectedStatusKnown=true;
                            this.runStatus=run.status||'正在执行';this.approval=run.approval||null;
                            this.ensureEventStream(runId,run);return;
                        }
                    }
                    this.stopEventStream();this.activeRun=null;this.approval=null;
                    const activity=activityState||await api.get(`${base}/sessions/${api.id(id)}/activity`);
                    if(!current())return;
                    this.externalActivity=activity;this.externalRunning=!!activity.running;this.selectedStatusKnown=activity.available!==false;
                    this.liveTools=this.externalRunning?(activity.progress||[]).map(row=>({id:row.id,tool:row.tool||row.name||'工具',preview:row.preview||'',status:row.status||'running',open:false})):[];
                }catch(error){if(current())this.error=error.message;}
            },
            async takeover() {if(this.agent!=='codex'||!this.externalRunning||!this.selectedId)return;if(!confirm('将中断其他客户端正在执行的任务，并由 Web 端接管会话。继续？'))return;try{const id=this.selectedId;const result=await api.post(`${base}/sessions/${api.id(id)}/takeover`,{expected_activity_id:this.externalActivity?.activity_id||''});if(!result.ready)throw new Error('接管尚未确认');this.externalRunning=false;this.externalActivity=null;await this.refreshAll();}catch(e){this.error=e.message;}},
            stopEventStream() {streamAbort?.abort();streamAbort=null;this.streamRun=null;this.streamConnected=false;},
            ensureEventStream(runId,run={}) {if(this.streamRun===runId)return;this.stopEventStream();this.streamRun=runId;this.streamSeq=0;this.streamGone=false;const started=Number(run.created_at||run.started_at||0)*1000;this.streamHistoryIds=this.messages.filter(m=>!started||this.messageStamp(m)<started).map(m=>String(m.id));this.liveSegments=this.liveSegments.filter(s=>s.complete).slice(-100);this.liveTools=[];this.watchEventStream(runId,this.epoch);},
            async watchEventStream(runId,epoch) {let failures=0;while(this.streamRun===runId&&this.epoch===epoch&&this.activeRun===runId){const controller=new AbortController();streamAbort=controller;const started=Date.now();try{await RemoteToolSse({url:`${base}/runs/${api.id(runId)}/events`,lastId:this.streamSeq,signal:controller.signal,onConnected:()=>{if(this.streamRun===runId)this.streamConnected=true;},onEvent:event=>{if(this.streamRun===runId&&this.epoch===epoch)this.onRunEvent(event);}});if(Date.now()-started>15000)failures=0;}catch(error){if(controller.signal.aborted)break;if(error.status===404){this.streamGone=true;await this.loadHistory(epoch);break;}}finally{if(streamAbort===controller)streamAbort=null;this.streamConnected=false;}if(this.streamRun!==runId||this.epoch!==epoch||this.activeRun!==runId)break;failures++;await new Promise(resolve=>setTimeout(resolve,Math.min(30000,1500*2**Math.min(failures-1,4))));}},
            onRunEvent(event) {const seq=Number(event.seq||0);const type=event.type||event.event||'';if(seq&&seq<=this.streamSeq)return;if(type==='stream.gap'||(seq&&this.streamSeq&&seq>this.streamSeq+1)){this.loadHistory();this.refreshActiveTools();}if(seq)this.streamSeq=seq;if(type==='stream.gap')return;
                if(type==='message.started'||type==='message.delta'||type==='message.snapshot'||type==='message.completed'){const id=event.item_id||(type==='message.started'?`seq-${seq}`:this.liveSegments.at(-1)?.id||`seq-${seq}`);let segment=this.liveSegments.find(s=>s.id===id);if(!segment){segment={id,text:'',phase:event.phase||'commentary',createdAt:Date.now(),nativeItem:!!event.item_id,historyIds:[...this.streamHistoryIds]};this.liveSegments.push(segment);}if(event.phase)segment.phase=event.phase;if(type==='message.delta')segment.text+=this.asText(event.delta);else if(type==='message.snapshot'||type==='message.completed')segment.text=this.asText(event.text)||segment.text;if(type==='message.completed'){segment.complete=true;this.loadHistory();}this.queueFollowLatest();return;}
                if(type==='message.interim'){const interim=this.asText(event.text);if(interim.trim())this.pushLiveSegment({id:`interim-${seq}`,text:interim,phase:'commentary',complete:true,createdAt:Date.now(),historyIds:[...this.streamHistoryIds]});this.loadHistory();this.queueFollowLatest();return;}
                if(type==='tool.started'||type==='tool.completed'||type==='tool.failed'){const id=event.item_id||event.call_id||(type==='tool.started'?`tool-${seq}`:this.liveTools.findLast(t=>t.status==='running'&&t.tool===event.tool)?.id)||`tool-${seq}`;let tool=this.liveTools.find(t=>t.id===id);if(!tool){tool={id,tool:event.tool||'工具',preview:event.preview||'',status:'running',open:false};this.liveTools.push(tool);}if(event.preview)tool.preview=event.preview;if(type!=='tool.started')tool.status=type==='tool.failed'||event.error?'failed':'completed';if(this.dialog?.activeTools)this.refreshActiveTools();this.queueFollowLatest();return;}
                if(type==='approval.request'){this.approval=event;return;}
                if(type.startsWith('run.')){this.loadStatus();this.loadHistory();this.loadContext();}
            },
            async loadContext(epoch=this.epoch, result=null) {
                const id=this.selectedId,current=this.startRead('context',epoch);
                try {result=result||await api.get(`${base}/sessions/${api.id(id)}/context`);if(current())this.context=this.agent==='codex'?result:{context:result};}catch{}
            },
            async loadQueue(epoch=this.epoch, result=null) {
                if(this.agent!=='codex')return;
                const id=this.selectedId,current=this.startRead('queue',epoch);
                try{result=result||await api.get(`${base}/sessions/${api.id(id)}/queue`);if(current())this.queue=result.data||[];}catch{}
            },
            async loadModels() { try { const result=await api.get(`${base}/model-options`); this.models=result.providers||[]; this.modelSelection={provider:result.provider||'',model:result.model||'',effort:result.reasoning_effort||'',tier:result.service_tier||''}; } catch {} },
            async loadCodexAdmin() { if(this.agent!=='codex')return; const results=await Promise.allSettled([api.get(`${base}/projects`),api.get(`${base}/accounts`)]); if(results[0].status==='fulfilled')this.projects=results[0].value.data||[]; if(results[1].status==='fulfilled'){this.accounts=results[1].value;this.selectedAccount=this.accounts.current||'';} },
            async useAccount() {if(!this.selectedAccount||this.selectedAccount===this.accounts.current)return;const row=(this.accounts.data||[]).find(item=>item.id===this.selectedAccount);if(row)await this.useWorkspace(row);},
            providerChanged() { this.modelSelection.model='';this.modelSelection.effort='';this.modelSelection.tier=''; },
            groupRows(group) { return (this.groupByProject && this.agent === 'codex' && this.collapsedGroups.includes(group.name)) ? [] : group.rows; },
            toggleGroupMode() {
                this.groupByProject = !this.groupByProject;
                localStorage.setItem(`remotetool:web:groupByProject:${this.agent}`, this.groupByProject ? '1' : '0');
            },
            toggleGroup(name) {
                const index = this.collapsedGroups.indexOf(name);
                if (index >= 0) this.collapsedGroups.splice(index, 1); else this.collapsedGroups.push(name);
                localStorage.setItem(`remotetool:web:collapsedGroups:${this.agent}`, JSON.stringify(this.collapsedGroups));
            },
            async loadSchedules() {
                if (this.agent !== 'codex' || !this.selectedId) { this.schedules = []; return; }
                const id = this.selectedId;
                try {
                    const result = await api.get(`${base}/schedules?sessionId=${encodeURIComponent(id)}`);
                    if (this.selectedId !== id) return;
                    this.schedules = (result.items || []).filter(row => row.status === 'pending');
                } catch (error) { if (this.selectedId === id) this.schedules = []; }
            },
            localDateTimeInput(date) {
                const pad = value => String(value).padStart(2, '0');
                return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
            },
            openSchedule() {
                this.scheduleError = '';
                this.scheduleText = this.draft;
                this.scheduleAt = this.localDateTimeInput(new Date(Date.now() + 3600 * 1000));
                this.showSchedule = true;
            },
            quickSchedule(minutes, hour, plusDays) {
                const target = new Date();
                if (minutes) target.setTime(Date.now() + minutes * 60 * 1000);
                else { target.setDate(target.getDate() + (plusDays || 0)); target.setHours(hour || 0, 0, 0, 0); }
                this.scheduleAt = this.localDateTimeInput(target);
            },
            async createSchedule() {
                if (!this.selectedId || !this.scheduleText.trim() || !this.scheduleAt) return;
                const at = new Date(this.scheduleAt);
                if (Number.isNaN(at.getTime())) { this.scheduleError = '请选择有效的发送时间'; return; }
                if (at.getTime() <= Date.now()) { this.scheduleError = '请选择将来的时间'; return; }
                this.scheduleBusy = true;
                this.scheduleError = '';
                try {
                    await api.post(`${base}/schedules`, {
                        sessionId: this.selectedId,
                        text: this.scheduleText,
                        sendAt: at.toISOString(),
                        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                        clientKey: (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2)),
                    });
                    this.showSchedule = false;
                    this.scheduleText = '';
                    await this.loadSchedules();
                    this.$Message.success('定时消息已提交，由服务端到点发送');
                } catch (error) { this.scheduleError = error.message; }
                finally { this.scheduleBusy = false; }
            },
            async cancelSchedule(id) {
                try {
                    await api.delete(`${base}/schedules/${encodeURIComponent(id)}`);
                    await this.loadSchedules();
                } catch (error) { this.error = error.message; }
            },
            scheduleChip(row) {
                const date = new Date(row.send_at * 1000);
                const pad = value => String(value).padStart(2, '0');
                return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())} · ${String(row.text || '').replace(/\s+/g, ' ').slice(0, 16)}`;
            },
            openMoveDialog(session) {
                this.sessionMenu = null;
                this.moveTarget = session; this.moveError = ''; this.moveSearch = '';
                if (this.agent === 'codex' && !this.projects.length) { this.projectsLoading = true; api.get(`${base}/projects`).then(r => { this.projects = r.data || []; }).catch(e => { this.error = e.message; }).finally(() => { this.projectsLoading = false; }); }
                this.showMoveDialog = true;
            },
            async doMove(projectId) {
                if (!this.moveTarget || this.moveMoving) return;
                const id = this.moveTarget.id;
                this.moveMoving = true; this.moveError = '';
                try {
                    const result = await api.post(`${base}/sessions/${api.id(id)}/move`, { projectId: projectId || '' });
                    if (result.session) this.sessions = this.sessions.map(s => s.id === id ? result.session : s);
                    this.showMoveDialog = false; this.moveTarget = null;
                    this.$Message.success('已移动到新项目');
                } catch (e) { this.moveError = e.message; } finally { this.moveMoving = false; }
            },
            async toggleArchiveSession(session) {
                this.sessionMenu = null;
                const archived = !session.archived;
                try { await api.post(`${base}/sessions/${api.id(session.id)}/archive`, { archived: archived }); this.sessions = this.sessions.map(s => s.id === session.id ? { ...s, archived: archived } : s); this.$Message.success(archived ? '已存档' : '已取消存档'); }
                catch (e) { this.error = e.message; }
            },
            async saveModel() { if(!this.selectedId||!this.modelSelection.model)return; try {const choice=this.modelSelection;const body={provider:choice.provider,model:choice.model,reasoning_effort:choice.effort};if(this.agent==='codex')body.service_tier=choice.tier;else body.model_options={reasoning:choice.effort==='none'?{enabled:false}:choice.effort?{enabled:true,effort:choice.effort}:{}};await api.post(`${base}/sessions/${api.id(this.selectedId)}/model`,body); this.$Message.success('模型设置已更新'); await this.refreshSessions(); } catch(e) { this.error=e.message; } },
            openCreateDialog() {this.createTitle='';this.createProjectId='';this.createRoot='';this.createRoots=[];this.createProjectName='';this.createProjectSearch='';this.createMode='existing';this.createBrowser=false;this.createBrowserError='';this.createError='';this.showCreateDialog=true;if(this.agent==='codex')this.loadCodexAdmin();},
            pickCreateProject(project) {this.createProjectId=project?.id||'';const roots=this.projectRootsOf(project);this.createRoot=roots[0]||'';},
            toggleCreateRoot(path) {const rows=this.createRoots.slice();const at=rows.indexOf(path);if(at<0){if(rows.length>=16)return;rows.push(path);}else rows.splice(at,1);this.createRoots=rows;if(!rows.includes(this.createRoot))this.createRoot=rows[0]||'';},
            async openCreateBrowser() {this.createBrowser=true;this.createBrowserNew='';this.createBrowserError='';await this.loadCreateBrowser(this.createBrowserPath||'');},
            async loadCreateBrowser(path) {this.createBrowserLoading=true;this.createBrowserError='';try{const result=await api.get('/api/files/list'+(path?`?path=${api.id(path)}`:''));const rows=result.data||[];this.createBrowserDirs=rows.filter(row=>row.isDirectory!==false).map(row=>({name:row.name||row.path,path:row.path}));this.createBrowserPath=path;}catch(e){this.createBrowserError=e.message;this.createBrowserDirs=[];}finally{this.createBrowserLoading=false;}},
            enterCreateBrowser(row) {if(row?.path)this.loadCreateBrowser(row.path);},
            createBrowserUp() {const path=this.createBrowserPath||'';if(!path)return;const up=path.replace(/[\\/][^\\/]+$/,'');this.loadCreateBrowser(/^[A-Za-z]:$/.test(up)?up+'\\':(up.length>=2?up:''));},
            async mkdirInCreateBrowser() {const name=this.createBrowserNew.trim();if(!name||!this.createBrowserPath)return;const sep=this.createBrowserPath.includes('\\')&&!this.createBrowserPath.includes('/')?'\\':'/';this.createBrowserMkdir=true;try{await api.post('/api/files/create-directory',{path:this.createBrowserPath.replace(/[\\/]+$/,'')+sep+name});this.createBrowserNew='';await this.loadCreateBrowser(this.createBrowserPath);}catch(e){this.createBrowserError=e.message;}finally{this.createBrowserMkdir=false;}},
            useCreateBrowserDir() {const path=this.createBrowserPath;if(!path)return;if(!this.createRoots.includes(path))this.createRoots=[...this.createRoots,path];if(!this.createRoot)this.createRoot=path;if(!this.createProjectName.trim())this.createProjectName=path.replace(/[\\/]+$/,'').split(/[\\/]/).pop()||'';this.createBrowser=false;},
            async newSession() {if(this.creating)return;if(!this.createValid){this.createError=this.agent==='codex'?'请选择项目与工作目录（新项目需填写名称并至少选择一个文件夹）':'请检查输入';return;}const title=this.createTitle.trim();const body={title:title||`新${this.agentName}会话`};if(this.agent==='codex'){body.project_mode=this.createMode;if(this.createMode==='existing'){body.project_id=this.createProjectId;body.cwd=this.createRoot;}else if(this.createMode==='new'){body.project_name=this.createProjectName.trim();body.cwd=this.createRoot;body.roots=this.createRoots.map(path=>({path}));}}else body.auto_title=!title;this.creating=true;try{const result=await api.post(`${base}/sessions`,body);const session=result.session||result;this.showCreateDialog=false;await this.refreshSessions(false);if(session.id)await this.select(this.sessions.find(s=>s.id===session.id)||session);}catch(e){this.createError=e.message;this.error=e.message;}finally{this.creating=false;}},
            async renameSession(target=null) {const session=(target&&target.id)?target:this.selected;this.closeSessionMenu();if(!session?.id)return;const title=prompt('重命名会话',session.title||'');if(!title?.trim())return;try{await api.patch(`${base}/sessions/${api.id(session.id)}`,{title:title.trim()});session.title=title.trim();await this.refreshSessions();}catch(e){this.error=e.message;}},
            async togglePin(target=null) {const session=(target&&target.id)?target:this.selected;this.closeSessionMenu();if(!session?.id)return;try{await api.post(`${base}/sessions/${api.id(session.id)}/pin`,{pinned:!session.pinned});session.pinned=!session.pinned;await this.refreshSessions();}catch(e){this.error=e.message;}},
            async deleteSession(target=null) {const session=(target&&target.id)?target:this.selected;this.closeSessionMenu();if(!session?.id)return;if(!confirm(`删除“${session.title||'此会话'}”？`))return;try{const id=session.id;await api.post(`${base}/sessions/${api.id(id)}/delete`);localStorage.removeItem(receiptKey(id));if(this.selectedId===id){this.selected=null;this.selectedId='';}await this.refreshSessions(false);}catch(e){this.error=e.message;}},
            async forkMessage(message) {if(!this.selectedId||!message.id)return;try{const result=await api.post(`${base}/sessions/${api.id(this.selectedId)}/fork`,{message_id:message.id,fork_id:`web_fork_${newKey().replaceAll('-','')}`});const session=result.session||result;await this.refreshSessions();if(session.id)await this.select(this.sessions.find(s=>s.id===session.id)||session);}catch(e){this.error='分叉结果尚未确认，请先刷新会话列表核对。'+e.message;}},
            openMessageMenu(event,message) {if(!message?.id)return;this.messageMenu={x:Math.max(8,Math.min(event.clientX,innerWidth-190)),y:Math.max(8,Math.min(event.clientY,innerHeight-145)),message};},
            closeMessageMenu() {this.messageMenu=null;},
            openSessionMenu(event,item) {if(!item?.id)return;this.sessionMenu={x:Math.max(8,Math.min(event.clientX,innerWidth-190)),y:Math.max(8,Math.min(event.clientY,innerHeight-(agent==='codex'?370:300))),session:item};},
            closeSessionMenu() {this.sessionMenu=null;},
            escapeMessageMenu(event) {if(event.key==='Escape'){this.closeMessageMenu();this.closeSessionMenu();this.closeSessionMenus();}},
            canEditMessage(message) {return message?.role==='user'&&!!message.id&&message.editable!==false&&!this.activeRun&&!this.pending;},
            async copyMessage(message) {try{await navigator.clipboard.writeText(this.messageText(message));this.$Message.success('已复制消息');}catch(e){this.error=e.message;}finally{this.closeMessageMenu();}},
            editMessage(message) {this.closeMessageMenu();if(!this.selectedId||!this.canEditMessage(message))return;this.editTarget=message;this.editText=this.messageText(message);this.editFiles=[...(message.attachments||[])];this.editError='';this.showEditDialog=true;},
            async uploadEditFiles(event) {const files=Array.from(event.target.files||[]);event.target.value='';if(!this.selectedId||!files.length)return;this.editing=true;try{for(const file of files){const body=new FormData();body.append('file',file);const result=await api.request(`${base}/sessions/${api.id(this.selectedId)}/files`,{method:'POST',body});this.editFiles.push(result.attachment||result.file||result);}}catch(error){this.editError=error.message;}finally{this.editing=false;}},
            async submitEdit() {const message=this.editTarget;const input=this.editText.trim();if(!message||!input||this.editing||!this.canEditMessage(message))return;const id=this.selectedId;this.draft=input;this.attachments=[...this.editFiles];this.editing=true;const boundary={message_id:message.id,expected_last_turn_id:[...this.messages].reverse().find(m=>m.turn_id)?.turn_id||null,expected_latest_user_id:[...this.messages].reverse().find(m=>m.role==='user'&&m.id)?.id||null};try{const result=await api.post(`${base}/sessions/${api.id(id)}/rewind`,boundary);if(!result.rewound)throw new Error('回退未确认');this.showEditDialog=false;await this.loadHistory();await this.send();}catch(error){this.editError='结果未确认；草稿已保留，请核对会话历史。'+error.message;}finally{this.editing=false;}},
            async compact() {if(!confirm('压缩此会话上下文？'))return;try{await api.post(`${base}/sessions/${api.id(this.selectedId)}/compact`,{}, {'Idempotency-Key':newKey()});await this.loadStatus();}catch(e){this.error=e.message;}},
            async stopRun() {if(!this.activeRun)return;try{await api.post(`${base}/runs/${api.id(this.activeRun)}/stop`);await this.loadStatus();}catch(e){this.error=e.message;}},
            async approve(choice) {if(!this.activeRun||!this.approval)return;try{await api.post(`${base}/runs/${api.id(this.activeRun)}/approval`,{choice,request_id:this.approval.request_id});this.approval=null;}catch(e){this.error=e.message;}},
            answerQuestion() {const request=this.context.question;if(!request)return;const replies=[];for(const [index,question] of (request.questions||[]).entries()){const answer=prompt(`${question.question||question.title||'请回答'}${question.options?.length?'\n'+question.options.map(option=>option.label).join(' / '):''}`);if(answer===null)return;replies.push({questionItemId:JSON.stringify(['request_user_input_async',request.request_id,index]),question:question.question||'',answer});}this.draft=`<send_user_message_question_reply>\n${JSON.stringify(replies)}\n</send_user_message_question_reply>`;this.$nextTick(()=>document.querySelector('.agent-compose-wrap textarea')?.focus());},
            async answerApprovalQuestion() {if(!this.activeRun||!this.approval)return;const answers={};for(const question of this.approval.questions||[]){const answer=prompt(question.question||question.title||'请回答');if(answer===null)return;answers[question.id]={answers:[answer]};}try{await api.post(`${base}/runs/${api.id(this.activeRun)}/approval`,{request_id:this.approval.request_id,answers});this.approval=null;}catch(e){this.error=e.message;}},
            composerKeydown(e) {if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();this.send();}},
            restorePending(id) {try{const p=JSON.parse(localStorage.getItem(receiptKey(id))||'null');return p?.key?p:null;}catch{return null;}},
            savePending() {if(this.selectedId){if(this.pending)localStorage.setItem(receiptKey(this.selectedId),JSON.stringify(this.pending));else localStorage.removeItem(receiptKey(this.selectedId));}},
            restoreDelivered(id) {try{const rows=JSON.parse(localStorage.getItem(echoKey(id))||'[]');return Array.isArray(rows)?rows.filter(row=>row.key&&Date.now()-(row.createdAt||0)<1800000).slice(-20):[];}catch{return[];}},
            saveDelivered() {if(!this.selectedId)return;if(this.delivered.length)localStorage.setItem(echoKey(this.selectedId),JSON.stringify(this.delivered));else localStorage.removeItem(echoKey(this.selectedId));},
            localMessage(row) {return {role:'user',content:row.input,attachments:row.attachments||[],localKey:row.key,delivery:row.delivery,timestamp:row.createdAt};},
            localMessageMatched(row,messages,used=new Set()) {return messages.find(m=>{if(m.role!=='user'||used.has(m))return false;const key=m.client_request_id||m.clientUserMessageId||m.requestKey;if(key)return key===row.key;if(row.delivery!=='已送达')return false;const expected=(row.attachments||[]).map(f=>String(f.id)).sort().join('|');const actual=(m.attachments||[]).map(f=>String(f.id)).sort().join('|');return expected===actual&&this.sameText(this.messageText(m),row.input)&&this.messageStamp(m)>=row.createdAt-30000;});},
            reconcileDelivered() {const used=new Set();if(!this.delivered.length)return used;this.delivered=this.delivered.filter(row=>Date.now()-(row.createdAt||0)<1800000);for(const row of this.delivered){if(!row.canonicalId)continue;const match=this.messages.find(m=>m.role==='user'&&String(m.id)===String(row.canonicalId));if(match)used.add(match);}for(const row of this.delivered){if(row.canonicalId)continue;const match=this.localMessageMatched(row,this.messages,used);if(match?.id){row.canonicalId=match.id;used.add(match);}}this.saveDelivered();return used;},
            acceptPending(id,key) {if(this.selectedId!==id||this.pending?.key!==key)return;this.delivered.push({...this.pending,delivery:'已送达'});this.delivered=this.delivered.slice(-20);this.saveDelivered();this.pending=null;this.savePending();this.queueFollowLatest();},
            reconcilePending(used=new Set()) {if(this.pending&&this.localMessageMatched(this.pending,this.messages,used)){this.pending=null;this.savePending();}},
            async verifyPending() {if(!this.pending)return;const id=this.selectedId,key=this.pending.key;try{const query=`key=${api.id(key)}`+(this.agent==='hermes'?`&session_id=${api.id(id)}`:'');const result=await api.get(`${base}/runs/lookup?${query}`);if(this.selectedId!==id||this.pending?.key!==key)return;if(result.found){if(result.delivery==='rejected'){this.draft=this.pending.input;this.attachments=this.pending.attachments||[];this.pending=null;this.savePending();this.error='服务端确认未写入，草稿已恢复。';return;}const accepted=result.delivery==='accepted'||(this.agent==='hermes'&&result.delivery!=='unknown'&&!!result.run_id&&['started','submitting','running','queued','stopping','waiting_for_approval','completed','failed','cancelled','interrupted'].includes(result.status));if(accepted){if(this.pending.kind==='queue'){this.pending=null;this.savePending();this.loadQueue();}else this.acceptPending(id,key);if(result.run_id&&!terminal.has(result.status)){this.activeRun=result.run_id;this.ensureEventStream(result.run_id);}}else{this.pending.delivery='发送状态待核对';this.pending.checkedMissing=false;this.savePending();}}else{this.pending.delivery='仍未查到发送记录，请核对历史';this.pending.checkedMissing=true;this.savePending();}this.loadHistory();}catch(e){this.error=e.message;}},
            clearUnconfirmed() {if(!this.pending?.checkedMissing||!confirm('请先核对会话历史。清除本地记录后，重新发送可能产生重复消息。继续？'))return;this.draft=this.pending.input;this.attachments=this.pending.attachments||[];this.pending=null;this.savePending();},
            onPaste(e) {const items=(e.clipboardData&&e.clipboardData.items)||[];const files=[];for(const item of items){if(item.kind==='file'){const file=item.getAsFile();if(file&&/^image\//.test(file.type))files.push(file);}}if(!files.length)return;e.preventDefault();this.uploadFiles({target:{files}});},
            async uploadFiles(e) {
                const files=Array.from(e.target.files||[]),id=this.selectedId;e.target.value='';
                if(!id||!files.length||this.uploading)return;
                this.uploading=true;let owned=[...this.attachments];
                try{for(const file of files){
                    const form=new FormData();form.append('file',file);
                    const result=await api.request(`${base}/sessions/${api.id(id)}/files`,{method:'POST',body:form});
                    const attachment=result.attachment||result.file||result;
                    if(this.selectedId===id){this.attachments.push(attachment);owned=[...this.attachments];}
                    else{owned.push(attachment);localStorage.setItem(draftFilesKey(id),JSON.stringify(owned));}
                }}catch(error){if(this.selectedId===id)this.error=error.message;}
                finally{this.uploading=false;}
            },
            async enqueue() {
                const id=this.selectedId,input=this.draft.trim();
                if(this.agent!=='codex'||!id||!input||this.sending||this.uploading||this.pending||this.externalRunning)return;
                const key=newKey(),files=[...this.attachments];
                this.pending={key,input,attachments:files,createdAt:Date.now(),delivery:'正在入队',kind:'queue'};this.savePending();
                this.sending=true;this.draft='';this.attachments=[];
                localStorage.removeItem(draftKey(id));localStorage.removeItem(draftFilesKey(id));
                try {
                    await api.post(`${base}/sessions/${api.id(id)}/queue`,{action:'add',key,session_id:id,input,attachment_ids:files.map(f=>f.id)});
                    if(this.selectedId!==id){localStorage.removeItem(receiptKey(id));return;}
                    if(this.pending?.key!==key)return;
                    this.pending=null;this.savePending();await this.loadQueue();this.$Message.success('已加入队列');
                }catch(error){
                    const pending={key,input,attachments:files,createdAt:Date.now(),kind:'queue',delivery:'入队状态待核对'};
                    if(this.selectedId!==id){localStorage.setItem(receiptKey(id),JSON.stringify(pending));return;}
                    if(this.pending?.key!==key)return;
                    if(error.delivery==='rejected'){this.draft=input;this.attachments=files;this.pending=null;this.savePending();}
                    else{this.pending=pending;this.savePending();}
                    this.error=error.message;
                }finally{this.sending=false;}
            },
            async removeQueued(item) {if(!confirm('从队列移除此消息？'))return;try{await api.post(`${base}/sessions/${api.id(this.selectedId)}/queue`,{action:'delete',id:item.id});await this.loadQueue();}catch(e){this.error=e.message;}},
            async send() {const input=this.draft.trim();if(!this.selectedId||!input||this.sending||this.pending||(this.externalRunning&&this.agent==='codex'))return;const id=this.selectedId;const key=newKey();const files=[...this.attachments];const modelSettings=this.agent==='codex'?{provider:this.selected?.provider||this.selected?.modelProvider||this.modelSelection.provider,model:this.selected?.model||this.modelSelection.model,reasoning_effort:this.selected?.reasoning_effort??this.modelSelection.effort,service_tier:this.selected?.service_tier??this.modelSelection.tier}:null;const submission={key,input,attachments:files,createdAt:Date.now(),delivery:'正在发送',modelSettings};this.pending=submission;this.savePending();this.draft='';this.attachments=[];localStorage.removeItem(`remotetool:web:draft:${agent}:${id}`);this.sending=true;this.queueFollowLatest();try{const body={input,session_id:id,attachment_ids:files.map(f=>f.id)};if(this.agent==='codex'&&modelSettings?.provider&&modelSettings?.model)body.model_settings=modelSettings;if(this.agent==='codex'&&this.context.question?.request_id)body.question_reply_id=this.context.question.request_id;if(this.agent==='hermes'){if(this.modelSelection.provider)body.provider=this.modelSelection.provider;if(this.modelSelection.model)body.model=this.modelSelection.model;}const steering=!!this.activeRun;const route=steering?`${base}/runs/${api.id(this.activeRun)}/steer`:`${base}/runs`;const response=await api.post(route,body,{'Idempotency-Key':key});const accepted=response.delivery!=='unknown'&&response.delivery!=='rejected'&&response.status!=='acceptance_unknown'&&(response.delivery==='accepted'||response.accepted===true||(steering?response.accepted!==false:!!response.run_id));if(this.selectedId!==id){if(accepted){const rows=this.restoreDelivered(id);rows.push({...submission,delivery:'已送达'});localStorage.setItem(echoKey(id),JSON.stringify(rows.slice(-20)));localStorage.removeItem(receiptKey(id));}return;}if(this.pending?.key!==key)return;if(accepted){this.acceptPending(id,key);if(response.run_id){this.activeRun=response.run_id;this.ensureEventStream(response.run_id);}}else{this.pending.delivery='发送状态待核对';this.savePending();}this.loadStatus();this.loadHistory();for(const delay of [800,2500])setTimeout(()=>{if(this.selectedId===id&&(this.pending?.key===key||this.delivered.some(row=>row.key===key)))this.loadHistory();},delay);}catch(error){if(this.selectedId!==id||this.pending?.key!==key)return;if(error.delivery==='rejected'){this.draft=input;this.attachments=files;this.pending=null;this.savePending();this.error=`${error.message}。消息未写入，草稿已恢复。`;}else{this.pending.delivery='发送状态待核对';this.savePending();this.error=`${error.message}。请先核对发送状态，避免重复发送。`;}}finally{this.sending=false;}},
            async showFiles() {try{const files=await this.loadFiles();this.dialog={title:'会话文件',files,text:'',more:false};}catch(e){this.error=e.message;}},
            async showActiveTools(tool=null) {if(!this.selectedId)return;this.dialog={title:'工具调用详情',tools:[],files:[],text:'',more:false,activeTools:true,selectedTool:tool?.id||null,running:!!(this.activeRun||this.externalRunning)};await this.refreshActiveTools();if(this.toolTimer)clearInterval(this.toolTimer);this.toolTimer=setInterval(()=>{if(this.dialog?.activeTools&&(this.dialog.running||this.externalRunning))this.refreshActiveTools();},2000);},
            async refreshActiveTools(offset=0) {if(!this.dialog?.activeTools||!this.selectedId)return;try{const result=await api.get(`${base}/sessions/${api.id(this.selectedId)}/tools/active?offset=${offset}`);if(!this.dialog?.activeTools)return;const rows=result.data||[];const current=offset?this.dialog.tools||[]:[];const ids=new Set(current.map(row=>row.id));this.dialog.tools=[...current,...rows.filter(row=>!ids.has(row.id))];if(this.dialog.selectedTool){const index=this.dialog.tools.findIndex(row=>row.id===this.dialog.selectedTool);if(index>0)this.dialog.tools.unshift(...this.dialog.tools.splice(index,1));}this.dialog.running=!!result.running;this.dialog.more=!!result.has_more;this.toolOffset=offset+rows.length;this.dialog.title=`工具 ${result.tools??this.dialog.tools.length} 次 · 文件修改 ${result.file_changes??0} 次`;}catch(error){if(error.status!==404)this.error=error.message;}},
            async fetchFile(file) {const url=file.url||`${base}/sessions/${api.id(this.selectedId)}/files/${api.id(file.id)}`;const headers=new Headers();const token=localStorage.getItem('token');if(token)headers.set('Authorization',`Bearer ${token}`);const response=await fetch(url,{headers,credentials:'same-origin'});if(!response.ok)throw new Error('无法读取文件');return response.blob();},
            async openFile(file) {try{const blob=await this.fetchFile(file);const objectUrl=URL.createObjectURL(blob);window.open(objectUrl,'_blank','noopener');setTimeout(()=>URL.revokeObjectURL(objectUrl),10*60*1000);}catch(e){this.error=e.message;}},
            async downloadFile(file) {try{const blob=await this.fetchFile(file);const objectUrl=URL.createObjectURL(blob);const link=document.createElement('a');link.href=objectUrl;link.download=file.name||'attachment';document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(objectUrl),60000);}catch(e){this.error=e.message;}},
            async showTool(id) {try{this.toolOffset=0;const result=await api.get(`${base}/sessions/${api.id(this.selectedId)}/tools/${api.id(id)}?offset=0`);this.dialog={title:`工具 ${result.tools||0} 次 · 文件修改 ${result.file_changes||0} 次`,tools:result.data||[],text:'',files:[],more:!!result.has_more,toolId:id};this.toolOffset=(result.data||[]).length;}catch(e){this.error=e.message;}},
            async loadMoreTool() {if(this.dialog?.activeTools){await this.refreshActiveTools(this.toolOffset);return;}if(!this.dialog?.toolId)return;try{const result=await api.get(`${base}/sessions/${api.id(this.selectedId)}/tools/${api.id(this.dialog.toolId)}?offset=${this.toolOffset}`);this.dialog.tools.push(...(result.data||[]));this.dialog.more=!!result.has_more;this.toolOffset+=(result.data||[]).length;}catch(e){this.error=e.message;}}
        },
        created() { this.loadProfiles(); },
        unmounted() { clearTimeout(adminTimer);window.removeEventListener('beforeunload',this.guardAdminUnload); },
        watch: { selectedId() { this.loadSchedules(); }, draft(value) {if(this.selectedId)localStorage.setItem(draftKey(this.selectedId),value);}, attachments: {deep:true,handler(value){if(agent==='hermes'&&this.selectedId)localStorage.setItem(draftFilesKey(this.selectedId),JSON.stringify(value));}}, dialog(value) {if(!value?.activeTools&&this.toolTimer){clearInterval(this.toolTimer);this.toolTimer=null;}} },
        mounted() {this.refreshSessions();this.loadCapabilities();this.loadModels();this.loadCodexAdmin();this.loadCurrentUsage();this.usageTimer=setInterval(()=>{if(document.visibilityState==='visible')this.loadCurrentUsage();},60000);window.addEventListener('click',this.closeMessageMenu);window.addEventListener('click',this.closeSessionMenu);document.addEventListener('click',this.closeSessionMenus,true);window.addEventListener('keydown',this.escapeMessageMenu);window.addEventListener('workspace-route-change',this.onWorkspaceRouteChange);if(window.ResizeObserver&&this.$refs.timeline){timelineResizeObserver=new ResizeObserver(()=>this.queueFollowLatest());timelineResizeObserver.observe(this.$refs.timeline.firstElementChild||this.$refs.timeline);}this.timer=setInterval(()=>{if(document.visibilityState!=='visible')return;if(Date.now()-this.sessionStatusRefreshAt>=20000)this.refreshSessionStatuses();if(this.selectedId){this.loadStatus();if(!this.streamConnected||this.streamGone||this.liveSegments.some(segment=>segment.complete))this.loadHistory();}},10000);},
        beforeUnmount() {this.stopEventStream();timelineResizeObserver?.disconnect();timelineResizeObserver=null;if(this.scrollFrame!==null)cancelAnimationFrame(this.scrollFrame);clearInterval(this.timer);clearInterval(this.usageTimer);clearInterval(this.toolTimer);window.removeEventListener('click',this.closeMessageMenu);window.removeEventListener('click',this.closeSessionMenu);document.removeEventListener('click',this.closeSessionMenus,true);window.removeEventListener('keydown',this.escapeMessageMenu);window.removeEventListener('workspace-route-change',this.onWorkspaceRouteChange);}
    });
    app.use(ViewUIPlus);
    app.mount(root);
})();
