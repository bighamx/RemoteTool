package com.chuckiehelper.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.view.Surface;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLSocketFactory;

/** Native hardware H.264 rendering, touch mapping and WebSocket input. */
public class RemoteActivity extends Activity {
    private final ExecutorService inputs = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private String endpoint;
    private RemoteView remoteView;
    private TextView status, modeButton;
    private volatile boolean running;
    private HttpURLConnection streamConnection;
    private Thread streamThread;
    private volatile InputSocket inputSocket;
    private RelativeMotionQueue relativeMotion=new RelativeMotionQueue();
    private boolean trackpad;
    private TextureView videoTexture;
    private H264Decoder videoDecoder;
    private boolean videoActive, fallbackStarted;
    private int videoWidth=1920, videoHeight=1080;
    private volatile int session;
    private View topTools,bottomTools;
    private FrameLayout root;
    private LinearLayout topRow,bottomRow;
    private android.widget.ScrollView leftRail,rightRail;
    private TextView metrics,qualityButton;
    private boolean sideTools,toolsVisible=true,toolbarGesture;
    private int railWidth;
    private int quality;
    private static final String[] QUALITY_NAMES={"原画", "高清", "均衡", "省流", "低速网络"};
    private static final String[] QUALITY_QUERY={
        "resolution=original&bitrate=20M&maxrate=20M&fps=60&crf=16",
        "resolution=1920x1080&bitrate=8M&maxrate=10M&fps=60&crf=18",
        "resolution=1280x720&bitrate=3M&maxrate=4M&fps=30&crf=23",
        "resolution=854x480&bitrate=1M&maxrate=1M&fps=24&crf=28",
        "resolution=640x360&bitrate=250k&maxrate=500k&fps=10&crf=34&colors=rgb565"};
    private volatile long jpegBytes,jpegFrames;
    private long statsAt,statsBytes,statsFrames;
    private final Runnable updateMetrics=new Runnable(){public void run(){
        if(!running)return;
        long now=android.os.SystemClock.elapsedRealtime();
        H264Decoder decoder=videoDecoder;
        long bytes=decoder!=null?decoder.receivedBytes():jpegBytes;
        long frames=decoder!=null?decoder.renderedFrames():jpegFrames;
        double seconds=(now-statsAt)/1000.0;
        if(toolsVisible&&seconds>0&&bytes>=statsBytes&&frames>=statsFrames){
            double rate=(bytes-statsBytes)*8/seconds/1000000.0;
            metrics.setText(String.format(java.util.Locale.US,"%s · %s · %d×%d  %.2f Mbps  %.0f fps",
                QUALITY_NAMES[quality],fallbackStarted?"MJPEG":"H.264",videoWidth,videoHeight,rate,(frames-statsFrames)/seconds));
        }
        statsAt=now;statsBytes=bytes;statsFrames=frames;ui.postDelayed(this,1000);
    }};
    private TextView toolToggle;
    private final Set<Integer> heldModifiers=new HashSet<>();
    private TextView ctrlButton,altButton;
    private int recoveryAttempts;
    private Runnable pendingRecovery;
    private final Runnable videoWatchdog=this::checkVideoHealth;
    private volatile long lastJpegFrameAt;
    private final Runnable hideTools = () -> {
        toolsVisible=false;
        View first=sideTools?leftRail:topTools,second=sideTools?rightRail:bottomTools;
        for(View v:new View[]{first,second,metrics})if(v!=null)v.animate().alpha(0).setDuration(180).withEndAction(()->v.setVisibility(View.INVISIBLE));
        if(toolToggle!=null)toolToggle.setVisibility(View.VISIBLE);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(android.os.Build.VERSION.SDK_INT>=28){android.view.WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(attributes);}
        endpoint = getIntent().getStringExtra("endpoint");
        if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) { finish(); return; }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        immersive();
        root = new FrameLayout(this);
        quality=Math.max(0,Math.min(4,getPreferences(0).getInt("quality",1)));
        root.setBackgroundColor(Color.rgb(5,10,17));
        videoTexture = new TextureView(this);
        root.addView(videoTexture, new FrameLayout.LayoutParams(-1,-1));
        videoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){if(running&&!fallbackStarted)startH264(session);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){remoteView.invalidate();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(videoDecoder!=null){videoDecoder.close();videoDecoder=null;}return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
        remoteView = new RemoteView();
        root.addView(remoteView, new FrameLayout.LayoutParams(-1,-1));

        HorizontalScrollView topScroll = new HorizontalScrollView(this); topScroll.setHorizontalScrollBarEnabled(false);
        topTools=topScroll;
        topScroll.setBackgroundColor(0xb9101d2a);
        LinearLayout top = new LinearLayout(this); topRow=top; top.setOrientation(LinearLayout.HORIZONTAL); top.setPadding(dp(8),dp(6),dp(8),dp(6));
        topScroll.addView(top);
        add(top,"返回",() -> finish());
        add(top,"适应",() -> remoteView.resetZoom());
        add(top,"横屏",() -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        add(top,"竖屏",() -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        modeButton = add(top,"直接触控",() -> {trackpad=!trackpad;modeButton.setText(trackpad?"触控板":"直接触控");});
        qualityButton=add(top,QUALITY_NAMES[quality],this::chooseQuality);
        status = add(top,"连接中…",() -> reconnect());
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP); root.addView(topScroll,topParams);

        HorizontalScrollView bottomScroll = new HorizontalScrollView(this); bottomScroll.setHorizontalScrollBarEnabled(false);
        bottomTools=bottomScroll;
        bottomScroll.setBackgroundColor(0xb9101d2a);
        LinearLayout bottom = new LinearLayout(this); bottomRow=bottom; bottom.setOrientation(LinearLayout.HORIZONTAL); bottom.setPadding(dp(8),dp(6),dp(8),dp(6));
        bottomScroll.addView(bottom);
        add(bottom,"键盘",this::openPhoneKeyboard);
        add(bottom,"发送文本",this::showKeyboard);
        add(bottom,"右键",() -> remoteView.click("right-click"));
        add(bottom,"左键",() -> remoteView.click("click"));
        add(bottom,"上滚",() -> remoteView.wheel(120));
        add(bottom,"下滚",() -> remoteView.wheel(-120));
        ctrlButton=add(bottom,"Ctrl",() -> toggleModifier(17,ctrlButton,"Ctrl"));
        altButton=add(bottom,"Alt",() -> toggleModifier(18,altButton,"Alt"));
        add(bottom,"Esc",() -> key(27));
        add(bottom,"Enter",() -> key(13));
        add(bottom,"Tab",() -> key(9));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); root.addView(bottomScroll,bottomParams);
        toolToggle=new TextView(this);toolToggle.setText("工具");toolToggle.setTextColor(Color.WHITE);toolToggle.setTextSize(12);toolToggle.setPadding(dp(12),dp(8),dp(12),dp(8));toolToggle.setBackgroundColor(0xa8203547);toolToggle.setVisibility(View.GONE);toolToggle.setOnClickListener(v->showTools());
        FrameLayout.LayoutParams toggleParams=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL);toggleParams.rightMargin=dp(8);root.addView(toolToggle,toggleParams);
        leftRail=new android.widget.ScrollView(this);rightRail=new android.widget.ScrollView(this);
        leftRail.setFillViewport(false);rightRail.setFillViewport(false);
        leftRail.setOnTouchListener((v,event)->railBlankTouch(leftRail,topRow,event));
        rightRail.setOnTouchListener((v,event)->railBlankTouch(rightRail,bottomRow,event));
        leftRail.setBackgroundColor(0xff101d2a);rightRail.setBackgroundColor(0xff101d2a);
        leftRail.setVisibility(View.GONE);rightRail.setVisibility(View.GONE);
        root.addView(leftRail,new FrameLayout.LayoutParams(dp(80),-1,Gravity.START));
        root.addView(rightRail,new FrameLayout.LayoutParams(dp(80),-1,Gravity.END));
        metrics=new TextView(this);metrics.setTextColor(0xffd3e6f2);metrics.setTextSize(11);
        metrics.setPadding(dp(8),dp(3),dp(8),dp(3));metrics.setBackgroundColor(0x99101d2a);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        mp.bottomMargin=dp(2);root.addView(metrics,mp);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutTools());
        setContentView(root);
        showTools();
    }

    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private void immersive() { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if(focused) immersive(); }
    @Override protected void onResume() { super.onResume(); recoveryAttempts=0; if(remoteView!=null) startRemote(); }
    @Override protected void onPause() { stopRemote(); super.onPause(); }
    @Override protected void onDestroy() { stopRemote(); ui.removeCallbacks(hideTools); inputs.shutdown(); if(remoteView!=null)remoteView.clear(); super.onDestroy(); }

    private TextView add(LinearLayout row,String label,Runnable action) {
        TextView button=new TextView(this);button.setText(label);button.setTextColor(Color.WHITE);button.setTextSize(14);button.setGravity(Gravity.CENTER);button.setPadding(dp(14),dp(10),dp(14),dp(10));
        GradientDrawable background=new GradientDrawable();background.setColor(0xda263d51);background.setCornerRadius(dp(11));button.setBackground(background);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(36));p.rightMargin=dp(7);row.addView(button,p);button.setOnClickListener(v->{showTools();action.run();});return button;
    }
    private void showTools(){
        toolsVisible=true;ui.removeCallbacks(hideTools);if(topTools==null||bottomTools==null||toolToggle==null)return;
        View first=sideTools?leftRail:topTools,second=sideTools?rightRail:bottomTools;
        for(View v:new View[]{first,second,metrics})if(v!=null){v.animate().cancel();v.setVisibility(View.VISIBLE);v.setAlpha(1);}
        toolToggle.setVisibility(View.GONE);ui.postDelayed(hideTools,3200);
    }
    private void toggleTools(){if(toolsVisible){ui.removeCallbacks(hideTools);hideTools.run();}else showTools();}
    private boolean railBlankTouch(android.widget.ScrollView rail,LinearLayout row,MotionEvent event){
        if(event.getY()+rail.getScrollY()<row.getBottom())return false;
        if(event.getActionMasked()==MotionEvent.ACTION_UP)toggleTools();
        return true;
    }
    private void chooseQuality(){
        new AlertDialog.Builder(this).setTitle("画质 · 分辨率 / 上限码率 / 帧率")
            .setSingleChoiceItems(new String[]{"原画 · 原始分辨率 / 20 Mbps / 60 fps","高清 · 1080p / 10 Mbps / 60 fps","均衡 · 720p / 4 Mbps / 30 fps","省流 · 480p / 1 Mbps / 24 fps","低速网络 · 360p / 500 Kbps / 10 fps · 减少颜色"},quality,(dialog,index)->{
                quality=index;getPreferences(0).edit().putInt("quality",index).apply();qualityButton.setText(QUALITY_NAMES[index]);
                dialog.dismiss();remoteView.resetZoom();reconnect();
            }).setNegativeButton("取消",null).show();
    }
    private void layoutTools(){
        if(root==null||topRow==null||videoWidth<=0||videoHeight<=0)return;
        int w=root.getWidth(),h=root.getHeight();if(w==0||h==0)return;
        float fit=Math.min(w/(float)videoWidth,h/(float)videoHeight);
        int gap=(int)((w-videoWidth*fit)/2);
        boolean sides=w>h&&gap>=dp(56);
        int width=sides?Math.min(gap,dp(116)):0;
        if(sides==sideTools&&width==railWidth)return;
        sideTools=sides;railWidth=width;ui.removeCallbacks(hideTools);
        if(sides){
            ((ViewGroup)topRow.getParent()).removeView(topRow);((ViewGroup)bottomRow.getParent()).removeView(bottomRow);
            arrangeButtons(topRow,true);arrangeButtons(bottomRow,true);
            leftRail.addView(topRow);rightRail.addView(bottomRow);
            leftRail.setLayoutParams(new FrameLayout.LayoutParams(width,-1,Gravity.START));
            rightRail.setLayoutParams(new FrameLayout.LayoutParams(width,-1,Gravity.END));
            leftRail.setVisibility(View.VISIBLE);rightRail.setVisibility(View.VISIBLE);
            topTools.setVisibility(View.GONE);bottomTools.setVisibility(View.GONE);showTools();
        }else{
            ((ViewGroup)topRow.getParent()).removeView(topRow);((ViewGroup)bottomRow.getParent()).removeView(bottomRow);
            arrangeButtons(topRow,false);arrangeButtons(bottomRow,false);
            ((HorizontalScrollView)topTools).addView(topRow);((HorizontalScrollView)bottomTools).addView(bottomRow);
            leftRail.setVisibility(View.GONE);rightRail.setVisibility(View.GONE);showTools();
        }
    }
    private void arrangeButtons(LinearLayout row,boolean vertical){
        row.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        row.setPadding(dp(4),dp(8),dp(4),dp(8));
        for(int i=0;i<row.getChildCount();i++){
            TextView button=(TextView)row.getChildAt(i);button.setTextSize(vertical?12:14);
            button.setPadding(dp(4),dp(4),dp(4),dp(4));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(vertical?-1:-2,dp(44));
            if(vertical)p.bottomMargin=dp(6);else p.rightMargin=dp(7);button.setLayoutParams(p);
        }
    }
    private void setStatus(String value) { ui.post(() -> {if(status!=null)status.setText(value);}); }

    private void startRemote() {
        if(running)return;
        running=true;relativeMotion=new RelativeMotionQueue();
        final int epoch=++session;
        fallbackStarted=false;videoActive=false;
        lastJpegFrameAt=0;jpegBytes=jpegFrames=0;statsBytes=statsFrames=0;statsAt=android.os.SystemClock.elapsedRealtime();
        ui.removeCallbacks(updateMetrics);ui.postDelayed(updateMetrics,1000);
        videoTexture.setVisibility(View.VISIBLE);
        setStatus("连接中…");
        inputs.execute(() -> {InputSocket socket=new InputSocket();try {socket.open();if(running&&epoch==session)inputSocket=socket;else socket.close();}catch(Exception e){socket.close();}});
        startH264(epoch);
        ui.postDelayed(()->{if(running&&epoch==session&&!videoActive)fallback(epoch);},10000);
        ui.removeCallbacks(videoWatchdog);ui.postDelayed(videoWatchdog,2500);
    }
    private void startH264(int epoch){
        if(!running||epoch!=session||videoDecoder!=null||!videoTexture.isAvailable())return;
        videoTexture.setVisibility(View.VISIBLE);
        videoDecoder=new H264Decoder(endpoint+"/api/stream?format=h264&"+QUALITY_QUERY[quality],cookie(),new Surface(videoTexture.getSurfaceTexture()),new H264Decoder.Listener(){
            public void onSize(int width,int height){ui.post(()->{if(epoch==session){videoWidth=width;videoHeight=height;layoutTools();remoteView.invalidate();}});}
            public void onFrame(boolean hardware){ui.post(()->{if(running&&epoch==session){videoActive=true;remoteView.clear();remoteView.invalidate();setStatus(hardware?"H.264 硬件解码":"H.264 软件解码");ui.postDelayed(()->{if(running&&epoch==session&&videoDecoder!=null&&android.os.SystemClock.elapsedRealtime()-videoDecoder.lastFrameAt()<2500)recoveryAttempts=0;},3000);}});}
            public void onError(){ui.post(()->{if(recoveryAttempts<2)recoverVideo(epoch,"H264 stream ended");else fallback(epoch);});}
        });
        videoDecoder.start();
    }
    private void fallback(int epoch){
        if(!running||epoch!=session||fallbackStarted)return;
        fallbackStarted=true;videoActive=false;
        if(videoDecoder!=null){videoDecoder.close();videoDecoder=null;}
        videoTexture.setVisibility(View.GONE);remoteView.invalidate();setStatus("MJPEG 兼容模式");
        streamThread=new Thread(this::readStream,"remote-mjpeg");streamThread.start();
    }
    private void stopRemote() {
        if(pendingRecovery!=null){ui.removeCallbacks(pendingRecovery);pendingRecovery=null;}
        ui.removeCallbacks(videoWatchdog);ui.removeCallbacks(updateMetrics);
        running=false;
        session++;
        if(videoDecoder!=null){videoDecoder.close();videoDecoder=null;}
        if(streamConnection!=null)streamConnection.disconnect();
        final InputSocket closing=inputSocket;inputSocket=null;
        final Integer[] keys=heldModifiers.toArray(new Integer[0]);heldModifiers.clear();
        if(ctrlButton!=null)ctrlButton.setText("Ctrl");if(altButton!=null)altButton.setText("Alt");
        if(closing!=null||keys.length>0)inputs.execute(()->{for(int code:keys){try{String value=new JSONObject().put("type","keyboard").put("vkCode",code).put("isKeyDown",false).toString();if(closing==null||!closing.send(value))post("/api/input/keyboard",value);}catch(Exception ignored){}}if(closing!=null)closing.close();});
        if(streamThread!=null)streamThread.interrupt();
    }
    private void reconnect() { recoveryAttempts=0;stopRemote(); startRemote(); }
    private void recoverVideo(int epoch,String reason){
        if(!running||epoch!=session||pendingRecovery!=null)return;
        android.util.Log.i("ChuckieVideo","Recovering video: "+reason);
        setStatus("画面暂时中断，正在恢复…");
        long delay=Math.min(5000,500L*(1L<<Math.min(recoveryAttempts++,4)));
        pendingRecovery=()->{pendingRecovery=null;if(running&&epoch==session){stopRemote();startRemote();}};
        ui.postDelayed(pendingRecovery,delay);
    }
    private void checkVideoHealth(){
        if(!running)return;
        long last=videoActive&&videoDecoder!=null?videoDecoder.lastFrameAt():lastJpegFrameAt;
        if(last>0&&android.os.SystemClock.elapsedRealtime()-last>6500)recoverVideo(session,"frame timeout");
        ui.postDelayed(videoWatchdog,2500);
    }

    private void readStream() {
        final int epoch=session;
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(endpoint+"/api/stream/legacy").openConnection();
            streamConnection=c;c.setConnectTimeout(5000);c.setReadTimeout(5000);
            c.setRequestProperty("Cookie",cookie());c.setRequestProperty("Accept","multipart/x-mixed-replace");
            if(c.getResponseCode()!=200)throw new Exception("画面 HTTP "+c.getResponseCode());
            setStatus("MJPEG 兼容模式");
            try(InputStream in=new BufferedInputStream(c.getInputStream(),32768)) {
                byte[] chunk=new byte[16384];ByteArrayOutputStream frame=new ByteArrayOutputStream(150000);
                boolean jpeg=false;int previous=-1,count;
                while(running&&epoch==session&&(count=in.read(chunk))!=-1){jpegBytes+=count;for(int i=0;i<count;i++) {
                    int b=chunk[i]&255;
                    if(!jpeg){if(previous==0xff&&b==0xd8){jpeg=true;frame.reset();frame.write(0xff);frame.write(0xd8);}previous=b;continue;}
                    frame.write(b);
                    if(previous==0xff&&b==0xd9){
                        byte[] data=frame.toByteArray();Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
                        if(bitmap!=null){if(running&&epoch==session){jpegFrames++;videoWidth=bitmap.getWidth();videoHeight=bitmap.getHeight();lastJpegFrameAt=android.os.SystemClock.elapsedRealtime();remoteView.setFrame(bitmap,epoch);}else bitmap.recycle();}jpeg=false;frame.reset();
                    } else if(frame.size()>10_000_000){jpeg=false;frame.reset();}
                    previous=b;
                }}
            }
            if(running&&epoch==session)throw new java.io.EOFException("MJPEG stream ended");
        }catch(Exception e){if(running&&epoch==session){android.util.Log.w("ChuckieVideo","MJPEG interrupted",e);ui.post(()->recoverVideo(epoch,"MJPEG interrupted"));}}
        finally{if(c!=null)c.disconnect();if(streamConnection==c)streamConnection=null;}
    }
    private String cookie(){String saved=getIntent().getStringExtra("deviceCookie");if(saved!=null)return saved;String value=CookieManager.getInstance().getCookie(endpoint);return value==null?"":value;}
    private void send(JSONObject json) {
        if(!running)return;
        final int epoch=session;
        inputs.execute(() -> transmit(json,epoch));
    }
    private void sendRelative(double dx,double dy){
        if(!running)return;
        final int epoch=session;final RelativeMotionQueue pending=relativeMotion;
        if(!pending.offer(dx,dy))return;
        inputs.execute(()->{
            int[] move;
            while(running&&epoch==session&&(move=pending.take())!=null){
                JSONObject v=event("mouse-relative",0,0);
                try{v.put("dx",move[0]).put("dy",move[1]);}catch(Exception ignored){}
                transmitRelative(v,epoch);
            }
        });
    }
    private void transmitRelative(JSONObject json,int epoch){
        if(epoch!=session||!running)return;
        InputSocket socket=inputSocket;
        if(socket!=null&&socket.supportsAck){
            try{json.put("ack",true);if(socket.send(json.toString())){socket.awaitAck();return;}}
            catch(Exception error){socket.close();if(inputSocket==socket)inputSocket=null;android.util.Log.w("ChuckieInput","Relative input acknowledgement failed",error);return;}
        }
        // Older servers use the confirmed HTTP route to keep at most one move in flight.
        try{post("/api/input/mouse-relative",json.toString());}catch(Exception error){android.util.Log.w("ChuckieInput","Relative input failed",error);}
    }
    private void transmit(JSONObject json,int epoch){
            boolean release="mouse-up".equals(json.optString("type"))||("keyboard".equals(json.optString("type"))&&!json.optBoolean("isKeyDown"));
            if(epoch!=session&&!release)return;
            String value=json.toString();
            String type=json.optString("type");
            boolean confirmed=type.equals("click")||type.equals("pointer-click")||type.equals("text");
            try {if(!confirmed&&inputSocket!=null&&inputSocket.send(value))return;}catch(Exception ignored) {if(inputSocket!=null)inputSocket.close();inputSocket=null;}
            try {post("/api/input/"+type,value);}catch(Exception e) {android.util.Log.w("ChuckieInput","Input action failed: "+type,e);ui.post(()->android.widget.Toast.makeText(this,"输入失败，请重连："+e.getMessage(),android.widget.Toast.LENGTH_SHORT).show());}
    }

    private void post(String path,String json)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint+path).openConnection();
        try {c.setRequestMethod("POST");c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setDoOutput(true);c.setRequestProperty("Cookie",cookie());c.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=c.getOutputStream()){out.write(json.getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("Input HTTP "+code);}
        finally{c.disconnect();}
    }
    private JSONObject event(String type,double x,double y) {JSONObject v=new JSONObject();try{v.put("type",type).put("x",Math.max(0,Math.min(1,x))).put("y",Math.max(0,Math.min(1,y)));}catch(Exception ignored){}return v;}
    private void key(int code) {keyEvent(code,true);ui.postDelayed(()->keyEvent(code,false),65);}
    private void toggleModifier(int code,TextView button,String label){boolean down=!heldModifiers.contains(code);if(down)heldModifiers.add(code);else heldModifiers.remove(code);button.setText(label+(down?" ✓":""));keyEvent(code,down);}
    private void keyEvent(int code,boolean down){JSONObject v=new JSONObject();try{v.put("type","keyboard").put("vkCode",code).put("isKeyDown",down);}catch(Exception ignored){}send(v);}
    private void sendText(String text){if(text.isEmpty())return;JSONObject v=new JSONObject();try{v.put("type","text").put("text",text);}catch(Exception ignored){}send(v);}
    private void openPhoneKeyboard(){remoteView.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(remoteView,InputMethodManager.SHOW_IMPLICIT);}
    private void showKeyboard() {
        EditText edit=new EditText(this);edit.setHint("输入或粘贴文字");edit.setSingleLine(false);
        new AlertDialog.Builder(this).setTitle("发送文字到电脑").setView(edit)
            .setPositiveButton("发送",(dialog,which)->sendText(edit.getText().toString()))
            .setNegativeButton("取消",null).show();
    }

    private final class InputSocket {
        private Socket socket;private OutputStream output;private java.io.DataInputStream input;private boolean supportsAck;
        void open()throws Exception {
            URI uri=URI.create(endpoint);String host=uri.getHost();boolean secure="https".equals(uri.getScheme());int port=uri.getPort()>0?uri.getPort():(secure?443:80);
            Socket plain=new Socket();plain.connect(new InetSocketAddress(host,port),3000);socket=secure?((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain,host,port,true):plain;socket.setSoTimeout(5000);socket.setTcpNoDelay(true);
            if(secure){javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)socket;javax.net.ssl.SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);tls.startHandshake();}
            byte[] nonce=new byte[16];random.nextBytes(nonce);String key=Base64.getEncoder().encodeToString(nonce);
            output=socket.getOutputStream();String request="GET /ws/input HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\nCookie: "+cookie()+"\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));output.flush();
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String line=reader.readLine();if(line==null||!line.contains(" 101 "))throw new Exception("WebSocket rejected: "+line);
            while((line=reader.readLine())!=null&&!line.isEmpty()){if(line.equalsIgnoreCase("X-Chuckie-Input-Ack: 1"))supportsAck=true;}
            input=new java.io.DataInputStream(socket.getInputStream());
            socket.setSoTimeout(0);
            if(supportsAck){long start=android.os.SystemClock.elapsedRealtime();send("{\"type\":\"mouse-relative\",\"dx\":0,\"dy\":0,\"ack\":true}");awaitAck();android.util.Log.i("ChuckieInput","Confirmed input channel ready in "+(android.os.SystemClock.elapsedRealtime()-start)+" ms");}
        }
        synchronized boolean send(String message)throws Exception {
            if(socket==null||socket.isClosed()||output==null)return false;
            byte[] data=message.getBytes(StandardCharsets.UTF_8),mask=new byte[4];random.nextBytes(mask);
            ByteArrayOutputStream frame=new ByteArrayOutputStream(data.length+8);
            frame.write(0x81);
            if(data.length<126)frame.write(0x80|data.length);
            else {frame.write(0x80|126);frame.write((data.length>>8)&255);frame.write(data.length&255);}
            frame.write(mask);
            for(int i=0;i<data.length;i++)frame.write(data[i]^mask[i&3]);
            output.write(frame.toByteArray());
            output.flush();return true;
        }
        synchronized void awaitAck()throws Exception{
            socket.setSoTimeout(2000);
            try{while(true){
                int first=input.readUnsignedByte(),second=input.readUnsignedByte(),opcode=first&15;
                int length=second&127;if(length==126)length=input.readUnsignedShort();else if(length==127)throw new java.io.IOException("Oversized input acknowledgement");
                if(length>2048)throw new java.io.IOException("Oversized input acknowledgement");
                byte[] mask=(second&128)!=0?new byte[4]:null;if(mask!=null)input.readFully(mask);
                byte[] payload=new byte[length];input.readFully(payload);if(mask!=null)for(int i=0;i<length;i++)payload[i]^=mask[i&3];
                if(opcode==8)throw new java.io.EOFException("Input socket closed");
                if(opcode==9){byte[] key=new byte[4];random.nextBytes(key);ByteArrayOutputStream pong=new ByteArrayOutputStream();pong.write(0x8a);pong.write(0x80|length);pong.write(key);for(int i=0;i<length;i++)pong.write(payload[i]^key[i&3]);output.write(pong.toByteArray());output.flush();continue;}
                if(opcode==1&&new JSONObject(new String(payload,StandardCharsets.UTF_8)).optString("type").equals("input-ack"))return;
            }}finally{if(!socket.isClosed())socket.setSoTimeout(0);}
        }
        void close(){try{if(socket!=null)socket.close();}catch(Exception ignored){}}
    }

    private final class RemoteView extends View {
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;
        private float zoom=1,panX=0,panY=0;
        private float startX,startY,lastX,lastY,pinchDistance,pinchZoom,midX,midY;
        private boolean dragging,longPress,twoFinger,panGesture;
        private final TwoFingerGesture twoGesture = new TwoFingerGesture(dp(8),dp(20));
        private double cursorX=.5,cursorY=.5;
        private long lastMove;
        private final Runnable rightHold=()->{if(!dragging&&!twoFinger){longPress=true;if(!trackpad)point(startX,startY);click("right-click");}};
        RemoteView(){super(RemoteActivity.this);setLayerType(View.LAYER_TYPE_HARDWARE,null);setFocusable(true);setFocusableInTouchMode(true);}
        @Override public boolean onCheckIsTextEditor(){return true;}
        @Override public InputConnection onCreateInputConnection(EditorInfo info){
            info.inputType=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            info.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_ACTION_NONE;
            return new BaseInputConnection(this,false){
                @Override public boolean commitText(CharSequence text,int cursor){sendText(text.toString());return true;}
                @Override public boolean setComposingText(CharSequence text,int cursor){return true;}
                @Override public boolean deleteSurroundingText(int before,int after){for(int i=0;i<Math.min(before,64);i++)key(8);for(int i=0;i<Math.min(after,64);i++)key(46);return true;}
                @Override public boolean sendKeyEvent(KeyEvent event){int code=event.getKeyCode();if(code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_ENTER){keyEvent(code==KeyEvent.KEYCODE_DEL?8:13,event.getAction()==KeyEvent.ACTION_DOWN);return true;}if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getUnicodeChar()!=0)sendText(new String(Character.toChars(event.getUnicodeChar())));return true;}
                @Override public boolean performEditorAction(int action){key(13);return true;}
            };
        }
        void setFrame(Bitmap next,int epoch){post(()->{if(!running||epoch!=session){next.recycle();return;}Bitmap old=bitmap;bitmap=next;invalidate();if(old!=null&&!old.isRecycled())old.recycle();});}
        void clear(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}
        void resetZoom(){zoom=1;panX=panY=0;invalidate();}
        private RectF destination(){int width=videoActive?videoWidth:bitmap!=null?bitmap.getWidth():0,height=videoActive?videoHeight:bitmap!=null?bitmap.getHeight():0;if(width==0||height==0)return new RectF();float fit=Math.min(getWidth()/(float)width,getHeight()/(float)height);float w=width*fit*zoom,h=height*fit*zoom;float maxX=Math.max(0,(w-getWidth())/2),maxY=Math.max(0,(h-getHeight())/2);panX=Math.max(-maxX,Math.min(maxX,panX));panY=Math.max(-maxY,Math.min(maxY,panY));float l=(getWidth()-w)/2+panX,t=(getHeight()-h)/2+panY;return new RectF(l,t,l+w,t+h);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(videoActive){RectF r=destination();Matrix matrix=new Matrix();matrix.setScale(r.width()/getWidth(),r.height()/getHeight(),getWidth()/2f,getHeight()/2f);matrix.postTranslate(panX,panY);videoTexture.setTransform(matrix);return;}canvas.drawColor(Color.rgb(5,10,17));if(bitmap!=null&&!bitmap.isRecycled())canvas.drawBitmap(bitmap,null,destination(),paint);else{Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.rgb(154,174,195));p.setTextSize(dp(18));canvas.drawText("正在连接远程画面…",dp(24),getHeight()/2f,p);}}
        private void point(float x,float y){RectF r=destination();if(r.width()>0&&r.height()>0){cursorX=Math.max(0,Math.min(1,(x-r.left)/r.width()));cursorY=Math.max(0,Math.min(1,(y-r.top)/r.height()));}}
        void click(String type){JSONObject v=event(trackpad?"pointer-click":type,cursorX,cursorY);try{v.put("button","right-click".equals(type)?2:0);}catch(Exception ignored){}send(v);}
        void wheel(int delta){JSONObject v=event(trackpad?"pointer-wheel":"mouse-wheel",cursorX,cursorY);try{v.put("delta",delta).put("dy",delta);}catch(Exception ignored){}send(v);}
        private void mouse(String type,int button){JSONObject v=event(type,cursorX,cursorY);try{v.put("button",button);}catch(Exception ignored){}send(v);}
        @Override public boolean onTouchEvent(MotionEvent e){int act=e.getActionMasked();
            if(act==MotionEvent.ACTION_DOWN){
                RectF bounds=destination();toolbarGesture=bounds.width()>0&&!bounds.contains(e.getX(),e.getY());
                if(toolbarGesture){toggleTools();return true;}
                ui.removeCallbacks(hideTools);hideTools.run();
                startX=lastX=e.getX();startY=lastY=e.getY();dragging=longPress=twoFinger=panGesture=false;ui.postDelayed(rightHold,550);return true;}
            if(toolbarGesture){if(act==MotionEvent.ACTION_UP||act==MotionEvent.ACTION_CANCEL)toolbarGesture=false;return true;}
            if(act==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()==2){ui.removeCallbacks(rightHold);if(dragging&&!panGesture&&!trackpad)mouse("mouse-up",0);twoFinger=true;pinchDistance=Math.max(1,distance(e));pinchZoom=zoom;midX=(e.getX(0)+e.getX(1))/2;midY=(e.getY(0)+e.getY(1))/2;twoGesture.start(pinchDistance,midX,midY);return true;}
            if(act==MotionEvent.ACTION_MOVE){
                if(twoFinger&&e.getPointerCount()>=2){float d=distance(e),x=(e.getX(0)+e.getX(1))/2,y=(e.getY(0)+e.getY(1))/2;
                    int delta=twoGesture.update(d,x,y);
                    if(twoGesture.mode==TwoFingerGesture.Mode.PINCH){float before=zoom;zoom=Math.max(1,Math.min(5,pinchZoom*d/pinchDistance));float ratio=zoom/before;panX=(midX-getWidth()/2f)-(midX-getWidth()/2f-panX)*ratio;panY=(midY-getHeight()/2f)-(midY-getHeight()/2f-panY)*ratio;panX+=x-midX;panY+=y-midY;invalidate();}
                    else if(twoGesture.mode==TwoFingerGesture.Mode.SCROLL&&delta!=0){JSONObject v=event("pointer-wheel",0,0);try{v.put("dy",delta);}catch(Exception ignored){}send(v);}
                    else if(twoGesture.mode==TwoFingerGesture.Mode.PAN&&zoom>1){panX+=x-midX;panY+=y-midY;invalidate();}
                    midX=x;midY=y;return true;}
                if(twoFinger)return true;
                float dx=e.getX()-lastX,dy=e.getY()-lastY;lastX=e.getX();lastY=e.getY();
                if((dragging||Math.hypot(e.getX()-startX,e.getY()-startY)>dp(trackpad?2:7))&&!longPress){ui.removeCallbacks(rightHold);
                    if(zoom>1&&!trackpad){dragging=panGesture=true;panX+=dx;panY+=dy;invalidate();return true;}
                    if(!dragging&&trackpad){dx=e.getX()-startX;dy=e.getY()-startY;}
                    if(!dragging&&!trackpad){point(startX,startY);mouse("mouse-down",0);}dragging=true;
                    if(trackpad){float fit=Math.min(getWidth()/(float)videoWidth,getHeight()/(float)videoHeight);sendRelative(dx/fit,dy/fit);}
                    else {point(e.getX(),e.getY());if(android.os.SystemClock.uptimeMillis()-lastMove>25){mouse("mouse-move",0);lastMove=android.os.SystemClock.uptimeMillis();}}
                }return true;}
            if(act==MotionEvent.ACTION_POINTER_UP){ui.removeCallbacks(rightHold);twoFinger=true;return true;}
            if(act==MotionEvent.ACTION_UP||act==MotionEvent.ACTION_CANCEL){ui.removeCallbacks(rightHold);if(!twoFinger){if(dragging&&!panGesture&&!trackpad)mouse("mouse-up",0);else if(!dragging&&!longPress&&act==MotionEvent.ACTION_UP){if(!trackpad)point(e.getX(),e.getY());click("click");}}twoFinger=false;return true;}
            return true;
        }
        private float distance(MotionEvent e){return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));}
    }
}
