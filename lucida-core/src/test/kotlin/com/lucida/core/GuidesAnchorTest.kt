package com.lucida.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** N-DAP-10 / N-DAP-7 / N-DAP-9: anchored steps by native_id, carousel paging, theme tokens and placement. */
class GuidesAnchorTest {
    class FakeAnchored : AnchoredGuidePresenter {
        val plain = mutableListOf<Triple<String, Int, List<String>>>()
        val anchored = mutableListOf<Pair<Int, GuideAnchor>>()
        var missing = false
        var tap: ((List<GuideButton>) -> GuideButton?)? = null

        override fun present(guide: MobileGuide, step: Int, buttons: List<GuideButton>, onAction: (GuideButton?) -> Unit) {
            plain.add(Triple(guide.id, step, buttons.map { it.label }))
            tap?.let { onAction(it(buttons)) }
        }

        override fun presentAnchored(guide: MobileGuide, step: Int, buttons: List<GuideButton>, anchor: GuideAnchor, onMissing: () -> Unit, onAction: (GuideButton?) -> Unit) {
            anchored.add(step to anchor)
            if (missing) {
                onMissing()
                return present(guide, step, buttons, onAction)
            }
            tap?.let { onAction(it(buttons)) }
        }

        override fun open(url: String) {}
    }

    private class Http(val json: String) : HttpPort {
        override fun send(method: String, url: String, body: String?, headers: Map<String, String>, gzip: Boolean): HttpResult =
            if (url.contains("/v1/guides")) HttpResult(200, json) else HttpResult(200)
    }

    private class Setup(json: String) {
        val presenter = FakeAnchored()
        val events = mutableListOf<Obj>()
        val core = Core(
            CoreConfig(
                apiKey = "lc_pk_test", apiHost = "https://ingest.test/", http = Http(json), store = MemoryStore(),
                queue = { QueueConfig(it, "lc_pk_test", flushAt = 100, flushIntervalMs = 60_000, random = { 0.0 }) },
            ),
        ).also { c -> c.onCaptured { events.add(it) } }
        val guides = GuidesService(core, GuidesConfig(enabled = true, presenter = presenter), background = {}) { false }
    }

    @Test fun tourStepsAnchorByNativeIdOthersShowPlain() {
        val t = Setup("""{"guides":[{"id":"t","name":"T","kind":"tour","conditions":{},"steps":[{"id":"a","title":"A","native_id":"checkout","placement":"top","wait_ms":500},{"id":"b","title":"B"}]}]}""")
        t.guides.reload()
        t.presenter.tap = { it.first() }
        t.guides.show("Home")
        assertEquals(listOf(0 to GuideAnchor("checkout", "top", 500, false)), t.presenter.anchored)
        assertEquals(listOf(1), t.presenter.plain.map { it.second })
    }

    @Test fun missingTargetEmitsEventAndFallsBack() {
        val t = Setup("""{"guides":[{"id":"tt","name":"T","kind":"tooltip","conditions":{},"steps":[{"id":"a","title":"A","native_id":"gone","wait_ms":99999}]}]}""")
        t.presenter.missing = true
        t.guides.reload()
        t.guides.show("Home")
        assertEquals(15_000L, t.presenter.anchored[0].second.waitMs) // clamped
        assertEquals(listOf("tt"), t.presenter.plain.map { it.first })
        val ev = t.events.first { it["event"] == "\$guide_target_missing" }
        @Suppress("UNCHECKED_CAST")
        val props = ev["properties"] as Map<String, Any?>
        assertEquals("gone", props["selector"])
        assertEquals("a", props["step_id"])
    }

    @Test fun hotspotNeedsNativeId() {
        val t = Setup("""{"guides":[{"id":"h0","name":"H","kind":"hotspot","conditions":{},"steps":[{"id":"a","selector":"#web"}]},{"id":"h1","name":"H","kind":"hotspot","conditions":{},"steps":[{"id":"a","native_id":"bell"}]}]}""")
        t.guides.reload()
        t.guides.show("Home")
        assertEquals(listOf(true), t.presenter.anchored.map { it.second.hotspot })
    }

    @Test fun carouselBackAndNext() {
        val t = Setup("""{"guides":[{"id":"c","name":"C","kind":"carousel","conditions":{},"steps":[{"id":"1","title":"One"},{"id":"2","title":"Two"}]}]}""")
        t.guides.reload()
        val taps = ArrayDeque(listOf("Next", "Back", "Next", "Done"))
        t.presenter.tap = { bs -> val want = taps.removeFirst(); bs.first { it.label == want } }
        t.guides.show("Home")
        assertEquals(listOf(0, 1, 0, 1), t.presenter.plain.map { it.second })
        assertEquals(listOf("Back", "Done", "Dismiss"), t.presenter.plain[1].third)
    }

    @Test fun themeTokens() {
        val a: Obj = mapOf(
            "tokens" to mapOf("background" to "#ffffff", "accent" to "javascript:x", "radius" to 8),
            "dark" to mapOf("tokens" to mapOf("background" to "#000")),
            "font_family" to "Inter",
        )
        assertEquals("#ffffff", GuideTheme.tokens(a, false).background)
        assertEquals("#000", GuideTheme.tokens(a, true).background)
        assertNull(GuideTheme.tokens(a, false).accent)
        assertEquals(8.0, GuideTheme.tokens(a, false).radius)
        assertEquals("#ffffff", GuideTheme.tokens(a + ("theme_mode" to "light"), true).background)
        assertEquals(0xFFFF0000.toInt(), GuideTheme.argb("#f00"))
    }

    @Test fun queuedFirstSkipsSamplingAndExclusionsMatchScreens() {
        val presenter = FakeAnchored()
        val core = Setup("""{"guides":[{"id":"plain","name":"P","kind":"modal","conditions":{},"steps":[{"id":"a"}]},{"id":"q","name":"Q","kind":"modal","queued":true,"conditions":{"sample_pct":1},"steps":[{"id":"a"}]},{"id":"x","name":"X","kind":"modal","conditions":{"exclude_url_patterns":["Check*"]},"steps":[{"id":"a"}]}]}""").core
        val guides = GuidesService(core, GuidesConfig(enabled = true, presenter = presenter), random = { 0.99 }, background = {}) { false }
        guides.reload()
        presenter.tap = { null }
        guides.show("Checkout")
        assertEquals(listOf("q", "plain"), presenter.plain.map { it.first }) // queued first despite 1% sampling; x excluded here
        assertTrue(GuidesService.excluded("Checkout", listOf("Check*")))
        assertTrue(!GuidesService.excluded("Home", listOf("Check*", "")))
        assertTrue(!GuidesService.excluded("a.b", listOf("a?b")))
        presenter.plain.clear()
        guides.show("Home")
        assertEquals(listOf("x"), presenter.plain.map { it.first })
    }

    @Test fun placementBelowAboveClamped() {
        val bounds = GuidePlacement.Box(0, 0, 400, 800)
        assertEquals(25 to 148, GuidePlacement.origin(200, 100, GuidePlacement.Box(100, 100, 50, 40), bounds, null, 12, 8))
        assertEquals(632, GuidePlacement.origin(200, 100, GuidePlacement.Box(100, 740, 50, 40), bounds, null, 12, 8).second)
        assertTrue(GuidePlacement.origin(200, 100, GuidePlacement.Box(380, 100, 20, 20), bounds, null, 12, 8).first <= 188)
    }
}
