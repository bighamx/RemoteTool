(() => {
    const app = Vue.createApp({
        data: () => ({loading:false,error:'',stats:{},recurringCount:'—',shortcuts:[
            {href:'/#/system',icon:'/icons/speed.svg',title:'系统与监控',description:'主机状态与系统操作'},
            {href:'/#/desktop',icon:'/icons/desktop_windows.svg',title:'远程桌面',description:'画面与键鼠控制'},
            {href:'/#/docker',icon:'/icons/layers.svg',title:'Docker',description:'容器、镜像和 Compose'},
            {href:'/#/files',icon:'/icons/folder.svg',title:'文件管理',description:'浏览、编辑和传输文件'},
            {href:'/#/jobs',icon:'/icons/schedule.svg',title:'定时任务',description:'计划、执行记录和日志'},
            {href:'/#/codex',icon:'/icons/codex.png',title:'Codex',description:'项目与编码会话'},
            {href:'/#/hermes',icon:'/icons/hermes.svg',title:'Hermes',description:'智能体会话与任务'}
        ]}),
        methods:{async refresh(){this.loading=true;this.error='';try{const result=await RemoteToolApi.get('/api/native-jobs/overview');this.stats=result.stats||{};this.recurringCount=result.recurring?.length??0;}catch(e){this.error=e.message;}finally{this.loading=false;}}},
        mounted(){this.refresh();}
    });
    app.use(ViewUIPlus);
    app.mount('#overview-app');
})();
