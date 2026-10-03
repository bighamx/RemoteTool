package com.chuckiehelper.mobile;

/** Locks each two-finger gesture to scroll, pinch or pan after a small movement threshold. */
public final class TwoFingerGesture {
    public enum Mode { UNDECIDED, SCROLL, PINCH, PAN }
    public Mode mode = Mode.UNDECIDED;
    private final float threshold, step;
    private float startDistance, startX, startY, lastY, accumulated;
    public TwoFingerGesture(float threshold, float step) { this.threshold=threshold;this.step=step; }
    public void start(float distance,float x,float y) { mode=Mode.UNDECIDED;startDistance=distance;startX=x;startY=lastY=y;accumulated=0; }
    public int update(float distance,float x,float y) {
        float dx=Math.abs(x-startX),dy=Math.abs(y-startY),spread=Math.abs(distance-startDistance);
        if(mode==Mode.UNDECIDED) {
            if(spread>=threshold&&spread>=Math.max(dx,dy)*.75f)mode=Mode.PINCH;
            else if(dy>=threshold&&dy>=dx*1.25f&&spread<threshold*1.5f){mode=Mode.SCROLL;accumulated=y-startY;}
            else if(dx>=threshold)mode=Mode.PAN;
        } else if(mode==Mode.SCROLL) accumulated+=y-lastY;
        lastY=y;
        if(mode!=Mode.SCROLL)return 0;
        int ticks=Math.max(-6,Math.min(6,(int)(accumulated/step)));
        accumulated-=ticks*step;
        return ticks*120;
    }
}
