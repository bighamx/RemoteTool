package com.chuckiehelper.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class RelativeMotionQueueTest {
    @Test public void burstHasOneSenderAndPreservesDistance(){
        RelativeMotionQueue q=new RelativeMotionQueue();assertTrue(q.offer(1,2));
        for(int i=0;i<1000;i++)assertFalse(q.offer(1,2));
        assertArrayEquals(new int[]{1001,2002},q.take());assertNull(q.take());
        assertTrue(q.offer(3,4));assertArrayEquals(new int[]{3,4},q.take());assertNull(q.take());
    }
    @Test public void fractionalMovementIsRetained(){
        RelativeMotionQueue q=new RelativeMotionQueue();assertFalse(q.offer(.6,-.6));assertTrue(q.offer(.6,-.6));
        assertArrayEquals(new int[]{1,-1},q.take());assertNull(q.take());
    }
}
