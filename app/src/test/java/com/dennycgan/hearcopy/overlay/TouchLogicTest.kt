package com.dennycgan.hearcopy.overlay

import android.view.HapticFeedbackConstants
import android.view.Surface
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLogicTest {

    private val allStatuses = listOf(
        CaptureStatus.Ready,
        CaptureStatus.Preparing,
        CaptureStatus.Recording(startedAtMs = 5L),
        CaptureStatus.Processing,
        CaptureStatus.Failed(FailureReason.NoAudio),
    )

    private fun gesture() = TouchGesture(SLOP).apply { down(100f, 100f) }

    @Test
    fun `movement within the slop is a tap`() {
        val g = gesture()
        assertNull(g.move(105f, 100f))
        assertEquals(GestureEnd.Tap, g.up(105f, 100f))
    }

    @Test
    fun `movement of exactly the slop is still a tap`() {
        val g = gesture()
        assertNull(g.move(100f + SLOP, 100f))
        assertEquals(GestureEnd.Tap, g.up(100f + SLOP, 100f))
    }

    @Test
    fun `movement beyond the slop is a drag and reports the full delta`() {
        val g = gesture()
        assertEquals(DragDelta(SLOP + 1, 0), g.move(100f + SLOP + 1, 100f))
        assertEquals(GestureEnd.DragEnd, g.up(100f + SLOP + 1, 100f))
    }

    @Test
    fun `diagonal movement uses the distance, not each axis`() {
        val g = gesture()
        assertNotNull(g.move(107f, 107f))
    }

    @Test
    fun `a drag that returns to its start is never a tap`() {
        val g = gesture()
        assertNotNull(g.move(150f, 100f))
        assertEquals(DragDelta(0, 0), g.move(100f, 100f))
        assertEquals(GestureEnd.DragEnd, g.up(100f, 100f))
    }

    @Test
    fun `a release far away without a move is not a tap`() {
        assertEquals(GestureEnd.DragEnd, gesture().up(300f, 100f))
    }

    @Test
    fun `cancel within the slop does nothing`() {
        assertEquals(GestureEnd.None, gesture().cancel())
    }

    @Test
    fun `cancel during a drag ends the drag without a tap`() {
        val g = gesture()
        g.move(150f, 100f)
        assertEquals(GestureEnd.DragEnd, g.cancel())
    }

    @Test
    fun `events after the gesture ended are ignored`() {
        val g = gesture()
        g.up(100f, 100f)
        assertNull(g.move(300f, 300f))
        assertEquals(GestureEnd.None, g.up(100f, 100f))
        assertEquals(GestureEnd.None, g.cancel())
    }

    @Test
    fun `a new gesture does not inherit an earlier drag`() {
        val g = gesture()
        g.move(150f, 100f)
        g.up(150f, 100f)
        g.down(100f, 100f)
        assertEquals(GestureEnd.Tap, g.up(100f, 100f))
    }

    @Test
    fun `dragging right reduces the inset and dragging down increases y`() {
        val moved = applyDrag(TouchPosition(100, 200), DragDelta(30, 40))
        assertEquals(TouchPosition(70, 240), moved)
    }

    @Test
    fun `a position inside the frame is unchanged`() {
        val position = TouchPosition(100, 200)
        assertEquals(position, clampPosition(position, FRAME_W, FRAME_H, SIZE))
    }

    @Test
    fun `clamp holds every edge and the corners`() {
        val maxX = FRAME_W - SIZE
        val maxY = FRAME_H - SIZE
        assertEquals(TouchPosition(0, 200), clampPosition(TouchPosition(-5, 200), FRAME_W, FRAME_H, SIZE))
        assertEquals(TouchPosition(maxX, 200), clampPosition(TouchPosition(maxX + 5, 200), FRAME_W, FRAME_H, SIZE))
        assertEquals(TouchPosition(100, 0), clampPosition(TouchPosition(100, -5), FRAME_W, FRAME_H, SIZE))
        assertEquals(TouchPosition(100, maxY), clampPosition(TouchPosition(100, maxY + 5), FRAME_W, FRAME_H, SIZE))
        assertEquals(TouchPosition(0, 0), clampPosition(TouchPosition(-5, -5), FRAME_W, FRAME_H, SIZE))
        assertEquals(TouchPosition(maxX, maxY), clampPosition(TouchPosition(9999, 9999), FRAME_W, FRAME_H, SIZE))
    }

    @Test
    fun `a frame smaller than the button clamps to zero`() {
        assertEquals(TouchPosition(0, 0), clampPosition(TouchPosition(50, 50), SIZE - 1, SIZE - 1, SIZE))
    }

    @Test
    fun `clamping never changes the input, so a later clamp in a larger frame restores it`() {
        val preferred = TouchPosition(100, 2000)
        val landscape = clampPosition(preferred, FRAME_H, FRAME_W, SIZE)
        assertEquals(FRAME_W - SIZE, landscape.y)
        assertEquals(preferred, clampPosition(preferred, FRAME_W, FRAME_H, SIZE))
    }

    @Test
    fun `a window inside the frame is not corrected`() {
        val position = TouchPosition(10, 20)
        assertEquals(position, correctedForOverflow(position, 500, 500, SIZE, FRAME))
    }

    @Test
    fun `a window touching the frame edges is not corrected`() {
        val position = TouchPosition(10, 20)
        assertEquals(position, correctedForOverflow(position, FRAME.left, FRAME.top, SIZE, FRAME))
        assertEquals(position, correctedForOverflow(position, FRAME.right - SIZE, FRAME.bottom - SIZE, SIZE, FRAME))
    }

    @Test
    fun `overflow on the left moves the window right, which reduces the inset`() {
        val corrected = correctedForOverflow(TouchPosition(100, 20), FRAME.left - 7, 500, SIZE, FRAME)
        assertEquals(TouchPosition(93, 20), corrected)
    }

    @Test
    fun `overflow on the right moves the window left, which increases the inset`() {
        val corrected = correctedForOverflow(TouchPosition(100, 20), FRAME.right - SIZE + 7, 500, SIZE, FRAME)
        assertEquals(TouchPosition(107, 20), corrected)
    }

    @Test
    fun `overflow above the frame moves the window down`() {
        val corrected = correctedForOverflow(TouchPosition(100, 20), 500, FRAME.top - 66, SIZE, FRAME)
        assertEquals(TouchPosition(100, 86), corrected)
    }

    @Test
    fun `overflow below the frame moves the window up`() {
        val corrected = correctedForOverflow(TouchPosition(100, 2000), 500, FRAME.bottom - SIZE + 12, SIZE, FRAME)
        assertEquals(TouchPosition(100, 1988), corrected)
    }

    @Test
    fun `correction is idempotent once the window is inside`() {
        val corrected = correctedForOverflow(TouchPosition(100, 20), 500, FRAME.top - 66, SIZE, FRAME)
        assertEquals(corrected, correctedForOverflow(corrected, 500, FRAME.top, SIZE, FRAME))
    }

    @Test
    fun `timer parts`() {
        assertEquals(TimerParts(0, 0), timerParts(0))
        assertEquals(TimerParts(0, 0), timerParts(999))
        assertEquals(TimerParts(0, 1), timerParts(1_000))
        assertEquals(TimerParts(0, 59), timerParts(59_999))
        assertEquals(TimerParts(1, 0), timerParts(60_000))
        assertEquals(TimerParts(5, 0), timerParts(300_000))
    }

    @Test
    fun `a negative elapsed time shows zero`() {
        assertEquals(TimerParts(0, 0), timerParts(-1))
    }

    @Test
    fun `the next tick lands on a whole second`() {
        assertEquals(1_000L, msToNextTick(0))
        assertEquals(999L, msToNextTick(1))
        assertEquals(1L, msToNextTick(999))
        assertEquals(1_000L, msToNextTick(1_000))
        assertEquals(1_000L, msToNextTick(-1))
    }

    @Test
    fun `visual per status`() {
        assertEquals(
            listOf(
                TouchVisual.Idle,
                TouchVisual.Idle,
                TouchVisual.Recording,
                TouchVisual.Processing,
                TouchVisual.Idle,
            ),
            allStatuses.map(::touchVisual),
        )
    }

    @Test
    fun `content description per status`() {
        assertEquals(
            listOf(
                R.string.touch_description_ready,
                R.string.touch_description_preparing,
                R.string.touch_description_recording,
                R.string.touch_description_processing,
                R.string.touch_description_failed,
            ),
            allStatuses.map(::descriptionRes),
        )
    }

    @Test
    fun `only Processing rejects with a haptic`() {
        assertTrue(rejectsWithHaptic(CaptureStatus.Processing))
        allStatuses.filterNot { it == CaptureStatus.Processing }.forEach {
            assertFalse("$it must not vibrate", rejectsWithHaptic(it))
        }
    }

    @Test
    fun `Completed looks like Ready, accepts a tap and has its own description`() {
        assertEquals(TouchVisual.Idle, touchVisual(CaptureStatus.Completed))
        assertEquals(ALPHA_IDLE, touchAlpha(CaptureStatus.Completed), 0f)
        assertFalse(rejectsWithHaptic(CaptureStatus.Completed))
        assertEquals(R.string.touch_description_completed, descriptionRes(CaptureStatus.Completed))
    }

    @Test
    fun `reject haptic constant by API level`() {
        assertEquals(HapticFeedbackConstants.LONG_PRESS, rejectHapticConstant(29))
        assertEquals(HapticFeedbackConstants.REJECT, rejectHapticConstant(30))
        assertEquals(HapticFeedbackConstants.REJECT, rejectHapticConstant(37))
    }

    @Test
    fun `panel and screen round trip for all four rotations`() {
        val points = listOf(PanelPoint(0, 0), PanelPoint(842, 2081), PanelPoint(PANEL_W - SIZE, PANEL_H - SIZE), PanelPoint(17, 1234))
        rotations.forEach { rotation ->
            points.forEach { p ->
                val screen = panelToScreen(p, rotation, PANEL_W, PANEL_H, SIZE)
                assertEquals("rotation $rotation", p, screenToPanel(screen, rotation, PANEL_W, PANEL_H, SIZE))
            }
        }
    }

    @Test
    fun `screen and panel round trip for all four rotations`() {
        rotations.forEach { rotation ->
            val screen = ScreenPoint(300, 500)
            val panel = screenToPanel(screen, rotation, PANEL_W, PANEL_H, SIZE)
            assertEquals("rotation $rotation", screen, panelToScreen(panel, rotation, PANEL_W, PANEL_H, SIZE))
        }
    }

    @Test
    fun `natural orientation is the identity`() {
        assertEquals(ScreenPoint(842, 2081), panelToScreen(PanelPoint(842, 2081), Surface.ROTATION_0, PANEL_W, PANEL_H, SIZE))
    }

    @Test
    fun `logged portrait position keeps its place on the glass in landscape`() {
        // Device-A: portrait screen (842,2081), button 168 px; ROTATION_90 puts the portrait top on the left.
        val landscape = panelToScreen(PanelPoint(842, 2081), Surface.ROTATION_90, PANEL_W, PANEL_H, SIZE)
        assertEquals(ScreenPoint(2081, 70), landscape)
        val frame = ScreenFrame(120, 0, 2460, 1080)
        assertEquals(TouchPosition(211, 70), screenToParams(landscape, frame, SIZE))
    }

    @Test
    fun `panel corners map to the matching corners in every rotation`() {
        val topLeft = PanelPoint(0, 0)
        val bottomRight = PanelPoint(PANEL_W - SIZE, PANEL_H - SIZE)
        assertEquals(ScreenPoint(0, PANEL_W - SIZE), panelToScreen(topLeft, Surface.ROTATION_90, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(PANEL_W - SIZE, PANEL_H - SIZE), panelToScreen(topLeft, Surface.ROTATION_180, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(PANEL_H - SIZE, 0), panelToScreen(topLeft, Surface.ROTATION_270, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(PANEL_H - SIZE, 0), panelToScreen(bottomRight, Surface.ROTATION_90, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(0, 0), panelToScreen(bottomRight, Surface.ROTATION_180, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(0, PANEL_W - SIZE), panelToScreen(bottomRight, Surface.ROTATION_270, PANEL_W, PANEL_H, SIZE))
    }

    @Test
    fun `a point on the panel top edge lands on the matching screen edge`() {
        val topEdge = PanelPoint(456, 0)
        assertEquals(ScreenPoint(0, 456), panelToScreen(topEdge, Surface.ROTATION_90, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(PANEL_H - SIZE, 456), panelToScreen(topEdge, Surface.ROTATION_270, PANEL_W, PANEL_H, SIZE))
        assertEquals(ScreenPoint(456, PANEL_H - SIZE), panelToScreen(topEdge, Surface.ROTATION_180, PANEL_W, PANEL_H, SIZE))
    }

    @Test
    fun `a landscape drag maps back to portrait`() {
        val restedInLandscape = ScreenPoint(1000, 300)
        val panel = screenToPanel(restedInLandscape, Surface.ROTATION_90, PANEL_W, PANEL_H, SIZE)
        assertEquals(PanelPoint(612, 1000), panel)
        assertEquals(ScreenPoint(612, 1000), panelToScreen(panel, Surface.ROTATION_0, PANEL_W, PANEL_H, SIZE))
    }

    @Test
    fun `screen to params matches the logged frames`() {
        assertEquals(TouchPosition(70, 912), screenToParams(ScreenPoint(2222, 912), ScreenFrame(120, 0, 2460, 1080), SIZE))
        assertEquals(TouchPosition(912, 2016), screenToParams(ScreenPoint(0, 2148), ScreenFrame(0, 132, 1080, 2316), SIZE))
    }

    @Test
    fun `alpha per status`() {
        assertEquals(
            listOf(ALPHA_IDLE, ALPHA_IDLE, ALPHA_ACTIVE, ALPHA_ACTIVE, ALPHA_IDLE),
            allStatuses.map(::touchAlpha),
        )
        assertEquals(0.70f, ALPHA_IDLE, 0f)
        assertEquals(0.85f, ALPHA_ACTIVE, 0f)
    }

    @Test
    fun `no status is fully opaque or above the maximum`() {
        allStatuses.forEach {
            val alpha = touchAlpha(it)
            assertTrue("$it alpha $alpha", alpha <= 0.85f)
            assertTrue("$it alpha $alpha", alpha < 1f)
        }
    }

    @Test
    fun `Preparing looks like Ready`() {
        assertEquals(touchAlpha(CaptureStatus.Ready), touchAlpha(CaptureStatus.Preparing), 0f)
        assertEquals(touchVisual(CaptureStatus.Ready), touchVisual(CaptureStatus.Preparing))
    }

    private val rotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)

    private companion object {
        const val PANEL_W = 1080
        const val PANEL_H = 2460
        const val SLOP = 8
        // Device-A: 56 dp at density 3 (912 + 168 = 1080 in the landscape log).
        const val SIZE = 168
        const val FRAME_W = 1080
        const val FRAME_H = 2300
        val FRAME = ScreenFrame(0, 66, 1080, 2366)
    }
}
