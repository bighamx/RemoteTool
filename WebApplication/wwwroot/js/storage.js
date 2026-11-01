(() => {
    const root=document.getElementById('storage-app');if(!root)return;
    const api=window.RemoteToolApi;
    let timer;
    const app=Vue.createApp({
        data:()=>({inventory:null,rows:[],total:0,hasMore:false,category:'',kind:'',agent:'',search:'',sort:'size_desc',olderDays:'0',grouped:true,selected:[],plan:null,loading:false,scanning:false,busy:false,error:'',notice:'',epoch:0}),
        computed:{
            trashBytes(){return this.inventory?.categories.find(c=>c.id==='trash')?.bytes||0;},
            selectedBytes(){return this.rows.filter(r=>this.selected.includes(r.id)).reduce((n,r)=>n+r.bytes,0);},
            groups(){const map=new Map();for(const row of this.rows){const name=this.grouped?row.group||'其他文件':'';if(!map.has(name))map.set(name,[]);map.get(name).push(row);}return [...map].map(([name,rows])=>({name,rows}));}
        },
        methods:{
            bytes(value){let n=Number(value)||0,i=0;const units=['B','KB','MB','GB','TB'];while(n>=1024&&i<4){n/=1024;i++;}return (i?n.toFixed(1):n.toFixed(0))+' '+units[i];},
            time(value){return value?new Date(value).toLocaleString('zh-CN'):'时间未知';},
            kindLabel(kind){return ({image:'图片',video:'视频',audio:'音频',document:'文档',log:'日志',package:'安装包'})[kind]||'文件';},
            groupSelected(rows){const available=rows.filter(r=>!r.protected);return available.length>0&&available.every(r=>this.selected.includes(r.id));},
            selectRows(rows,checked){const ids=rows.filter(r=>!r.protected).map(r=>r.id);this.selected=checked?[...new Set([...this.selected,...ids])].slice(0,500):this.selected.filter(id=>!ids.includes(id));},
            async scan(){if(this.busy||this.scanning)return;this.scanning=true;this.error='';this.selected=[];this.epoch++;
                try{this.inventory=await api.post('/api/storage/scan',{});await this.reload();}
                catch(e){this.error=e.status===404?'当前服务端尚不支持空间管理，请先更新服务端。':e.message;}
                finally{this.scanning=false;}
            },
            changeCategory(value){this.category=value;this.reload();},
            scheduleSearch(){clearTimeout(timer);timer=setTimeout(()=>this.reload(),250);},
            async reload(){this.selected=[];this.rows=[];await this.read(0);},
            async loadMore(){if(this.loading||this.busy)return;await this.read(this.rows.length);},
            async read(offset){if(!this.inventory)return;const epoch=++this.epoch;this.loading=true;this.error='';
                const params=new URLSearchParams({snapshot:this.inventory.snapshot,category:this.category,kind:this.kind,agent:this.agent,search:this.search,sort:this.sort,olderDays:this.olderDays,offset:String(offset),limit:'100'});
                try{const result=await api.get('/api/storage/items?'+params);if(this.epoch!==epoch)return;this.rows=offset?[...this.rows,...result.data]:result.data;this.total=result.total;this.hasMore=result.hasMore;}
                catch(e){if(this.epoch===epoch)this.error=e.message;}
                finally{if(this.epoch===epoch)this.loading=false;}
            },
            async prepare(action){if(this.busy||!this.selected.length)return;this.busy=true;this.error='';
                try{this.plan=await api.post('/api/storage/prepare',{snapshot:this.inventory.snapshot,ids:this.selected,action});}
                catch(e){this.error=e.message;}finally{this.busy=false;}
            },
            async execute(){if(this.busy||!this.plan)return;this.busy=true;this.error='';
                try{const result=await api.post('/api/storage/execute',{plan:this.plan.plan});this.plan=null;this.notice=`完成 ${result.processed} 项，释放 ${this.bytes(result.freedBytes)}`+(result.failed?`；${result.failed} 项未处理：${result.failures.slice(0,2).map(f=>f.message).join('；')}`:'');this.busy=false;await this.scan();}
                catch(e){this.error='操作结果未确认，请重试同一操作或刷新核对：'+e.message;}
                finally{this.busy=false;}
            }
        },
        mounted(){this.scan();},beforeUnmount(){clearTimeout(timer);this.epoch++;}
    });app.use(ViewUIPlus);app.mount(root);
})();
