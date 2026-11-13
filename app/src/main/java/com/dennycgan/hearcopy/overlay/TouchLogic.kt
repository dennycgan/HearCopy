package com.dennycgan.hearcopy.overlay

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.Surface
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.TapAction
import com.dennycgan.hearcopy.state.tapAction

/**
 * hearcopyTouch decisions that need no View: gesture classification, window
 * position, timer values and what each capture status looks like.
 *
 * Three coordinate spaces, all for the button's top-left corner in pixels:
 * - [PanelPoint]: the panel in its natural (portrait) orientation. This is
 *   where the user put the button; it does not change when the screen
 *   content rotates.
 * - [ScreenPoint]: the screen in the current orientation.
 * - [TouchPosition]: WindowManager LayoutParams with gravity TOP|END, that is
 *   the inset from the visible frame's right edge and the offset from its top.
 */

/** LayoutParams position: [x] is the inset from the right edge, [y] from the top. */
internal data class TouchPosition(val x: Int, val y: Int)

internal data class PanelPoint(val x: Int, val y: Int)

internal data class ScreenPoint(val x: Int, val y: Int)

/** Finger movement since the gesture began; positive is right and down. */
internal data class DragDelta(val dx: Int, val dy: Int)

/** The visible area of the screen, in screen coordinates. */
internal data class ScreenFrame(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class TimerParts(val minutes: Int, val seconds: Int)

internal enum class GestureEnd { None, Tap, DragEnd }

internal enum class TouchVisual { Idle, Recording, Processing }

/**
 * Separates a tap from a drag. Once the finger has moved beyond the slop the
 * gesture stays a drag, even if the finger returns to where it started.
 */
internal class TouchGesture(slopPx: Int) {

    private val slopSquared = slopPx.toFloat() * slopPx
    private var active = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    fun down(rawX: Float, rawY: Float) {
        active = true
        dragging = false
        downX = rawX
        downY = rawY
    }

    /** The movement to apply, or null while the finger is still within the slop. */
    fun move(rawX: Float, rawY: Float): DragDelta? {
        if (!active) return null
        if (!dragging && beyondSlop(rawX, rawY)) dragging = true
        return if (dragging) DragDelta((rawX - downX).toInt(), (rawY - downY).toInt()) else null
    }

    fun up(rawX: Float, rawY: Float): GestureEnd {
        if (!active) return GestureEnd.None
        val dragged = dragging || beyondSlop(rawX, rawY)
        active = false
        dragging = false
        return if (dragged) GestureEnd.DragEnd else GestureEnd.Tap
    }

    fun cancel(): GestureEnd {
        if (!active) return GestureEnd.None
        val dragged = dragging
        active = false
        dragging = false
        return if (dragged) GestureEnd.DragEnd else GestureEnd.None
    }

    private fun beyondSlop(rawX: Float, rawY: Float): Boolean {
        val dx = rawX - downX
        val dy = rawY - downY
        return dx * dx + dy * dy > slopSquared
    }
}

/**
 * Why the button is being placed. [fromMemory]: the target is the remembered
 * panel point mapped to the current rotation, not where the window is now.
 * [storesPosition]: the measured resting point becomes the remembered one.
 * [savesPosition]: it is also saved to storage (ADR-017). Only a drag end
 * saves; a clamp never writes back (ADR-006 split rule).
 */
internal enum class ClampTrigger(val fromMemory: Boolean, val storesPosition: Boolean, val savesPosition: Boolean) {
    /** First layout with no restored position: the default becomes the remembered point. */
    Initial(fromMemory = false, storesPosition = true, savesPosition = false),

    /** First layout with a restored position: placed like a configuration change. */
    Restored(fromMemory = true, storesPosition = false, savesPosition = false),

    DragEnd(fromMemory = false, storesPosition = true, savesPosition = true),

    Configuration(fromMemory = true, storesPosition = false, savesPosition = false),
}

/**
 * The remembered panel point of one hearcopyTouch instance, and the only
 * place that decides, per [ClampTrigger], whether it changes and whether
 * [save] runs.
 */
internal class TouchPlacement(private val save: (PanelPoint) -> Unit) {

    /** Where the user put the button, on the panel; null until restored or first measured. */
    var point: PanelPoint? = null
        private set

    /** The stored position, read once at show(); never saved again. */
    fun restore(stored: PanelPoint) {
        if (point == null) point = stored
    }

    /** The button has come to rest at [resting] after [trigger]. */
    fun settle(trigger: ClampTrigger, resting: PanelPoint) {
        if (!trigger.storesPosition) return
        point = resting
        if (trigger.savesPosition) save(resting)
    }
}

/** Dragging right reduces the inset from the right edge. */
internal fun applyDrag(start: TouchPosition, delta: DragDelta): TouchPosition =
    TouchPosition(start.x - delta.dx, start.y + delta.dy)

/** Keeps a window of [sizePx] inside a frame of the given size, in LayoutParams space. */
internal fun clampPosition(position: TouchPosition, frameWidth: Int, frameHeight: Int, sizePx: Int): TouchPosition =
    TouchPosition(
        position.x.coerceIn(0, (frameWidth - sizePx).coerceAtLeast(0)),
        position.y.coerceIn(0, (frameHeight - sizePx).coerceAtLeast(0)),
    )

/**
 * The position that moves a window measured at ([left], [top]) back inside
 * [frame]. Unchanged when the window is already inside, so applying it again
 * never drifts.
 */
internal fun correctedForOverflow(
    position: TouchPosition,
    left: Int,
    top: Int,
    sizePx: Int,
    frame: ScreenFrame,
): TouchPosition {
    val moveRight = overflow(left, sizePx, frame.left, frame.right)
    val moveDown = overflow(top, sizePx, frame.top, frame.bottom)
    return TouchPosition(position.x - moveRight, position.y + moveDown)
}

private fun overflow(start: Int, size: Int, min: Int, max: Int): Int = when {
    start < min -> min - start
    start + size > max -> max - (start + size)
    else -> 0
}

/**
 * Panel to screen for a display [rotation] (`Surface.ROTATION_*`). The panel
 * is [naturalWidth] x [naturalHeight] in its natural orientation.
 */
internal fun panelToScreen(
    point: PanelPoint,
    rotation: Int,
    naturalWidth: Int,
    naturalHeight: Int,
    sizePx: Int,
): ScreenPoint = when (rotation) {
    Surface.ROTATION_90 -> ScreenPoint(point.y, naturalWidth - point.x - sizePx)
    Surface.ROTATION_180 -> ScreenPoint(naturalWidth - point.x - sizePx, naturalHeight - point.y - sizePx)
    Surface.ROTATION_270 -> ScreenPoint(naturalHeight - point.y - sizePx, point.x)
    else -> ScreenPoint(point.x, point.y)
}

/** The inverse of [panelToScreen]. */
internal fun screenToPanel(
    point: ScreenPoint,
    rotation: Int,
    naturalWidth: Int,
    naturalHeight: Int,
    sizePx: Int,
): PanelPoint = when (rotation) {
    Surface.ROTATION_90 -> PanelPoint(naturalWidth - point.y - sizePx, point.x)
    Surface.ROTATION_180 -> PanelPoint(naturalWidth - point.x - sizePx, naturalHeight - point.y - sizePx)
    Surface.ROTATION_270 -> PanelPoint(point.y, naturalHeight - point.x - sizePx)
    else -> PanelPoint(point.x, point.y)
}

/** Screen to LayoutParams with gravity TOP|END inside [frame]. */
internal fun screenToParams(point: ScreenPoint, frame: ScreenFrame, sizePx: Int): TouchPosition =
    TouchPosition(frame.right - sizePx - point.x, point.y - frame.top)

internal fun timerParts(elapsedMs: Long): TimerParts {
    val seconds = elapsedMs.coerceAtLeast(0) / MS_PER_SECOND
    return TimerParts((seconds / SECONDS_PER_MINUTE).toInt(), (seconds % SECONDS_PER_MINUTE).toInt())
}

/** The delay that lands the next tick on a whole second of the timer. */
internal fun msToNextTick(elapsedMs: Long): Long =
    MS_PER_SECOND - elapsedMs.coerceAtLeast(0) % MS_PER_SECOND

internal fun touchVisual(status: CaptureStatus): TouchVisual = when (status) {
    is CaptureStatus.Recording -> TouchVisual.Recording
    CaptureStatus.Processing -> TouchVisual.Processing
    CaptureStatus.Ready, CaptureStatus.Preparing, CaptureStatus.Completed, is CaptureStatus.Failed -> TouchVisual.Idle
}

/** Button opacity, tuned here only. No status is fully opaque: content always shows through. */
internal const val ALPHA_IDLE = 0.70f
internal const val ALPHA_ACTIVE = 0.85f

/** Preparing looks like Ready: nothing changes before the first audio arrives. */
internal fun touchAlpha(status: CaptureStatus): Float = when (status) {
    is CaptureStatus.Recording, CaptureStatus.Processing -> ALPHA_ACTIVE
    CaptureStatus.Ready, CaptureStatus.Preparing, CaptureStatus.Completed, is CaptureStatus.Failed -> ALPHA_IDLE
}

internal fun descriptionRes(status: CaptureStatus): Int = when (status) {
    CaptureStatus.Ready -> R.string.touch_description_ready
    CaptureStatus.Preparing -> R.string.touch_description_preparing
    is CaptureStatus.Recording -> R.string.touch_description_recording
    CaptureStatus.Processing -> R.string.touch_description_processing
    CaptureStatus.Completed -> R.string.touch_description_completed
    is CaptureStatus.Failed -> R.string.touch_description_failed
}

internal fun rejectsWithHaptic(status: CaptureStatus): Boolean = tapAction(status) == TapAction.Reject

// REJECT is used only when sdk is at least 30; lint cannot follow a parameter.
@SuppressLint("InlinedApi")
internal fun rejectHapticConstant(sdk: Int): Int =
    if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
