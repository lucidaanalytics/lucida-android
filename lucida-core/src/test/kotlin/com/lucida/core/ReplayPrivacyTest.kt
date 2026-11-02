package com.lucida.core

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P-M17 masked screens, P-M46 text patterns and `$dead_swipe`, P-L12 controlled exposure. */
@Suppress("UNCHECKED_CAST")
class ReplayPrivacyTest {
    private val tree = ViewNode(
        ViewKind.CONTAINER, 0, 0, 100, 200,
        children = listOf(
            ViewNode(ViewKind.TEXT, 10, 10, 50, 20, text = "Order 1234", maskText = false),
            ViewNode(ViewKind.IMAGE, 10, 40, 50, 20, maskImage = false),
            ViewNode(ViewKind.INPUT, 10, 70, 50, 20, text = "ann 42", maskInput = false),
        ),
    )

    private fun harness(response: String = "{}"): Harness {
        val h = Harness(ConsentMode.OPT_OUT, http = FakeHttp().apply { this.response = response })
        h.core.setConsent(mapOf("analytics" to true, "replay" to true))
        return h
    }

    private fun events(h: Harness, name: String): List<Obj> {
        val out = java.util.concurrent.CopyOnWriteArrayList<Obj>()
        h.core.onCaptured { if (it["event"] == name) out.add(it["properties"] as Obj) }
        return out
    }

    private fun replayBodies(h: Harness) = h.http.bodies.filter { it.contains("session_id") && it.contains("\"events\"") && !it.contains("\"batch\"") }.joinToString()

    // P-M17

    @Test fun screenMaskedPatterns() {
        assertTrue(ReplayPolicy.screenMasked("Checkout", listOf("Checkout")))
        assertFalse(ReplayPolicy.screenMasked("CheckoutDone", listOf("Checkout")), "whole-name match")
        assertTrue(ReplayPolicy.screenMasked("PaymentActivity", listOf("pay*")))
        assertTrue(ReplayPolicy.screenMasked("UserSettings", listOf("*settings")))
        assertTrue(ReplayPolicy.screenMasked("a-b-c", listOf("a*c")))
        assertTrue(ReplayPolicy.screenMasked("checkout", listOf("CHECKOUT")))
        assertFalse(ReplayPolicy.screenMasked("Home", listOf("pay*")))
        assertFalse(ReplayPolicy.screenMasked("Home", emptyList()))
    }

    @Test fun remoteParsesPrivacyKeys() {
        val r = RemoteReplayConfig.parse(
            """{"replay_mask_screens":["Pay*"],"replay_mask_text_patterns":["\\d+",7],"replay_exposure_fields":["card"],"replay_exposure_key":{"kid":"k1","n":"AQ","e":"AQAB","pkcs1":"AA=="}}""",
        )!!
        assertEquals(listOf("Pay*"), r.maskScreens)
        assertEquals(listOf("\\d+"), r.maskTextPatterns)
        assertEquals(listOf("card"), r.exposureFields)
        assertEquals("k1", r.exposureKey?.kid)
        assertNull(RemoteReplayConfig.parse("""{"replay_exposure_key":{"kid":"k"}}""")!!.exposureKey, "incomplete key = off")
    }

    @Test fun maskedScreenMasksEverythingInWireframes() {
        val h = harness("""{"replay_mask_screens":["check*"]}""")
        val rec = ReplayRecorder(h.core)
        rec.loadRemote()
        rec.screen(tree, "Checkout", 100, 200)
        rec.screen(tree, "Home", 100, 200)
        rec.flush()
        val body = replayBodies(h)
        assertEquals(1, Regex("Order 1234").findAll(body).count(), "only the unmasked Home screen shows it")
        val local = ReplayRecorder(harness().core, ReplayOptions(maskScreens = listOf("CHECKOUT")))
        val masked = local.applyPrivacy(tree, "Checkout", false)
        assertTrue(masked.children.all { it.maskText && it.maskInput && it.maskImage })
    }

