document.addEventListener('DOMContentLoaded',()=>{
    const image=document.getElementById('preview-image'),stage=document.getElementById('image-preview'),video=document.getElementById('preview-video');
    if(!image||!stage||!video)return;
    let scale=1,x=0,y=0,pointers=new Map(),pinch=null;
    const apply=()=>{const ratio=Math.min(stage.clientWidth/(image.naturalWidth||1),stage.clientHeight/(image.naturalHeight||1));const width=image.naturalWidth*ratio,height=image.naturalHeight*ratio;const maxX=Math.max(0,(width*scale-stage.clientWidth)/2),maxY=Math.max(0,(height*scale-stage.clientHeight)/2);x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));image.style.transform=`translate(${x}px,${y}px) scale(${scale})`;};
    const reset=()=>{scale=1;x=y=0;apply();document.getElementById('preview-media-error').textContent='';};
    image.addEventListener('load',reset);
    document.getElementById('preview-fit-btn').addEventListener('click',reset);
    image.addEventListener('dblclick',e=>{e.preventDefault();scale=scale>1?1:2;apply();});
    stage.addEventListener('wheel',e=>{e.preventDefault();scale=Math.max(1,Math.min(6,scale*(e.deltaY<0?1.12:.88)));apply();},{passive:false});
    image.addEventListener('pointerdown',e=>{e.preventDefault();image.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size===2){const values=[...pointers.values()];pinch={distance:Math.hypot(values[0].x-values[1].x,values[0].y-values[1].y),scale};}});
    image.addEventListener('pointermove',e=>{const p=pointers.get(e.pointerId);if(!p)return;e.preventDefault();const dx=e.clientX-p.x,dy=e.clientY-p.y;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size===2&&pinch){const a=[...pointers.values()];scale=Math.max(1,Math.min(6,pinch.scale*Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y)/pinch.distance));}else if(scale>1){x+=dx;y+=dy;}apply();});
    for(const event of ['pointerup','pointercancel'])image.addEventListener(event,e=>{pointers.delete(e.pointerId);pinch=null;});
    video.addEventListener('error',()=>{document.getElementById('preview-media-error').textContent='当前格式或视频编码不受浏览器支持。可下载后使用兼容播放器。';});
    video.addEventListener('loadedmetadata',()=>{document.getElementById('preview-media-error').textContent='';});
    new ResizeObserver(apply).observe(stage);
});
