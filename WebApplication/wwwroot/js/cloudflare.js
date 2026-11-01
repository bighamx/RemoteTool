(() => {
    const api = window.RemoteToolApi;
    let listingVersion = 0;
    const app = Vue.createApp({
        data: () => ({ accounts: [], accountId: '', tab: 'dns', rows: [], zones: [], zone: '', bucket: '', prefix: '', database: '', tables: [], table: '',
            page: 1, hasMore: false, cursor: '', nextCursor: '', cursorHistory: [], busy: false, loading: false, error: '',
            query: '', recordType: '', proxy: '', sort: 'name', direction: 'asc', dialog: '', form: {}, dialogError: '',
            tunnel: null, connections: [], routes: [], fallback: {}, tunnelConfig: {}, tunnelToken: '',
            sql: '', parameters: '[]', results: [], schema: [], tableOffset: 0 }),
        computed: {
            account() { return this.accounts.find(row => row.id === this.accountId); },
            resourceTitle() { return ({dns:'DNS 记录',r2:'R2 对象存储',d1:'D1 数据库',tunnels:'Cloudflare Tunnels'})[this.tab]; }
        },
        methods: {
            async execute(operation, body = {}, accountId = this.accountId) {
                if (!accountId) throw new Error('请先选择账户');
                return (await api.post(`/api/cloudflare/accounts/${api.id(accountId)}/execute`, { ...body, operation })).data;
            },
            async loadAccounts(selected = this.accountId) {
                try { this.accounts = (await api.get('/api/cloudflare/accounts')).data || []; this.accountId = this.accounts.some(row => row.id === selected) ? selected : this.accounts[0]?.id || ''; await this.selectAccount(); }
                catch (error) { this.error = error.message; }
            },
            async selectAccount() {
                ++listingVersion; this.dialog = ''; this.form = {}; this.tunnelToken = ''; this.zone = ''; this.zones = []; this.bucket = ''; this.prefix = ''; this.database = ''; this.tables = []; this.table = ''; this.results = []; this.schema = [];
                await this.selectTab(this.tab);
            },
            async selectTab(tab) {
                this.tab = tab; this.page = 1; this.cursor = ''; this.cursorHistory = []; this.nextCursor = ''; this.rows = []; this.hasMore = false; this.error = ''; this.results = []; this.schema = [];
                const version = ++listingVersion, accountId = this.accountId;
                if (!accountId) {this.loading = false; return;}
                this.loading = true;
                try {
                    if (tab === 'dns') {
                        const zones = []; let page = 1, result;
                        do { result = await this.execute('zones', {page}, accountId); zones.push(...(result.items || [])); page++; } while (result.hasMore && version === listingVersion);
                        if (version !== listingVersion) return;
                        this.zones = zones; if (!zones.some(row => row.id === this.zone)) this.zone = zones[0]?.id || '';
                    }
                    if (version === listingVersion) await this.load();
                } catch (error) { if (version === listingVersion) this.error = error.message; }
                finally { if (version === listingVersion) this.loading = false; }
            },
            async load(reset = false) {
                if (reset) {this.page = 1; this.cursor = ''; this.cursorHistory = [];}
                const version = ++listingVersion;
                this.rows = []; this.hasMore = false; this.nextCursor = ''; this.error = '';
                if (!this.accountId || this.tab === 'dns' && !this.zone) {this.loading = false; return;}
                this.loading = true;
                try {
                    let operation, args = {page:this.page,cursor:this.cursor || undefined};
                    if (this.tab === 'dns') {operation = 'dns.list'; Object.assign(args,{zone:this.zone,query:this.query,recordType:this.recordType,proxy:this.proxy,sort:this.sort,direction:this.direction});}
                    if (this.tab === 'r2') {operation = this.bucket ? 'r2.list' : 'r2.buckets'; Object.assign(args,{bucket:this.bucket,prefix:this.prefix});}
                    if (this.tab === 'd1') operation = 'd1.list';
                    if (this.tab === 'tunnels') operation = 'tunnels.list';
                    const result = await this.execute(operation, args);
                    if (version !== listingVersion) return;
                    this.rows = result.items || []; this.hasMore = !!result.hasMore || !!result.cursor; this.nextCursor = result.cursor || '';
                } catch (error) {if (version === listingVersion) this.error = error.message;}
                finally {if (version === listingVersion) this.loading = false;}
            },
            async turnPage(forward) {
                if (this.loading) return;
                if (this.tab === 'r2') {
                    if (forward) {this.cursorHistory.push(this.cursor); this.cursor = this.nextCursor;}
                    else this.cursor = this.cursorHistory.pop() || '';
                }
                this.page += forward ? 1 : -1; await this.load();
            },
            async action(task, close = false) {
                if (this.busy || this.loading) return;
                this.busy = true; this.error = ''; this.dialogError = '';
                try { await task(); if (close) {this.dialog = ''; this.form = {}; this.tunnelToken = '';}}
                catch (error) {if (this.dialog) this.dialogError = error.message; else this.error = error.message;}
                finally {this.busy = false;}
            },
            closeDialog() {if (this.busy) return; this.dialog = ''; this.form = {}; this.tunnelToken = ''; this.dialogError = '';},
            editAccount(row) {
                this.dialogError = ''; this.form = row ? {...row,apiToken:'',accessKeyId:'',secretAccessKey:'',clearR2:false} : {name:'',accountId:'',apiToken:'',accessKeyId:'',secretAccessKey:'',bucket:'',clearR2:false}; this.dialog = 'account';
            },
            async saveAccount() {
                await this.action(async () => {const result = await api.post('/api/cloudflare/accounts', this.form); await this.loadAccounts(result.data.id);}, true);
            },
            async deleteAccount() {
                const row = this.account;
                if (!row || !confirm(`移除本机保存的账户“${row.name}”？Cloudflare 资源会保留。`)) return;
                await this.action(async () => {await api.post(`/api/cloudflare/accounts/${api.id(row.id)}/delete`); await this.loadAccounts();});
            },
            dnsReadOnly(row) {return row.meta?.readOnly === true || row.meta?.read_only === true;},
            editDns(row) {
                this.dialogError = ''; this.form = row ? {record:row.id,type:row.type,name:row.name,content:row.content,ttl:row.ttl,proxied:row.proxied,comment:row.comment||'',priority:row.priority??0} : {type:'A',name:'',content:'',ttl:1,proxied:false,comment:'',priority:0}; this.dialog = 'dns';
            },
            async saveDns() {
                if (!confirm('保存这条 DNS 记录？这会影响对应域名的解析。')) return;
                await this.action(async () => {await this.execute('dns.save', {...this.form,zone:this.zone}); await this.load();}, true);
            },
            async remove(operation, row, args) {
                if (!confirm(`删除“${row.name || row.key || row.id || row.uuid}”？此操作无法撤销。`)) return;
                await this.action(async () => {await this.execute(operation, args); await this.load();});
            },
            async openBucket(row) {this.bucket = row.name; this.prefix = ''; await this.load(true);},
            async enterFolder(row) {this.prefix = row.key; await this.load(true);},
            async r2Up() {
                if (this.prefix) this.prefix = this.prefix.replace(/\/$/, '').split('/').slice(0,-1).join('/').replace(/(.+)/,'$1/');
                else this.bucket = '';
                await this.load(true);
            },
            async upload(event) {
                const file = event.target.files?.[0]; event.target.value = '';
                if (!file) return;
                if (!file.size || file.size > 100*1024*1024) {this.error = '文件大小应为 1 字节至 100 MiB'; return;}
                const key = this.prefix + file.name;
                if (!confirm(`上传到 ${this.bucket}/${key}？同名对象将被覆盖。`)) return;
                await this.action(async () => {
                    const data = new FormData(); data.append('file',file);
                    await api.request(`/api/cloudflare/accounts/${api.id(this.accountId)}/r2/upload?${new URLSearchParams({bucket:this.bucket,key})}`, {method:'POST',body:data}); await this.load(true);
                });
            },
            async download(row) {
                // Cookie authentication keeps the streaming download out of JS memory and URLs free of credentials.
                const link = document.createElement('a'); link.href = `/api/cloudflare/accounts/${api.id(this.accountId)}/r2/download?${new URLSearchParams({bucket:this.bucket,key:row.key})}`; link.download = row.name; document.body.append(link); link.click(); link.remove();
            },
            async openDatabase(row) {
                this.database = row.uuid || row.id; this.table = ''; this.tables = []; this.schema = []; this.results = []; this.dialog = 'd1'; this.dialogError = '';
                await this.action(async () => {this.tables = (await this.execute('d1.tables',{database:this.database})).flatMap(row => row.results || []);});
            },
            async readTable(offset = 0) {
                if (!this.table) return;
                await this.action(async () => {
                    const args = {database:this.database,table:this.table};
                    const [schema,rows] = await Promise.all([this.execute('d1.schema',args),this.execute('d1.rows',{...args,offset})]);
                    this.schema = schema.flatMap(row => row.results || []); this.results = rows;this.tableOffset=offset;
                });
            },
            async runSql() {
                if (!this.sql.trim() || !confirm('执行此 SQL？写入或删除语句会立即改变数据库。')) return;
                await this.action(async () => {
                    const parameters = JSON.parse(this.parameters || '[]');
                    if (!Array.isArray(parameters) || parameters.some(value => value !== null && !['string','number','boolean'].includes(typeof value))) throw new Error('参数应为字符串、数字、布尔值或 null 的 JSON 数组');
                    this.results = await this.execute('d1.query',{database:this.database,sql:this.sql,parameters});
                });
            },
            async createResource() {
                if (!confirm(`创建“${this.form.name}”？资源用量可能产生费用。`)) return;
                await this.action(async () => {await this.execute(this.dialog === 'd1-create' ? 'd1.create' : 'tunnels.create',{name:this.form.name}); await this.load(true);}, true);
            },
            connection(row) {
                const edges = Array.isArray(row.conns) ? row.conns : [row];
                const value = (obj,...keys) => keys.map(key => obj[key]).find(value => typeof value === 'string' && value.trim() && value !== 'null') || '';
                const join = (...keys) => [...new Set(edges.map(edge => value(edge,...keys)).filter(Boolean))].join('、');
                return {clientId: Array.isArray(row.conns) || row.version || row.arch ? value(row,'id','clientId','client_id') : value(row,'clientId','client_id'),locations:join('coloName','colo_name'),ips:join('originIp','origin_ip'),version:value(row,'version','clientVersion','client_version') || join('clientVersion','client_version'),arch:row.arch||''};
            },
            async openTunnel(row) {
                this.tunnel = row; this.form={name:row.name}; this.connections = []; this.routes = []; this.tunnelToken = ''; this.dialog = 'tunnel'; this.dialogError = '';
                await this.action(async () => {
                    const [detail,config] = await Promise.all([this.execute('tunnels.detail',{tunnel:row.id}),this.execute('tunnels.config',{tunnel:row.id})]);
                    this.connections = (detail.connections || []).map(this.connection);
                    this.tunnelConfig = config.config || config;
                    const ingress = this.tunnelConfig.ingress || [];
                    const last = ingress.at(-1);
                    this.fallback = last && !last.hostname && !last.path ? {...last} : {service:'http_status:404'};
                    this.routes = (last === ingress.at(-1) && last && !last.hostname && !last.path ? ingress.slice(0,-1) : ingress).map(row => ({...row}));
                });
            },
            moveRoute(index,delta) {const [row] = this.routes.splice(index,1); this.routes.splice(index+delta,0,row);},
            async saveRoutes() {
                if (this.routes.some(row => !row.service?.trim()) || !this.fallback.service?.trim()) {this.dialogError = '每条路由和兜底规则都需要填写服务'; return;}
                if (this.routes.some(row => !row.hostname?.trim() && !row.path?.trim())) {this.dialogError = '普通路由需填写主机名或路径；全匹配规则请放在最后'; return;}
                if (!confirm('保存路由并影响此隧道的流量？域名的 DNS 记录需要单独配置。')) return;
                await this.action(async () => {
                    const ingress = this.routes.map(row => {const copy = {...row}; if (!copy.hostname?.trim()) delete copy.hostname; if (!copy.path?.trim()) delete copy.path; return copy;});
                    await this.execute('tunnels.saveConfig',{tunnel:this.tunnel.id,config:{...this.tunnelConfig,ingress:[...ingress,{...this.fallback}]}});
                });
            },
            async renameTunnel() {
                if (!this.form.name?.trim() || !confirm(`将隧道重命名为“${this.form.name}”？`)) return;
                await this.action(async () => {await this.execute('tunnels.rename',{tunnel:this.tunnel.id,name:this.form.name});this.tunnel.name=this.form.name;await this.load();});
            },
            async showToken() {await this.action(async () => {this.tunnelToken = (await this.execute('tunnels.token',{tunnel:this.tunnel.id})).token;});},
            async disconnect(row) {
                if (!row.clientId || !confirm(`断开连接器 ${row.clientId}？其网络连接将中断。`)) return;
                await this.action(async () => {await this.execute('tunnels.disconnect',{tunnel:this.tunnel.id,clientId:row.clientId});this.connections = (await this.execute('tunnels.detail',{tunnel:this.tunnel.id})).connections.map(this.connection);});
            },
            columns(result) {return [...new Set((result.results||[]).flatMap(row => Object.keys(row)))];},
            cell(value) {return value === null ? 'NULL' : typeof value === 'object' ? JSON.stringify(value) : value;},
            time: api.time
        },
        mounted() {this.loadAccounts();}
    });
    app.use(ViewUIPlus); app.mount('#cloudflare-app');
})();