    @Test fun maskedScreenScreenshotPlanDropsUnmaskRegions() {
        val rec = ReplayRecorder(harness().core, ReplayOptions(maskScreens = listOf("Pay*")))
        val regions = listOf(MarkerRegion(MarkerRole.UNMASK, MaskRect(0, 0, 100, 100)), MarkerRegion(MarkerRole.MASK, MaskRect(0, 100, 10, 10)))
        val plan = ScreenshotMasks.plan(rec.applyPrivacy(tree, "Payment", true), ReplayMasking.regions(regions, rec.screenMasked("Payment")))
        assertTrue(plan.unmask.isEmpty())
        assertEquals(3, plan.mask.size)
        assertEquals(1, plan.sensitive.size)
    }

    // P-M46 text patterns

    @Test fun maskPatternsStarsMatchesKeepsWhitespace() {
        val res = ReplayMasking.compile(listOf("\\d+", "(", "\\bsecret\\b"))
        assertEquals(2, res.size, "the invalid pattern is ignored")
        assertEquals("Order **** ****** code", ReplayMasking.maskPatterns("Order 1234 secret code", res))
        assertEquals("card **** ****", ReplayMasking.maskPatterns("card 4111 1111", ReplayMasking.compile(listOf("\\d{4} \\d{4}"))))
        assertEquals("nothing", ReplayMasking.maskPatterns("nothing", res))
        assertEquals("abc", ReplayMasking.maskPatterns("abc", emptyList()))
    }

    @Test fun patternsStarShownTextAndBlockInScreenshots() {
        val h = harness("""{"replay_mask_text_patterns":["\\d+"]}""")
        val rec = ReplayRecorder(h.core)
        rec.loadRemote()
        rec.screen(tree, "A", 100, 200)
        rec.flush()
        val body = replayBodies(h)
        assertTrue(body.contains("Order ****"))
        assertTrue(body.contains("ann **"))
        assertFalse(body.contains("1234"))
        val shot = ReplayRecorder(harness().core, ReplayOptions(maskTextPatterns = listOf("\\d+"))).applyPrivacy(tree, "A", true)
        assertEquals(listOf(true, false, true), shot.children.map { it.block })
        assertEquals(2, ScreenshotMasks.plan(shot).sensitive.size)
    }

    // P-M46 dead swipe

    @Test fun swipeDirectionDecisionAndFingerprint() {
        assertEquals("left", DeadSwipe.direction(-60.0, 10.0))
        assertEquals("right", DeadSwipe.direction(60.0, -10.0))
        assertEquals("up", DeadSwipe.direction(5.0, -70.0))
        assertEquals("down", DeadSwipe.direction(5.0, 70.0))
        val p = DeadSwipe.decide("A", "A", "x", "x", 0.0, -100.4, 5000, null)!!
        assertEquals(mapOf("\$screen_name" to "A", "\$swipe_direction" to "up", "\$swipe_distance" to 100), p)
        assertNull(DeadSwipe.decide("A", "A", "x", "x", 30.0, 30.0, 0, null), "under 48 dp")
        assertNull(DeadSwipe.decide("A", "A", "x", "y", 100.0, 0.0, 0, null), "content moved")
        assertNull(DeadSwipe.decide("B", "A", "x", "x", 100.0, 0.0, 0, null), "navigated")
        assertNull(DeadSwipe.decide("A", "A", "x", "x", 100.0, 0.0, 1999, 0), "cooldown")
        assertNotNull(DeadSwipe.decide("A", "A", "x", "x", 100.0, 0.0, 2000, 0))
        assertEquals(DeadSwipe.fingerprint(tree), DeadSwipe.fingerprint(tree.copy()))
        assertFalse(DeadSwipe.fingerprint(tree).contains("Order"), "never the text itself")
        assertTrue(DeadSwipe.fingerprint(tree) != DeadSwipe.fingerprint(tree.copy(children = tree.children.map { it.copy(y = it.y - 50) })))
    }

