package com.tatav.voiceassist.action

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bridge interface allowing action handlers to access the AccessibilityService's
 * rootInActiveWindow and performGlobalAction without a direct service dependency.
 * VoiceAssistService implements this and registers itself at runtime.
 */
interface AccessibilityBridge {
    fun getRootNode(): AccessibilityNodeInfo?
    /** Returns root nodes from ALL windows (including notification popups). */
    fun getAllRootNodes(): List<AccessibilityNodeInfo>
    fun doGlobalAction(action: Int): Boolean
    fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean
}
