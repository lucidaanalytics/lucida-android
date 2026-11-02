package com.lucida.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InstallAttributionTest {
    @Test fun parsesReferrer() {
        val p = installReferrerProps("utm_source=google-play&utm_medium=organic&utm_campaign=a%20b&gclid=G1&junk", 11L, 22L)
        assertEquals("google_play", p["\$install_store"])
        assertEquals("a b", p["\$utm_campaign"])
        assertEquals("google-play", p["\$utm_source"])
        assertEquals("G1", p["\$gclid"])
        assertEquals(11L, p["\$install_referrer_click_ts"])
        assertEquals(22L, p["\$install_begin_ts"])
        assertFalse(p.containsKey("\$utm_term"))
    }

    @Test fun installFiresOnceWithAttribution() {
        val t = Harness()
        val events = mutableListOf<Obj>()
        t.core.onCaptured { events.add(it) }
        val src: InstallSource = { done -> done(mapOf("\$install_store" to "google_play")); done(mapOf("x" to 1)) }
        Lifecycle(t.core, "1.0", installSource = src).launched()
        Lifecycle(t.core, "1.0", installSource = src).launched()
        val installs = events.filter { it["event"] == "\$app_installed" }
        assertEquals(1, installs.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("google_play", (installs[0]["properties"] as Obj)["\$install_store"])
    }

    @Test fun throwingSourceStillSendsInstall() {
        val t = Harness()
        val names = mutableListOf<String>()
        t.core.onCaptured { names.add(it["event"] as String) }
        Lifecycle(t.core, "1.0", installSource = { error("boom") }).launched()
        assertEquals(listOf("\$app_installed", "\$app_opened"), names)
    }
}
