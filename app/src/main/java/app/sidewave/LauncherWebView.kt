package app.sidewave

import android.content.Context
import android.annotation.SuppressLint
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView

class LauncherWebView(context: Context) : WebView(context) {
    // WebView's handler retains its own click and accessibility dispatch.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val unknownPointer = (0 until event.pointerCount).any {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_UNKNOWN
        }
        if ((BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) && event.actionMasked != MotionEvent.ACTION_MOVE) {
            Log.d("Dashphonic", "Touch action=${event.actionMasked} source=${event.source} unknown=$unknownPointer age=${android.os.SystemClock.uptimeMillis() - event.eventTime}")
        }
        if (!unknownPointer) return super.onTouchEvent(event)

        // VD launchers can omit the tool type; give Chromium consistent touchscreen events.
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
}
