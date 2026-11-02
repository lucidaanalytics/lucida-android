package com.lucida.android

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lucida.core.MarkerRegion
import com.lucida.core.MarkerRole
import com.lucida.core.MaskRect
import com.lucida.core.ScreenshotMaskPlan
import com.lucida.core.ScreenshotMasks
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAdaptersTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun text(s: String, tag: String? = null) = TextView(ctx).apply { text = s; this.tag = tag }

    @Test fun viewMarkersInheritAndMaskBeatsUnmask() {
        val unmasked = LinearLayout(ctx).apply { tag = LcTag.UNMASK; addView(text("free")); addView(text("hidden", LcTag.MASK)) }
        val masked = LinearLayout(ctx).apply { tag = LcTag.MASK; addView(text("inner", LcTag.UNMASK)) }
        val pw = EditText(ctx).apply { tag = LcTag.UNMASK; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText("hunter2") }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(unmasked); addView(masked); addView(pw); addView(text("default")); addView(text("gone", LcTag.IGNORE))
        }
        layout(root, 400, 800)
        val tree = ViewHierarchy(ReplayConfig(), 1f).capture(root)
        assertEquals(4, tree.children.size, "lc-ignore is omitted")
        val (u, m, p, d) = tree.children
        assertFalse(u.children[0].maskText, "unmask inherits into the subtree")
        assertTrue(u.children[1].maskText, "mask inside unmask wins")
        assertTrue(m.children[0].maskText, "unmask inside mask loses")
        assertTrue(p.maskInput && p.neverUnmask, "password inputs stay masked under lc-unmask")
        assertTrue(d.maskText && d.maskInput && d.maskImage, "masked by default")
    }

    @Test fun blockDropsSubtreeAndRegionsApply() {
        val blocked = FrameLayout(ctx).apply { tag = LcTag.BLOCK; addView(text("secret")) }
        val root = LinearLayout(ctx).apply { addView(blocked); addView(text("plain")) }
        layout(root, 200, 100)
        val cfg = ReplayConfig(maskAllText = false)
        val tree = ViewHierarchy(cfg, 1f).capture(root, listOf(MarkerRegion(MarkerRole.MASK, MaskRect(0, 0, 200, 100))))
        assertTrue(tree.children[0].block)
        assertTrue(tree.children[0].children.isEmpty() && tree.children[0].text == null)
        assertTrue(tree.children[1].maskText, "a Compose-style mask region masks a view under it")
    }

    @Test fun keystoreCipherRoundTripsAndRejectsTamperOrOtherKey() {
        val k1 = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val k2 = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val c = KeystoreQueueCipher(keyProvider = { k1 })
        val sealed = c.encrypt("""[{"event":"a"}]""")
        assertNotEquals(sealed, c.encrypt("""[{"event":"a"}]"""), "fresh IV per write")
        assertFalse(sealed.contains("event"))
        assertEquals("""[{"event":"a"}]""", c.decrypt(sealed))
        val bytes = Base64.decode(sealed, Base64.NO_WRAP).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(c.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP)), "tampered tag")
        assertNull(KeystoreQueueCipher(keyProvider = { k2 }).decrypt(sealed), "rotated key")
        assertNull(c.decrypt("not base64 !!"))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun screenshotPaintsMasksBeforeEncoding() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
        activity.setContentView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val decor = activity.window.decorView
        layout(decor, 200, 200)
        val d = activity.resources.displayMetrics.density
        val dp = Math.round(200 / d)
        val plan = ScreenshotMaskPlan(mask = listOf(MaskRect(0, 0, dp / 2, dp)), unmask = emptyList(), sensitive = emptyList())
        val frame = assertNotNull(ScreenshotCapture.capture(activity, plan, 80))
        val jpeg = Base64.decode(frame.jpegBase64, Base64.NO_WRAP)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(200, bmp.width)
        assertTrue(Color.red(bmp.getPixel(20, 100)) < 40, "masked half is painted black")
        assertTrue(Color.red(bmp.getPixel(180, 100)) > 200, "unmasked half keeps the white background")
        // Sensitive rects paint over unmask restores (passwords and lc-block always win).
        val both = ScreenshotMaskPlan(emptyList(), unmask = listOf(MaskRect(0, 0, dp, dp)), sensitive = listOf(MaskRect(0, 0, dp, dp)))
        val f2 = Base64.decode(assertNotNull(ScreenshotCapture.capture(activity, both, 80)).jpegBase64, Base64.NO_WRAP)
        assertTrue(Color.red(BitmapFactory.decodeByteArray(f2, 0, f2.size).getPixel(180, 100)) < 40)
        content.addView(TextView(activity).apply { text = "4111 1111" })
        layout(decor, 200, 200)
        val derived = ScreenshotMasks.plan(ViewHierarchy(ReplayConfig(), d).capture(decor))
        assertTrue(derived.mask.isNotEmpty() && derived.unmask.isEmpty(), "default config masks every text view")
    }
}
