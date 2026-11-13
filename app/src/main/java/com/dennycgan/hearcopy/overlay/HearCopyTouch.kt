package com.dennycgan.hearcopy.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.capture.CaptureCommands
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.PanelPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * hearcopyTouch: the floating capture button, a plain View in the window
 * manager. One instance per session. Main thread only.
 *
 * Position: the user's position is kept in panel coordinates, so the button
 * keeps its place on the glass whatever the screen content does. The stored
 * position (ADR-017) is read once at show() and placed like a configuration
 * change. Only the resting position after a drag (and, without a stored one,
 * the first position) is remembered, and only a drag end saves it; a clamp
 * moves the window but never writes back.
 */
class HearCopyTouch(
    private val context: Context,
    private val state: StateFlow<CaptureState>,
    private val commands: CaptureCommands,
) {

    private class Geometry(val rotation: Int, val naturalWidth: Int, val naturalHeight: Int)

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val sizePx = (SIZE_DP * density).toInt()
    private val gesture = TouchGesture(ViewConfiguration.get(context).scaledTouchSlop)

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (MARGIN_DP * density).toInt()
        y = (TOP_DP * density).toInt()
    }

    private val placement = TouchPlacement { commands.moveTouch(PanelPosition(it.x, it.y)) }

    private val view = TouchView(context, ::onConfigurationChanged).apply {
        setOnClickListener { onTap() }
        setOnTouchListener { touched, event ->
            if (onTouch(event) == GestureEnd.Tap) touched.performClick()
            true
        }
    }

    private var scope: CoroutineScope? = null
    private var dragStart = TouchPosition(params.x, params.y)
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var pendingLayout: ViewTreeObserver.OnGlobalLayoutListener? = null

    /**
     * Idempotent: one View, added only if not added yet. Returns whether the
     * View is attached (false when the window manager refused it).
     */
    fun show(): Boolean {
        if (scope != null) return true
        // Read once: a position restored after this applies from the next session.
        val firstPlacement = if (placement.point != null) {
            null
        } else {
            val restored = state.value.touchPosition
            if (restored != null) {
                placement.restore(PanelPoint(restored.x, restored.y))
                ClampTrigger.Restored
            } else {
                ClampTrigger.Initial
            }
        }
        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "overlay not shown: ${e.javaClass.simpleName}")
            return false
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch {
                // collectLatest also ends the tick loop when the status changes.
                state.map { it.status }.distinctUntilChanged().collectLatest { status ->
                    render(status)
                    if (status is CaptureStatus.Recording) tick(status.startedAtMs)
                }
            }
        }
        // The display, rotation and frame are known only once the window is attached.
        when (firstPlacement) {
            ClampTrigger.Restored -> afterNextLayout { clamp(ClampTrigger.Restored) }
            ClampTrigger.Initial -> afterNextLayout { correctOverflow(ClampTrigger.Initial) }
            else -> Unit
        }
        return true
    }

    fun hide() {
        val current = scope ?: return
        abandonClamp()
        current.cancel()
        scope = null
        view.clearVisual()
        activePointer = MotionEvent.INVALID_POINTER_ID
        // removeView only posts the removal to this looper, so the button
        // stayed up until the rest of the teardown (AudioRecord.stop) returned.
        windowManager.removeViewImmediate(view)
    }

    private fun render(status: CaptureStatus) {
        view.contentDescription = context.getString(descriptionRes(status))
        view.alpha = touchAlpha(status)
        view.setVisual(touchVisual(status))
    }

    /** Recomputed from the clock on every tick, so the timer cannot drift. */
    private suspend fun tick(startedAtMs: Long) {
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            val parts = timerParts(elapsed)
            view.setTimer(context.getString(R.string.touch_timer, parts.minutes, parts.seconds))
            delay(msToNextTick(elapsed))
        }
    }

    private fun onTap() {
        if (rejectsWithHaptic(state.value.status)) {
            view.performHapticFeedback(rejectHapticConstant(Build.VERSION.SDK_INT))
        }
        commands.tap()
    }

    private fun onTouch(event: MotionEvent): GestureEnd {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abandonClamp()
                activePointer = event.getPointerId(0)
                dragStart = TouchPosition(params.x, params.y)
                gesture.down(event.rawX, event.rawY)
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) {
                    gesture.move(event.getRawX(index), event.getRawY(index))?.let {
                        moveTo(applyDrag(dragStart, it))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == activePointer) {
                    return finish(gesture.up(event.getRawX(index), event.getRawY(index)))
                }
            }

            MotionEvent.ACTION_CANCEL -> return finish(gesture.cancel())
        }
        return GestureEnd.None
    }

    private fun finish(end: GestureEnd): GestureEnd {
        activePointer = MotionEvent.INVALID_POINTER_ID
        if (end == GestureEnd.DragEnd) clamp(ClampTrigger.DragEnd)
        return end
    }

    private fun moveTo(position: TouchPosition) {
        params.x = position.x
        params.y = position.y
        if (scope != null) windowManager.updateViewLayout(view, params)
    }

    /** The window may not be laid out for the new configuration yet; clamp after it is. */
    private fun onConfigurationChanged() {
        if (scope == null) return
        Log.d(TAG, "overlay: configuration changed")
        afterNextLayout { clamp(ClampTrigger.Configuration) }
    }

    /**
     * First step: clamp in LayoutParams space, which also brings the window
     * back inside the frame before it is measured. After a drag the start is
     * where the finger left the window; after a configuration change or a
     * restore it is the remembered panel position mapped to the current
     * rotation.
     */
    private fun clamp(trigger: ClampTrigger) {
        val frame = visibleFrame()
        val current = TouchPosition(params.x, params.y)
        val panel = placement.point
        val desired = if (trigger.fromMemory && panel != null) {
            val g = geometry()
            val screen = panelToScreen(panel, g.rotation, g.naturalWidth, g.naturalHeight, sizePx)
            screenToParams(screen, frame, sizePx)
        } else {
            current
        }
        moveTo(clampPosition(desired, frame.width(), frame.height(), sizePx))
        afterNextLayout { correctOverflow(trigger) }
    }

    /**
     * Second step: measure where the window really is and correct once. This
     * makes the clamp independent of how LayoutParams map to the screen. A
     * drag end (or the first layout without a restored position) remembers
     * the final resting position; only a drag end saves it.
     */
    private fun correctOverflow(trigger: ClampTrigger) {
        val frame = visibleFrame()
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val current = TouchPosition(params.x, params.y)
        val corrected = correctedForOverflow(current, location[0], location[1], sizePx, frame)
        val g = geometry()
        // Measured, plus the correction about to be applied.
        val resting = ScreenPoint(location[0] + current.x - corrected.x, location[1] + corrected.y - current.y)
        placement.settle(trigger, screenToPanel(resting, g.rotation, g.naturalWidth, g.naturalHeight, sizePx))
        Log.d(
            TAG,
            "overlay clamp $trigger: rotation=${g.rotation} frame=${frame.left},${frame.top},${frame.right},${frame.bottom} " +
                "params=(${current.x},${current.y}) screen=(${location[0]},${location[1]}) " +
                "corrected=${corrected != current} stored=${placement.point?.let { "(${it.x},${it.y})" }}",
        )
        if (corrected != current) moveTo(corrected)
    }

    private fun visibleFrame(): ScreenFrame {
        val rect = Rect().also { view.getWindowVisibleDisplayFrame(it) }
        return ScreenFrame(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun ScreenFrame.width(): Int = right - left

    private fun ScreenFrame.height(): Int = bottom - top

    /** Rotation and natural panel size, both from the attached window's display. */
    private fun geometry(): Geometry {
        val display = view.display
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val real = Point()
        // The logical size in the current rotation; the coordinates the window uses.
        @Suppress("DEPRECATION")
        display?.getRealSize(real)
        val turned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        return if (turned) Geometry(rotation, real.y, real.x) else Geometry(rotation, real.x, real.y)
    }

    /** The window size never changes, so a layout pass has to be requested. */
    private fun afterNextLayout(action: () -> Unit) {
        abandonClamp()
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                pendingLayout = null
                action()
            }
        }
        pendingLayout = listener
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.requestLayout()
    }

    private fun abandonClamp() {
        pendingLayout?.let { view.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        pendingLayout = null
    }

    /**
     * The disc sits [SHADOW_MARGIN_DP] inside the window so that its shadow is
     * not cut off. Alpha is applied to the whole View, so every part fades
     * together.
     */
    private class TouchView(
        context: Context,
        private val onConfigurationChange: () -> Unit,
    ) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.hearcopy_white)
            setShadowLayer(SHADOW_BLUR_DP * density, 0f, SHADOW_DY_DP * density, context.getColor(R.color.hearcopy_shadow))
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * density
            color = context.getColor(R.color.hearcopy_outline)
        }
        private val square = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.hearcopy_accent)
        }
        private val timer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TIMER_TEXT_DP * density
            color = context.getColor(R.color.hearcopy_accent)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_DP * density
            strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.hearcopy_accent)
        }
        private val ringBounds = RectF()
        private val timerBounds = Rect()
        private var ringStartDegrees = 0f
        private val ringAnimator = ValueAnimator.ofFloat(0f, FULL_TURN_DEGREES).apply {
            duration = RING_TURN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                ringStartDegrees = it.animatedValue as Float
                invalidate()
            }
        }
        private var visual = TouchVisual.Idle
        private var timerText = ""

        fun setVisual(value: TouchVisual) {
            if (value == visual) return
            visual = value
            if (value == TouchVisual.Processing) ringAnimator.start() else ringAnimator.cancel()
            invalidate()
        }

        fun setTimer(value: String) {
            if (value == timerText) return
            timerText = value
            invalidate()
        }

        /** Stops the animation, so that nothing runs once the View is removed. */
        fun clearVisual() {
            ringAnimator.cancel()
            visual = TouchVisual.Idle
        }

        /** A tap reaches this from the touch listener or from accessibility services. */
        override fun performClick(): Boolean = super.performClick()

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            onConfigurationChange()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - SHADOW_MARGIN_DP * density
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, outline)
            when (visual) {
                TouchVisual.Idle -> Unit
                TouchVisual.Recording -> drawRecording(canvas, cx, cy)
                TouchVisual.Processing -> drawRing(canvas, cx, cy, radius)
            }
        }

        /** A column of the square over the timer, centred in the disc. */
        private fun drawRecording(canvas: Canvas, cx: Float, cy: Float) {
            timer.getTextBounds(timerText, 0, timerText.length, timerBounds)
            val side = SQUARE_DP * density
            val gap = TIMER_GAP_DP * density
            val top = cy - (side + gap + timerBounds.height()) / 2f
            val half = side / 2f
            val corner = half * CORNER_RATIO
            canvas.drawRoundRect(cx - half, top, cx + half, top + side, corner, corner, square)
            canvas.drawText(timerText, cx, top + side + gap - timerBounds.top, timer)
        }

        /** Over the outline: the ring's outer edge meets the outline's outer edge. */
        private fun drawRing(canvas: Canvas, cx: Float, cy: Float, discRadius: Float) {
            val radius = discRadius + outline.strokeWidth / 2f - ring.strokeWidth / 2f
            ringBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(ringBounds, ringStartDegrees, RING_SWEEP_DEGREES, false, ring)
        }
    }

    private companion object {
        const val TAG = "HearCopyCapture"
        const val SIZE_DP = 56
        const val MARGIN_DP = 16
        const val TOP_DP = 160
        const val SHADOW_MARGIN_DP = 3f
        const val SHADOW_BLUR_DP = 2f
        const val SHADOW_DY_DP = 0.75f
        const val OUTLINE_DP = 1.5f
        const val SQUARE_DP = 16f
        const val CORNER_RATIO = 0.2f
        const val TIMER_TEXT_DP = 10f
        const val TIMER_GAP_DP = 3f
        const val RING_DP = 2.5f
        const val RING_SWEEP_DEGREES = 90f
        const val FULL_TURN_DEGREES = 360f
        const val RING_TURN_MS = 1_000L
    }
}
