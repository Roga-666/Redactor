package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class ZoomPanMathTest {
    @Test fun zoomClampsToOneToSix() {
        assertEquals(1f, ZoomPanMath.nextZoom(1f, .2f), .0001f)
        assertEquals(6f, ZoomPanMath.nextZoom(5f, 2f), .0001f)
        assertEquals(2f, ZoomPanMath.nextZoom(1f, 2f), .0001f)
    }

    @Test fun imageFittingViewCannotPanPastItsEdges() {
        assertEquals(0f, ZoomPanMath.limitPan(100f, 400f, 400f, 1f), .0001f)
        assertEquals(200f, ZoomPanMath.limitPan(500f, 400f, 400f, 2f), .0001f)
        assertEquals(-200f, ZoomPanMath.limitPan(-500f, 400f, 400f, 2f), .0001f)
    }

    @Test fun pinchKeepsContentUnderGestureCentroid() {
        val offset = ZoomPanMath.anchoredPan(0f, 300f, 400f, 0f, 2f)
        assertEquals(-100f, offset, .0001f)
    }

    @Test fun twoFingerScrollTranslatesInEitherDirection() {
        assertEquals(-50f, ZoomPanMath.anchoredPan(0f, 200f, 400f, -50f, 1f), .0001f)
        assertEquals(60f, ZoomPanMath.anchoredPan(0f, 200f, 400f, 60f, 1f), .0001f)
    }
}
