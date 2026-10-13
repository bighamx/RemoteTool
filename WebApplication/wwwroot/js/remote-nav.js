(() => {
    const tabs = [
        {key:'controls',label:'系统控制',docker:false}, {key:'processes',label:'进程',docker:false},
        {key:'screenshot',label:'远程桌面',docker:false}, {key:'terminal',label:'终端',docker:false},
        {key:'docker',label:'容器',docker:true}, {key:'images',label:'镜像',docker:true},
        {key:'compose',label:'Compose',docker:true}, {key:'files',label:'文件',docker:false}
    ];
    const initial = new URLSearchParams(location.hash.slice(1)).get('tab');
    const app = Vue.createApp({
        data: () => ({ tabs, current: tabs.some(item=>item.key===initial)?initial:'controls' }),
        computed: {
            isDocker() { return ['docker','images','compose'].includes(this.current); }
        },
        methods: {switchTo(key) {window.remoteControl?.switchTab(key);},sync() {this.current=window.remoteControl?.currentTab||new URLSearchParams(location.hash.slice(1)).get('tab')||'controls';this.updateRoot();},updateRoot() {const root=document.getElementById('remote-nav-app');if(root){root.classList.toggle('docker-selected',this.isDocker);root.style.display=this.isDocker?'flex':'none';}}},
        mounted() {this.sync();window.addEventListener('remote-tab-change',this.sync);window.addEventListener('hashchange',this.sync);},
        beforeUnmount() {window.removeEventListener('remote-tab-change',this.sync);window.removeEventListener('hashchange',this.sync);}
    });
    app.use(ViewUIPlus);
    app.mount('#remote-nav-app');
})();
