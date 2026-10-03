package com.chuckiehelper.mobile;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Annex B H.264 -> hardware decoder -> Surface; no MP4 buffering or JPEG frame decoding. */
final class H264Decoder {
    interface Listener { void onSize(int width,int height); void onFrame(boolean hardware); void onError(); }
    private final String url,cookie;
    private final Surface surface;
    private final Listener listener;
    private volatile boolean running;
    private HttpURLConnection connection;
    private MediaCodec codec;
    private Thread drain;
    private byte[] sps,pps;
    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(262144);
    private boolean hasSlice;
    private long pts;
    private volatile long lastFrameAt;
    private volatile long receivedBytes, renderedFrames;
    long receivedBytes(){return receivedBytes;}
    long renderedFrames(){return renderedFrames;}
    long lastFrameAt(){return lastFrameAt;}
    H264Decoder(String url,String cookie,Surface surface,Listener listener){this.url=url;this.cookie=cookie;this.surface=surface;this.listener=listener;}
    void start(){running=true;new Thread(this::read,"remote-h264").start();}
    void close(){running=false;if(connection!=null)connection.disconnect();}
    private void read(){
        try{
            connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(6000);connection.setReadTimeout(10000);connection.setRequestProperty("Cookie",cookie);
            int status=connection.getResponseCode();String contentType=connection.getContentType();
            if(status!=200||contentType==null||!contentType.contains("video/h264"))throw new Exception("H264 stream unavailable: HTTP "+status+", type="+contentType);
            try(InputStream in=new BufferedInputStream(connection.getInputStream(),65536)){
                byte[] chunk=new byte[32768];ByteArrayOutputStream nal=new ByteArrayOutputStream(65536);int zeros=0,n;boolean began=false;
                while(running&&(n=in.read(chunk))!=-1){
                    receivedBytes+=n;
                    for(int i=0;i<n;i++){
                        int b=chunk[i]&255;
                        if(b==0){zeros++;continue;}
                        if(b==1&&zeros>=2){if(began&&nal.size()>0)accept(nal.toByteArray());nal.reset();began=true;zeros=0;continue;}
                        if(began){while(zeros-->0)nal.write(0);nal.write(b);if(nal.size()>8_000_000)throw new Exception("Oversized NAL");}
                        zeros=0;
                    }
                }
            }
            if(running)throw new Exception("H264 stream ended");
        }catch(Exception error){if(running){android.util.Log.w("ChuckieH264","Stream or decoder failed",error);listener.onError();}}
        finally{
            running=false;if(connection!=null)connection.disconnect();
            if(drain!=null)try{drain.join(500);}catch(InterruptedException ignored){}
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}
            surface.release();
        }
    }
    private void accept(byte[] nal)throws Exception{
        int type=nal[0]&31;
        if(type==9){submit();accessUnit.reset();hasSlice=false;}
        if(type==7)sps=nal;
        if(type==8)pps=nal;
        if(codec==null&&sps!=null&&pps!=null)configure();
        accessUnit.write(new byte[]{0,0,0,1});accessUnit.write(nal);
        if(type==1||type==5)hasSlice=true;
        if(accessUnit.size()>10_000_000)throw new Exception("Oversized frame");
    }
    private void configure()throws Exception{
        int[] size=dimensions(sps);
        MediaFormat format=MediaFormat.createVideoFormat("video/avc",size[0],size[1]);
        format.setByteBuffer("csd-0",ByteBuffer.wrap(prefix(sps)));format.setByteBuffer("csd-1",ByteBuffer.wrap(prefix(pps)));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024);format.setInteger(MediaFormat.KEY_PRIORITY,0);
        if(Build.VERSION.SDK_INT>=30)format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
        codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,surface,null,0);codec.start();listener.onSize(size[0],size[1]);
        drain=new Thread(()->{
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean first=true;
            try{while(running){int index=codec.dequeueOutputBuffer(info,10000);if(index>=0){codec.releaseOutputBuffer(index,true);renderedFrames++;lastFrameAt=android.os.SystemClock.elapsedRealtime();if(first){first=false;boolean hardware=Build.VERSION.SDK_INT>=29?codec.getCodecInfo().isHardwareAccelerated():!codec.getName().startsWith("OMX.google");listener.onFrame(hardware);}}else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat output=codec.getOutputFormat();int width=output.containsKey("crop-right")&&output.containsKey("crop-left")?output.getInteger("crop-right")-output.getInteger("crop-left")+1:output.getInteger(MediaFormat.KEY_WIDTH);int height=output.containsKey("crop-bottom")&&output.containsKey("crop-top")?output.getInteger("crop-bottom")-output.getInteger("crop-top")+1:output.getInteger(MediaFormat.KEY_HEIGHT);listener.onSize(width,height);}}}
            catch(Exception error){if(running){android.util.Log.w("ChuckieH264","Output failed",error);listener.onError();}}
        },"remote-h264-render");drain.start();
    }
    private void submit()throws Exception{
        if(codec==null||!hasSlice||!running)return;
        int index=codec.dequeueInputBuffer(100000);if(index<0)throw new Exception("Decoder input stalled");
        byte[] frame=accessUnit.toByteArray();ByteBuffer input=codec.getInputBuffer(index);if(input==null||input.capacity()<frame.length)throw new Exception("Decoder frame too large");
        input.clear();input.put(frame);codec.queueInputBuffer(index,0,frame.length,pts,0);pts+=16667;
    }
    private static byte[] prefix(byte[] bytes){byte[] result=new byte[bytes.length+4];result[3]=1;System.arraycopy(bytes,0,result,4,bytes.length);return result;}
    static int[] dimensions(byte[] sps){
        ByteArrayOutputStream rbsp=new ByteArrayOutputStream();int zeros=0;
        for(int i=1;i<sps.length;i++){int b=sps[i]&255;if(zeros==2&&b==3){zeros=0;continue;}rbsp.write(b);zeros=b==0?zeros+1:0;}
        Bits bits=new Bits(rbsp.toByteArray());int profile=bits.read(8);bits.read(8);bits.read(8);bits.ue();int chroma=1,separate=0;
        if(Arrays.asList(100,110,122,244,44,83,86,118,128,138,139,134,135).contains(profile)){
            chroma=bits.ue();if(chroma==3)separate=bits.read(1);bits.ue();bits.ue();bits.read(1);
            if(bits.read(1)!=0)for(int i=0;i<(chroma==3?12:8);i++)if(bits.read(1)!=0){int last=8,next=8;for(int j=0;j<(i<6?16:64);j++){if(next!=0)next=(last+bits.se()+256)%256;if(next!=0)last=next;}}
        }
        bits.ue();int order=bits.ue();if(order==0)bits.ue();else if(order==1){bits.read(1);bits.se();bits.se();int count=bits.ue();for(int i=0;i<count;i++)bits.se();}
        bits.ue();bits.read(1);int width=(bits.ue()+1)*16,heightUnits=bits.ue()+1,frameOnly=bits.read(1);if(frameOnly==0)bits.read(1);bits.read(1);
        int left=0,right=0,top=0,bottom=0;if(bits.read(1)!=0){left=bits.ue();right=bits.ue();top=bits.ue();bottom=bits.ue();}
        int chromaArray=separate==1?0:chroma;int cropX=chromaArray==0||chromaArray==3?1:2;int cropY=(chromaArray==1?2:1)*(2-frameOnly);
        width-=(left+right)*cropX;int height=heightUnits*16*(2-frameOnly)-(top+bottom)*cropY;
        if(width<=0||height<=0||width>8192||height>8192)throw new IllegalArgumentException("Invalid H264 dimensions");return new int[]{width,height};
    }
    private static final class Bits{
        final byte[] data;int offset;Bits(byte[] data){this.data=data;}
        int read(int n){int value=0;for(int i=0;i<n;i++){if(offset>=data.length*8)throw new IllegalArgumentException("Truncated SPS");value=(value<<1)|((data[offset>>3]>>(7-(offset&7)))&1);offset++;}return value;}
        int ue(){int zeros=0;while(read(1)==0){if(++zeros>30)throw new IllegalArgumentException("Invalid SPS");}return (1<<zeros)-1+(zeros>0?read(zeros):0);}
        int se(){int n=ue();return (n&1)==1?(n+1)/2:-n/2;}
    }
}
