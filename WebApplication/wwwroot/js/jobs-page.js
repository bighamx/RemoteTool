(() => {
    const api = window.RemoteToolApi;
    const app = Vue.createApp({
        data: () => ({ loading: false, busy: false, error: '', overview: {}, jobs: [], tab: 'recurring', offset: 0, queue: 'default', detail: null, consoleText: '', consoleOffset: 0, consoleHasMore: false,
            tabs: [{key:'recurring',label:'定时'}, {key:'failed',label:'失败'}, {key:'processing',label:'执行中'}, {key:'enqueued',label:'排队'}, {key:'scheduled',label:'延迟'}, {key:'succeeded',label:'完成'}, {key:'deleted',label:'删除'}, {key:'servers',label:'服务'}],
            metrics: [{key:'enqueued',label:'排队中'}, {key:'processing',label:'执行中'}, {key:'failed',label:'失败'}, {key:'succeeded',label:'已完成'}, {key:'scheduled',label:'延迟'}] }),
        computed: { recurring() { return this.overview.recurring || []; }, servers() { return this.overview.servers || []; }, queues() { return this.overview.queues || []; } },
        methods: {
            stat(key) { const stats = this.overview.stats || {}; return stats[key] ?? stats[key[0].toUpperCase()+key.slice(1)] ?? '—'; },
            time: api.time, format(value) { return JSON.stringify(value ?? [], null, 2); },
            jsonSummary(value) { return Object.entries(value).filter(([key]) => !['name','Name'].includes(key)).map(([key,v])=>`${key}: ${typeof v === 'object' ? JSON.stringify(v) : v}`).join(' · '); },
            async refresh() { this.loading = true; this.error = ''; try { this.overview = await api.get('/api/native-jobs/overview'); this.jobs = ['recurring','servers'].includes(this.tab) ? [] : (await api.get(`/api/native-jobs/jobs?state=${this.tab}&queue=${api.id(this.queue)}&offset=${this.offset}&count=30`)).data || []; } catch(error) { this.error = error.message; } finally { this.loading = false; } },
            selectTab(value) { this.tab = value; this.offset = 0; this.refresh(); }, page(delta) { this.offset = Math.max(0,this.offset+delta*30); this.refresh(); },
            async action(path, prompt, body = {}) { if (this.busy || !confirm(prompt)) return; this.busy = true; try { await api.post(path, body); await this.refresh(); } catch(error) { this.error = error.message; } finally { this.busy = false; } },
            trigger(id) { this.action(`/api/job/${api.id(id)}/trigger`,`立即执行 ${id}？`); },
            retry(id) { this.action(`/api/native-jobs/jobs/${api.id(id)}/retry`,`重新排队任务 #${id}？`); },
            removeJob(id) { this.action(`/api/native-jobs/jobs/${api.id(id)}/delete`,`删除任务 #${id}？`); },
            removeRecurring(id) { this.action(`/api/native-jobs/recurring/${api.id(id)}/delete`,`删除定时任务 ${id}？`); },
            editRecurring(job) { const cron = prompt('Cron 表达式', job.cron); if (cron?.trim()) this.action(`/api/native-jobs/recurring/${api.id(job.id)}/update`,`将 ${job.id} 的计划修改为 ${cron}？`,{cron:cron.trim(),timeZoneId:job.timeZoneId}); },
            async showDetail(id) { if (!id) return; try { this.detail = await api.get(`/api/native-jobs/jobs/${api.id(id)}`); this.consoleText=''; this.consoleOffset=0; this.consoleHasMore=true; await this.loadConsole(); } catch(error) { this.error=error.message; } },
            async loadConsole() { if (!this.detail) return; try { const page = await api.get(`/api/native-jobs/jobs/${api.id(this.detail.id)}/console?offset=${this.consoleOffset}&count=200`); this.consoleText += (page.data||[]).map(item => typeof item==='string'?item:JSON.stringify(item)).join('\n') + '\n'; this.consoleOffset=page.nextOffset||0; this.consoleHasMore=!!page.hasMore; } catch(error) { this.error=error.message; } }
        },
        mounted() { this.refresh(); }
    });
    app.use(ViewUIPlus);
    app.mount('#jobs-app');
})();
