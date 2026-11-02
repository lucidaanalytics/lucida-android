package com.lucida.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** G241: schedule recurrence, frequency caps, holdout and the guide-state view sync on Android. */
class GuideRulesTest {
    // Sunday 2026-11-01 12:00 UTC is Sunday 07:00 in New York.
    private val now = 1_793_534_400_000L

    @Test fun scheduleRecurrenceInTheGuideZone() {
        val ny = mapOf("days" to listOf(0), "hours" to mapOf("from" to 6, "to" to 9), "timezone" to "America/New_York")
        assertTrue(GuideRules.scheduleOk(ny, now))
        assertFalse(GuideRules.scheduleOk(mapOf("days" to listOf(1), "timezone" to "UTC"), now))
        assertFalse(GuideRules.scheduleOk(mapOf("end" to "2026-11-01T12:00:00.000Z"), now))
        assertTrue(GuideRules.scheduleOk(mapOf("start" to "2026-11-01T11:00:00+00:00"), now))
        assertTrue(GuideRules.scheduleOk(null, now))
    }

    @Test fun frequencyAndMerge() {
        val f = mapOf("max_views" to 2, "per_days" to 7)
        assertTrue(GuideRules.frequencyOk(f, listOf(now - 10 * 86_400_000L, now - 1000), now))
        assertFalse(GuideRules.frequencyOk(f, listOf(now - 2000, now - 1000), now))
        assertFalse(GuideRules.frequencyOk(emptyMap<String, Any>(), listOf(1L), now), "omitted: once per lifetime")
        val state: Obj = mapOf("lc_guide_progress" to mapOf("t" to 2), "lc_guide_views" to mapOf("g" to listOf(1L)))
        val m = GuideRules.merged(state, mapOf("g" to listOf(1L, 5L), "h" to listOf(3L)))
        assertEquals(state["lc_guide_progress"], m["lc_guide_progress"], "other keys are kept")
        assertEquals(mapOf("g" to listOf(1L, 5L), "h" to listOf(3L)), m["lc_guide_views"])
    }

    private class StateHttp : HttpPort {
        val calls = mutableListOf<Pair<String, String?>>()
        override fun send(method: String, url: String, body: String?, headers: Map<String, String>, gzip: Boolean): HttpResult {
            calls.add("$method $url" to body)
            return when {
                url.contains("/v1/guides") -> HttpResult(
                    200,
                    """{"guides":[
                      {"id":"g-held","name":"H","kind":"modal","held_out":true,"conditions":{"holdout_pct":50,"ab_group":"x"},"steps":[]},
                      {"id":"g-twice","name":"T","kind":"modal","conditions":{"frequency":{"max_views":2}},"steps":[{"id":"a"}]}
                    ]}""",
                )
                url.contains("/v1/guide-state") -> HttpResult(200, """{"state":{"lc_guide_progress":{"t":1}}}""")
                else -> HttpResult(200)
            }
        }
    }

    @Test fun heldOutNeverShowsAndFrequencyRepeatsAcrossVisits() {
        val http = StateHttp()
        val presenter = GuidesTest.FakePresenter().apply { tap = { null } }
        val events = mutableListOf<String>()
        val core = Core(
            CoreConfig(
                apiKey = "lc_pk_test", apiHost = "https://ingest.test/", http = http, store = MemoryStore(),
                queue = { QueueConfig(it, "lc_pk_test", flushAt = 100, flushIntervalMs = 60_000, random = { 0.0 }) },
            ),
        ).also { c -> c.onCaptured { events.add(it["event"] as String) } }
        val guides = GuidesService(core, GuidesConfig(enabled = true, presenter = presenter), now = { now }, background = { it() }) { false }
        guides.reload()
        assertTrue(http.calls.first { it.first.contains("/v1/guides") }.first.contains("tz="))
        guides.show("Home")
        guides.show("Home") // once per screen visit
        guides.show("Settings")
        guides.show("Home")
        assertEquals(listOf("g-twice", "g-twice"), presenter.shown.map { it.first }, "shown up to its cap, the held-out guide never")
        assertEquals(1, events.count { it == "\$guide_holdout" })
        val put = http.calls.last { it.first.contains("/v1/guide-state") }.second!!
        val state = Json.decodeObj(put)["state"] as Map<*, *>
        assertEquals(mapOf("t" to 1L), (state["lc_guide_progress"] as Map<*, *>).mapValues { (it.value as Number).toLong() })
        assertTrue((state["lc_guide_views"] as Map<*, *>).containsKey("g-twice"))
    }
}
