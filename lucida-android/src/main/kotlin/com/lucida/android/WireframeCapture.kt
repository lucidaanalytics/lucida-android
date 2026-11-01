package com.lucida.android

import android.app.Activity
import com.lucida.core.ReplayBridge

/**
 * One-shot capture of an Activity's view tree for hosts that drive replay themselves (React Native).
 * Must run on the main thread. Returns `{"width","height","events"}` (see [ReplayBridge]).
 */
object WireframeCapture {
    fun capture(
        activity: Activity,
        screen: String,
        maskText: Boolean,
        maskAllTextInputs: Boolean = true,
        maskAllImages: Boolean = true,
        maskSandboxedViews: Boolean = true,
    ): String {
        val decor = activity.window.decorView
        val d = activity.resources.displayMetrics.density
        val tree = ViewHierarchy(ReplayConfig(maskAllText = maskText, maskAllTextInputs = maskAllTextInputs, maskAllImages = maskAllImages, maskSandboxedViews = maskSandboxedViews), d).capture(decor)
        return ReplayBridge.capture(tree, screen, Math.round(decor.width / d), Math.round(decor.height / d), System.currentTimeMillis())
    }
}
