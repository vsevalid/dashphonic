package app.sidewave

import android.content.Context
import android.annotation.SuppressLint
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView

class LauncherWebView(context: Context) : WebView(context) {
    // VD-лаунчер принимает палец своим окном на дисплее 0 и только потом переносит касание на наш дисплей:
    // фокус окна на несколько миллисекунд уходит к лаунчеру и возвращается. Chromium на любую смену фокуса
    // окна отменяет текущее касание (touchcancel), и кнопка не срабатывает. Поэтому короткую потерю фокуса
    // не передаём, а смену фокуса во время касания откладываем до отпускания пальца.
    private var touchActive = false
    private var forwardedFocus: Boolean? = null
    private var pendingFocus: Boolean? = null
    private val flushFocus = Runnable { if (!touchActive) deliverPendingFocus() }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if ((BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS)) {
            Log.d("Dashphonic", "Window focus=$hasWindowFocus touch=$touchActive forwarded=$forwardedFocus")
        }
        removeCallbacks(flushFocus)
        if (hasWindowFocus == forwardedFocus) {
            pendingFocus = null
            return
        }
        pendingFocus = hasWindowFocus
        scheduleFocus()
    }

    private fun scheduleFocus() {
        val focus = pendingFocus ?: return
        if (touchActive) return
        if (focus) deliverPendingFocus() else postDelayed(flushFocus, FOCUS_LOSS_DEBOUNCE_MS)
    }

    private fun deliverPendingFocus() {
        val focus = pendingFocus ?: return
        pendingFocus = null
        forwardedFocus = focus
        super.onWindowFocusChanged(focus)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(flushFocus)
        pendingFocus = null
        forwardedFocus = null
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                post { scheduleFocus() }
            }
        }
        val unknownPointer = (0 until event.pointerCount).any {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_UNKNOWN
        }
        if ((BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) && event.actionMasked != MotionEvent.ACTION_MOVE) {
            Log.d("Dashphonic", "Touch action=${event.actionMasked} source=${event.source} unknown=$unknownPointer age=${android.os.SystemClock.uptimeMillis() - event.eventTime}")
        }
        if (!unknownPointer) return super.onTouchEvent(event)

        val properties = Array(event.pointerCount) { index ->
            MotionEvent.PointerProperties().also {
                event.getPointerProperties(index, it)
                if (it.toolType == MotionEvent.TOOL_TYPE_UNKNOWN) it.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(event.pointerCount) { index ->
            MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) }
        }
        val touch = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, event.pointerCount,
            properties, coordinates, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN, event.flags
        )
        return try {
            super.onTouchEvent(touch)
        } finally {
            touch.recycle()
        }
    }

    private companion object {
        // лаунчер возвращает фокус за 5–10 мс; запас на загруженную систему
        const val FOCUS_LOSS_DEBOUNCE_MS = 300L
    }
}
