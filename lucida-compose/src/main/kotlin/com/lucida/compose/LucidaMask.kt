package com.lucida.compose

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.lucida.core.MarkerRegion
import com.lucida.core.MarkerRegistry
import com.lucida.core.MarkerRole
import com.lucida.core.MaskRect

/** Semantics property carrying the replay marker (`MASK` or `UNMASK`); visible to accessibility and UI tests. */
val LucidaReplayRole = SemanticsPropertyKey<String>("LucidaReplayRole")
var SemanticsPropertyReceiver.lucidaReplayRole: String by LucidaReplayRole

/** Never show this composable in replay (text starred, screenshots painted over). Wins over [lucidaUnmask]. */
fun Modifier.lucidaMask(): Modifier = marker(MarkerRole.MASK)

/** Show this composable in replay even with the global masks on. A [lucidaMask] inside or around it still wins; passwords stay masked. */
fun Modifier.lucidaUnmask(): Modifier = marker(MarkerRole.UNMASK)

// Compose content is not in the View tree, so the modifier also reports its bounds to MarkerRegistry for the walker.
private fun Modifier.marker(role: MarkerRole): Modifier = composed {
    val owner = remember { Any() }
    val density = LocalDensity.current.density
    val view = LocalView.current
    DisposableEffect(owner) { onDispose { MarkerRegistry.remove(owner) } }
    this.semantics { lucidaReplayRole = role.name }.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        // The registry holds screen coordinates: add this window's origin on screen (non-zero for dialogs and inset windows).
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        val inWindow = IntArray(2).also(view::getLocationInWindow)
        val left = b.left + onScreen[0] - inWindow[0]
        val top = b.top + onScreen[1] - inWindow[1]
        MarkerRegistry.set(
            owner,
            MarkerRegion(role, MaskRect(Math.round(left / density), Math.round(top / density), Math.round(b.width / density), Math.round(b.height / density))),
        )
    }
}