    @Test fun recorderCapturesDeadSwipeOnlyWhileRecording() {
        val h = harness()
        val seen = events(h, "\$dead_swipe")
        val rec = ReplayRecorder(h.core)
        assertFalse(rec.deadSwipe("Feed", tree, tree, 0.0, -200.0), "no snapshot yet")
        rec.screen(tree, "Feed", 100, 200)
        assertTrue(rec.deadSwipe("Feed", tree, tree, 0.0, -200.0))
        assertEquals(mapOf<String, Any?>("\$screen_name" to "Feed", "\$swipe_direction" to "up", "\$swipe_distance" to 200), seen.single().filterKeys { it in setOf("\$screen_name", "\$swipe_direction", "\$swipe_distance") })
        assertFalse(rec.deadSwipe("Feed", tree, tree, 100.0, 0.0), "2 s cooldown")
        h.now += 3000
        val scrolled = tree.copy(children = tree.children.map { it.copy(y = it.y - 80) })
        assertFalse(rec.deadSwipe("Feed", tree, scrolled, 0.0, -200.0))
        assertFalse(ReplayRecorder(h.core, ReplayOptions(captureDeadSwipes = false)).also { it.screen(tree, "Feed", 100, 200) }.deadSwipe("Feed", tree, tree, 0.0, -200.0))
        assertEquals(1, seen.size)
    }

    // P-L12 controlled exposure

    private fun keyPair(): Pair<PrivateKey, ExposureKey> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val url = java.util.Base64.getUrlEncoder().withoutPadding()
        return kp.private to ExposureKey("k1", url.encodeToString(pub.modulus.toByteArray()), url.encodeToString(pub.publicExponent.toByteArray()), "")
    }

    private fun decrypt(priv: PrivateKey, b64: String): String {
        val ct = java.util.Base64.getDecoder().decode(b64)
        assertEquals(256, ct.size)
        val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
        c.init(Cipher.DECRYPT_MODE, priv, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    @Test fun base64MatchesTheJdk() {
        for (n in 0..7) {
            val b = ByteArray(n) { (it * 37 + 200).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), Base64Codec.encode(b))
            assertContentEquals(b, Base64Codec.decode(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)))
            assertContentEquals(b, Base64Codec.decode(java.util.Base64.getEncoder().encodeToString(b)))
        }
        assertNull(Base64Codec.decode("a b"))
    }

    @Test fun truncateOnCodePointBoundary() {
        val (b, cut) = SensitiveCapture.truncate("a".repeat(189) + "é")
        assertEquals(189, b.size)
        assertTrue(cut)
        val (b2, cut2) = SensitiveCapture.truncate("short")
        assertContentEquals("short".toByteArray(), b2)
        assertFalse(cut2)
    }

    @Test fun encryptRoundTripAndBadKey() {
        val (priv, key) = keyPair()
        assertEquals("4111", decrypt(priv, SensitiveCapture.encrypt("4111".toByteArray(), key)!!))
        assertNull(SensitiveCapture.encrypt("x".toByteArray(), key.copy(n = "!!")))
    }

    @Test fun captureSensitiveNeedsListAndKeyAndDedupes() {
        val (priv, key) = keyPair()
        val h = harness(
            """{"replay_exposure_fields":["card","iban"],"replay_exposure_key":{"kid":"k1","n":"${key.n}","e":"${key.e}","pkcs1":"AA=="}}""",
        )
        val seen = events(h, "\$sensitive_capture")
        val rec = ReplayRecorder(h.core)
        assertFalse(rec.captureSensitive("card", "4111"), "remote config not loaded yet")
        rec.loadRemote()
        assertEquals(setOf("card", "iban"), rec.exposureFields())
        rec.screen(tree, "Pay", 100, 200)
        assertTrue(rec.captureSensitive("card", "4111 1111"))
        val p = seen.single()
        assertEquals("card", p["\$exposure_field"])
        assertEquals("k1", p["\$exposure_kid"])
        assertEquals("Pay", p["\$screen_name"])
        assertNull(p["\$exposure_truncated"])
        assertEquals("4111 1111", decrypt(priv, p["\$exposure_ciphertext"] as String))
        assertFalse(rec.captureSensitive("card", "4111 1111"), "same value: not re-sent")
        assertTrue(rec.captureSensitive("iban", "x".repeat(300)))
        assertEquals(true, seen.last()["\$exposure_truncated"])
        assertFalse(rec.captureSensitive("email", "a@b.c"), "unlisted")
        h.core.setConsent(mapOf("replay" to false))
        assertFalse(rec.captureSensitive("card", "other"), "needs replay consent")
    }
}
