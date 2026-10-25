package com.chuckiehelper.mobile;

/** One outstanding sender; retain displacement and fractional pixels without queuing every touch. */
final class RelativeMotionQueue {
    private double x,y;
    private boolean scheduled;
    synchronized boolean offer(double dx,double dy){
        x+=dx;y+=dy;
        if(scheduled||((int)x==0&&(int)y==0))return false;
        scheduled=true;return true;
    }
    synchronized int[] take(){
        int dx=(int)Math.max(-4096,Math.min(4096,x)),dy=(int)Math.max(-4096,Math.min(4096,y));
        if(dx==0&&dy==0){scheduled=false;return null;}
        x-=dx;y-=dy;return new int[]{dx,dy};
    }
}
