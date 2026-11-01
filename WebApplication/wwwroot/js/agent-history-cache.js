(() => {
    const prefix='remotetool:history:v1:';
    const ttl=30*60*1000, maxRows=80, maxBytes=512*1024, maxTotal=2*1024*1024;
    let identity;
    const scope=()=>identity||(identity=Promise.all([
        window.RemoteToolApi.get('/api/device/identity'),window.RemoteToolApi.get('/api/auth/me')
    ]).then(([device,user])=>device.id&&user.authenticated&&user.username?JSON.stringify([device.id,user.username]):null).catch(()=>null));
    window.RemoteToolHistoryCache={
        create(agent,profile) {
            const memory=new Map(),versions=new Map();
            let storageScope;
            const storageKey=()=>storageScope||(storageScope=scope().then(value=>value?prefix+JSON.stringify([value,agent,profile])+':':null));
            const valid=row=>row&&Array.isArray(row.data)&&row.data.every(item=>item&&typeof item==='object'&&typeof item.role==='string')&&row.data.length<=maxRows&&Number.isFinite(row.at)&&Date.now()-row.at>=0&&Date.now()-row.at<ttl;
            const peek=id=>{const row=memory.get(id);if(!valid(row)){memory.delete(id);return null;}return JSON.parse(JSON.stringify(row));};
            return {
                peek,
                async read(id) {
                    const cached=peek(id);if(cached)return cached;
                    const version=versions.get(id)||0;
                    try {
                        const key=await storageKey();if(!key||version!==(versions.get(id)||0))return null;
                        const text=sessionStorage.getItem(key+id);if(!text||text.length*2>maxBytes)return null;
                        const row=JSON.parse(text);
                        if(!valid(row)){sessionStorage.removeItem(key+id);return null;}
                        memory.set(id,row);return peek(id);
                    }catch{return null;}
                },
                async write(id,result) {
                    const rows=result.data.slice(-maxRows);
                    const row={data:rows,has_more:!!result.has_more||result.data.length>rows.length,oldest_id:rows[0]?.id||result.oldest_id,at:Date.now()};
                    const version=versions.get(id)||0;
                    try {
                        const text=JSON.stringify(row);
                        if(text.length*2>maxBytes){await this.remove(id);return;}
                        memory.delete(id);memory.set(id,JSON.parse(text));
                        while(memory.size>20)memory.delete(memory.keys().next().value);
                        const key=await storageKey();if(!key||version!==(versions.get(id)||0))return;
                        const entries=[];
                        for(let i=0;i<sessionStorage.length;i++){
                            const name=sessionStorage.key(i);if(!name?.startsWith(prefix))continue;
                            try{const value=sessionStorage.getItem(name);entries.push({name,size:value.length*2,at:JSON.parse(value).at||0});}catch{sessionStorage.removeItem(name);}
                        }
                        let size=text.length*2,kept=1;
                        for(const entry of entries.filter(row=>row.name!==key+id).sort((a,b)=>b.at-a.at)){
                            if(kept>=20||size+entry.size>maxTotal||Date.now()-entry.at>=ttl)sessionStorage.removeItem(entry.name);
                            else {size+=entry.size;kept++;}
                        }
                        sessionStorage.setItem(key+id,text);
                    }catch{/* Storage limits or private mode must never block live history. */}
                },
                async remove(id) {
                    versions.set(id,(versions.get(id)||0)+1);memory.delete(id);
                    try{const key=await storageKey();if(key)sessionStorage.removeItem(key+id);}catch{}
                }
            };
        }
    };
})();
