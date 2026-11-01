package com.lucida.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LC-ENG-15: guides from GET /v1/guides, targeted by screen, drawn through the presenter port. */
class GuidesTest {
    class FakePresenter : GuidePresenter {
        val shown = mutableListOf<Triple<String, Int, List<String>>>()
        val opened = mutableListOf<String>()
        var tap: ((List<GuideButton>) -> GuideButton?)? = null

        override fun present(guide: MobileGuide, step: Int, buttons: List<GuideButton>, onAction: (GuideButton?) -> Unit) {
            shown.add(Triple(guide.id, step, buttons.map { it.label }))
            tap?.let { onAction(it(buttons)) }
        }

        override fun open(url: String) {
            opened.add(url)
        }
    }

    private class GuidesHttp(val json: String) : HttpPort {
        val urls = mutableListOf<String>()
        override fun send(method: String, url: String, body: String?, headers: Map<String, String>, gzip: Boolean): HttpResult {
            urls.add("$method $url")
            return if (url.contains("/v1/guides")) HttpResult(200, json) else HttpResult(200)
        }
    }

    private val guidesJson = """
    {"guides":[
      {"id":"g-tour","name":"Tour","kind":"tour","conditions":{"url":"Home","person_filters":[]},"steps":[{"id":"s1","title":"Hi"},{"id":"s2","body":"Last","buttons":[{"label":"Docs","action":"link","url":"https://docs.test/x"}]}]},
      {"id":"g-person","name":"P","kind":"modal","conditions":{"person_filters":[{"key":"plan","op":"eq","value":"pro"}]},"steps":[{"id":"a"}]},
      {"id":"g-hotspot","name":"H","kind":"hotspot","conditions":{},"steps":[{"id":"a","selector":"#x"}]},
      {"id":"g-flag","name":"F","kind":"banner","conditions":{"flag_key":"beta","url":"Set","url_match":"regex"},"steps":[{"id":"b"}]}
    ]}
    """

    private class Setup(json: String, flags: Set<String>) {
        val http = GuidesHttp(json)
        val presenter = FakePresenter()
        val events = mutableListOf<String>()
        val core = Core(
            CoreConfig(
                apiKey = "lc_pk_test", apiHost = "https://ingest.test/", http = http, store = MemoryStore(),
                queue = { QueueConfig(it, "lc_pk_test", flushAt = 100, flushIntervalMs = 60_000, random = { 0.0 }) },
            ),
        ).also { c -> c.onCaptured { events.add(it["event"] as String) } }
        val guides = GuidesService(core, GuidesConfig(enabled = true, presenter = presenter)) { it in flags }
    }

    private fun setup(flags: Set<String> = emptySet()) = Setup(guidesJson, flags)

    @Test fun tourOnItsScreenWithEventsAndShowOnce() {
        val t = setup()
        t.guides.show("Settings") // nothing loaded yet
        t.guides.reload()
        assertTrue(t.presenter.shown.isEmpty(), "Settings: the tour wants Home, the banner's flag is off")
        val req = t.http.urls.first { it.contains("/v1/guides") }
        assertTrue(req.startsWith("GET "))
        assertTrue(req.contains("api_key=lc_pk_test"))
        assertTrue(req.contains("distinct_id="))

        t.presenter.tap = { it.first() } // Next, then the link button
        t.guides.show("HomeScreen")
        assertEquals(listOf("g-tour", "g-tour"), t.presenter.shown.map { it.first })
        assertEquals(listOf(0, 1), t.presenter.shown.map { it.second })
        assertEquals(listOf("Next", "Dismiss"), t.presenter.shown[0].third)
        assertEquals(listOf("Docs"), t.presenter.shown[1].third)
        assertEquals(listOf("https://docs.test/x"), t.presenter.opened)
        assertEquals(listOf("\$guide_shown", "\$guide_step", "\$guide_step", "\$guide_completed"), t.events.filter { it.startsWith("\$guide") })

        t.presenter.shown.clear()
        t.guides.show("HomeScreen")
        assertTrue(t.presenter.shown.isEmpty(), "shown once; person-targeted and hotspot guides never show on mobile")
    }

    @Test fun flagAndRegexTargetingAndDismiss() {
        val t = setup(setOf("beta"))
        t.guides.reload()
        t.presenter.tap = { null } // closed without a button
        t.guides.show("Settings")
        assertEquals(listOf("g-flag"), t.presenter.shown.map { it.first })
        assertEquals(listOf("Dismiss"), t.presenter.shown[0].third)
        assertTrue("\$guide_dismissed" in t.events)
    }

    @Test fun previewIsQuietAndOncePerLaunch() {
        val t = Setup("""{"guides":[{"id":"p","name":"P","kind":"modal","preview":true,"conditions":{"person_filters":[{"key":"a"}]},"steps":[{"id":"a"}]}]}""", emptySet())
        t.guides.reload()
        t.presenter.tap = { it.first() }
        t.guides.show("Any")
        assertEquals(listOf("p"), t.presenter.shown.map { it.first })
        assertTrue(t.events.none { it.startsWith("\$guide") })
        t.guides.show("Any")
        assertEquals(1, t.presenter.shown.size)
    }

    @Test fun matchModes() {
        assertTrue(GuidesService.matches("Checkout", "Check", "contains"))
        assertFalse(GuidesService.matches("Checkout", "Check", "exact"))
        assertTrue(GuidesService.matches("Checkout", "^Check.*t$", "regex"))
        assertFalse(GuidesService.matches("Checkout", "(", "regex"))
    }
}
