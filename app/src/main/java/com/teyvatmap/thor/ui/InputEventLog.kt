package com.teyvatmap.thor.ui

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records KeyEvents / MotionEvents this window receives (Phase 0.1): with FLAG_NOT_FOCUSABLE set, controller input
 * should *not* show up here (it stays with the game).
 */
object InputEventLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _events = MutableStateFlow<List<String>>(emptyList())
    private val _windowFocus = MutableStateFlow(false)
    private var lastAxisLog = 0L

    val events: StateFlow<List<String>> = _events.asStateFlow()
    val windowFocus: StateFlow<Boolean> = _windowFocus.asStateFlow()

    fun setWindowFocus(focus: Boolean) {
        _windowFocus.value = focus
        add("window focus = $focus")
    }

    fun clear() { _events.value = emptyList() }

    fun key(e: KeyEvent) {
        val action = if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else if (e.action == KeyEvent.ACTION_UP) "UP" else "MULTI"
        add("KEY $action ${KeyEvent.keyCodeToString(e.keyCode)} src=${sourceName(e.source)} dev=${e.deviceId}")
    }

    fun motion(e: MotionEvent) {
        val joystick = e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (joystick) {
            val now = System.currentTimeMillis()
            if (now - lastAxisLog < 250) return
            lastAxisLog = now
            add(
                "AXIS LS(%.2f,%.2f) RS(%.2f,%.2f) LT=%.2f RT=%.2f HAT(%.0f,%.0f) dev=%d".format(
                    e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                    e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                    e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), e.deviceId,
                ),
            )
        } else if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            add("SCROLL src=${sourceName(e.source)}")
        }
    }

    fun touch(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) add("TOUCH down (%.0f, %.0f)".format(e.x, e.y))
    }

    private fun add(line: String) {
        val entry = "${fmt.format(Date())}  $line"
        _events.update { (listOf(entry) + it).take(200) }
    }

    private fun sourceName(src: Int): String = buildList {
        if (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("gamepad")
        if (src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("joystick")
        if (src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) add("dpad")
        if (src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) add("keyboard")
        if (src and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN) add("touch")
        if (src and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) add("mouse")
        if (isEmpty()) add("0x" + src.toString(16))
    }.joinToString("|")
}
