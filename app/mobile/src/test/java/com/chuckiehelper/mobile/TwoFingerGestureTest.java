package com.chuckiehelper.mobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class TwoFingerGestureTest {
    @Test public void smoothVerticalMotionAccumulatesIntoWheelTicks(){
        TwoFingerGesture g=new TwoFingerGesture(8,20);g.start(100,200,200);
        assertEquals(0,g.update(101,200,194));
        assertEquals(0,g.update(100,200,188));
        assertEquals(-120,g.update(101,200,177));
        assertEquals(TwoFingerGesture.Mode.SCROLL,g.mode);
        assertEquals(120,g.update(100,200,207));
    }
    @Test public void pinchDoesNotSendWheelOrBecomeScroll(){
        TwoFingerGesture g=new TwoFingerGesture(8,20);g.start(100,200,200);
        assertEquals(0,g.update(130,200,202));assertEquals(TwoFingerGesture.Mode.PINCH,g.mode);
        assertEquals(0,g.update(140,200,260));
    }
    @Test public void horizontalPanAndSmallJitterNeverScroll(){
        TwoFingerGesture g=new TwoFingerGesture(8,20);g.start(100,200,200);
        assertEquals(0,g.update(101,203,202));assertEquals(TwoFingerGesture.Mode.UNDECIDED,g.mode);
        assertEquals(0,g.update(100,220,202));assertEquals(TwoFingerGesture.Mode.PAN,g.mode);
    }
}
