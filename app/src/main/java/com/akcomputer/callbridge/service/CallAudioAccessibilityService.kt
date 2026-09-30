package com.akcomputer.callbridge.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper. Does not read screen content; being an enabled accessibility service keeps
 * the process alive and on several OEM builds allows audio capture while a call is active.
 */
class CallAudioAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
