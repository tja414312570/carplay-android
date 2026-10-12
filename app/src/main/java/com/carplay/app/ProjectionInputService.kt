package com.carplay.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.carplay.adapter.generic.ProjectionInput
import com.carplay.app.diagnostics.Diagnostics
import com.carplay.app.diagnostics.Stage

/** Gestures are enabled only by the user's accessibility authorization and an active sender. */
class ProjectionInputService : AccessibilityService() {
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f; private var lastY = 0f
    override fun onServiceConnected() {
        ProjectionInput.touch = { action,x,y,pointer ->
            if (Diagnostics.status.value.stage != Stage.STREAMING || pointer != 0) false else {
                val path = Path().apply { moveTo(if (stroke == null) x else lastX,if (stroke == null) y else lastY); lineTo(x,y) }
                val continuing = action != 1
                val next = stroke?.continueStroke(path,0,32,continuing) ?: GestureDescription.StrokeDescription(path,0,32,continuing)
                val accepted = dispatchGesture(GestureDescription.Builder().addStroke(next).build(),null,null)
                if (accepted) { stroke = if (continuing) next else null; lastX = x; lastY = y }
                accepted
            }
        }
        ProjectionInput.key = { code,down ->
            if (Diagnostics.status.value.stage != Stage.STREAMING) false else if (!down) true else when (code) {
                KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                else -> false
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stroke = null }
    override fun onDestroy() { ProjectionInput.touch = null; ProjectionInput.key = null; stroke = null; super.onDestroy() }
}
