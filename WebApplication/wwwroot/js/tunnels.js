(() => {
    let timer;
    const app = Vue.createApp({
        data: () => ({sessions:[], loading:false, error:'', stopping:''}),
        methods: {
            size(bytes) { return bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes/1024).toFixed(1)} KiB` : `${(bytes/1048576).toFixed(1)} MiB`; },
            async refresh() {
                clearTimeout(timer); this.loading=true;
                try { this.sessions=(await RemoteToolApi.get('/api/tunnels')).data||[];this.error=''; }
                catch(error) { this.error=`状态更新失败：${error.message}`; }
                finally { this.loading=false; timer=setTimeout(()=>this.refresh(),3000); }
            },
            async stop(session) {
                if(!confirm(`停止 ${session.name} 的全部转发？`))return;
                this.stopping=session.id;
                try { await RemoteToolApi.post(`/api/tunnels/${RemoteToolApi.id(session.id)}/stop`);await this.refresh(); }
                catch(error) { this.error=error.message; }
                finally { this.stopping=''; }
            }
        },
        mounted() { this.refresh(); },
        beforeUnmount() { clearTimeout(timer); }
    });
    app.use(ViewUIPlus);app.mount('#tunnels-app');
})();
