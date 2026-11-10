(() => {
    const root=document.getElementById('phone-tools-app');if(!root)return;
    const api=window.RemoteToolApi;
    const app=Vue.createApp({
        data:()=>({devices:[],connections:[],loading:false,busy:false,uncertain:false,error:'',notice:'',selectedPhone:null,advanced:false,createOpen:false,toolsOpen:false,toolsLoading:false,toolsError:'',tools:[],toolEpoch:0,credentialsOpen:false,issued:null,timer:null}),
        computed:{connectionConfig(){return this.issued?JSON.stringify({mcpServers:{'remotetool-phone':{url:this.issued.mcp_url,headers:{Authorization:'Bearer '+this.issued.token}}}},null,2):'';}},
        methods:{
            time:api.time,
            phoneName(id){return this.devices.find(row=>row.id===id)?.name||id;},
            visible(){return !root.closest('.workspace-module')?.hidden&&document.visibilityState!=='hidden';},
            async refresh(){if(this.loading||this.busy)return;this.loading=true;this.error='';try{
                const [phones,connections]=await Promise.all([api.get('/api/phone/v1/devices'),api.get('/api/phone/v1/connections')]);
                this.devices=(phones.devices||[]).filter(row=>row.online!==false);this.connections=connections.connections||[];this.uncertain=false;
            }catch(e){this.error=e.status===404?'当前服务不支持手机工具，请更新服务端。':e.message;}finally{this.loading=false;}},
            openCreate(phone){this.selectedPhone=phone;this.advanced=false;this.createOpen=true;this.notice='';},
            async createConnection(){if(this.busy||this.uncertain||!this.selectedPhone)return;this.busy=true;this.error='';
                const body={phone_id:this.selectedPhone.id,allow_advanced:this.advanced===true};
                try{this.issued=await api.post('/api/phone/v1/connections',body);this.createOpen=false;if(!this.visible()){this.issued=null;this.notice='连接已创建；离开页面后令牌已清除，可撤销后重新创建。';return;}this.credentialsOpen=true;this.notice='连接已创建。配置中包含令牌，仅交给你信任的 Agent。';}
                catch(e){this.uncertain=!e.status||e.status>=500;this.error=e.message+(this.uncertain?'；创建结果未确认，请先刷新连接列表核对。':'');}
                finally{this.busy=false;if(!this.uncertain)void this.refresh();}
            },
            async showTools(phone){this.selectedPhone=phone;this.toolsOpen=true;this.tools=[];this.toolsError='';this.toolsLoading=true;const epoch=++this.toolEpoch;
                try{const result=await api.get(`/api/phone/v1/devices/${api.id(phone.id)}/tools?advanced=false`);if(epoch===this.toolEpoch)this.tools=result.tools||[];}
                catch(e){if(epoch===this.toolEpoch)this.toolsError=e.message;}finally{if(epoch===this.toolEpoch)this.toolsLoading=false;}
            },
            async revoke(connection){if(this.busy||!confirm('撤销此手机工具连接？使用该连接的 Agent 将无法继续调用手机工具。'))return;this.busy=true;this.error='';try{await api.delete('/api/phone/v1/bindings/'+api.id(connection.id));if(this.issued?.id===connection.id)this.issued=null;this.notice='连接已撤销。';}catch(e){this.error=e.message;}finally{this.busy=false;void this.refresh();}},
            async copyConfig(){if(!this.issued)return;try{await navigator.clipboard.writeText(this.connectionConfig);this.notice='MCP 配置已复制。';}catch{this.error='无法自动复制，请选中配置文本手动复制。';}},
            credentialsVisibility(open){if(!open)this.issued=null;},
            routeChanged(){if(!this.visible()){this.issued=null;this.credentialsOpen=false;this.createOpen=false;this.toolsOpen=false;}else if(!this.loading&&!this.busy)void this.refresh();}
        },
        mounted(){void this.refresh();window.addEventListener('workspace-route-change',this.routeChanged);this.timer=setInterval(()=>{if(this.visible()&&!this.createOpen&&!this.credentialsOpen)void this.refresh();},15000);},
        beforeUnmount(){clearInterval(this.timer);window.removeEventListener('workspace-route-change',this.routeChanged);this.issued=null;}
    });app.use(ViewUIPlus);app.mount(root);
})();
