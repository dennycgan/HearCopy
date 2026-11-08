package com.dennycgan.hearcopy.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.capture.CaptureCommands
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * hearcopyTouch: the floating capture button, a plain View in the window
 * manager. Phase 1.3 minimum: fixed position, tap, white or Recording style.
 * Main thread only.
 */
class HearCopyTouch(
    private val context: Context,
    private val state: StateFlow<CaptureState>,
    private val commands: CaptureCommands,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = TouchView(context).apply {
        contentDescription = context.getString(R.string.touch_description)
        setOnClickListener { commands.tap() }
    }
    private var scope: CoroutineScope? = null

    /** Idempotent: one View, added only if not added yet. */
    fun show() {
        if (scope != null) return
        try {
            windowManager.addView(view, layoutParams())
        } catch (e: RuntimeException) {
            Log.w(TAG, "overlay not shown: ${e.javaClass.simpleName}")
            return
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { state.collect { view.setRecording(it.status is CaptureStatus.Recording) } }
        }
    }

    fun hide() {
        val current = scope ?: return
        current.cancel()
        scope = null
        windowManager.removeView(view)
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (MARGIN_DP * density).toInt()
            y = (TOP_DP * density).toInt()
        }
    }

    private class TouchView(context: Context) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.hearcopy_white)
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
        private var recording = false

        fun setRecording(value: Boolean) {
            if (value == recording) return
            recording = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - outline.strokeWidth
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, outline)
            if (recording) {
                val half = radius * SQUARE_RATIO
                val corner = half * CORNER_RATIO
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, square)
            }
        }
    }

    private companion object {
        const val TAG = "HearCopyCapture"
        const val SIZE_DP = 56
        const val MARGIN_DP = 16
        const val TOP_DP = 160
        const val OUTLINE_DP = 1.5f
        const val SQUARE_RATIO = 0.4f
        const val CORNER_RATIO = 0.2f
    }
}
