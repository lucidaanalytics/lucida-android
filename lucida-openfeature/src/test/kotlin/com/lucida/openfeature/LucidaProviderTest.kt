package com.lucida.openfeature

import com.lucida.core.FlagDetails
import com.lucida.core.Json
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Value
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** $LUCIDA_TESTDATA, else the nearest testdata/ above the working dir (monorepo and mirror layouts both work). */
private fun testdata(rel: String): File {
    System.getenv("LUCIDA_TESTDATA")?.takeIf { it.isNotEmpty() }?.let { return File(it, rel) }
    return generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "testdata/$rel") }.firstOrNull { it.exists() }
        ?: error("testdata/$rel not found")
}

class LucidaProviderTest {
    @Test fun reasonTableMatchesSharedJson() {
        val root = Json.decodeObj(testdata("openfeature/reasons.json").readText())

        @Suppress("UNCHECKED_CAST")
        (root["reasons"] as Map<String, Map<String, String>>).forEach { (raw, v) ->
            assertEquals(v["reason"], ReasonMap.openFeature(raw, false), raw)
            v["variant_reason"]?.let { assertEquals(it, ReasonMap.openFeature(raw, true), raw) }
        }
        assertEquals("UNKNOWN", ReasonMap.openFeature("brand_new", false))
    }

    @Test fun fallbackMapsToDefaultAndKeepsRawReasonInMetadata() {
        val d = FlagDetails(true, null, null, "fallback", -1, null, false)
        assertEquals("DEFAULT", ReasonMap.openFeature(d.reason, false))
        assertEquals("fallback", ReasonMap.metadata(d).getString("lucidaReason"))
    }

    @Test fun metadataCarriesConditionIndexAndRequestId() {
        val m = ReasonMap.metadata(FlagDetails("b", null, "b", "condition_match", 2, "r1", false))
        assertEquals(2, m.getInt("conditionIndex"))
        assertEquals("r1", m.getString("requestId"))
        assertEquals(false, m.getBoolean("evaluatedLocally"))
    }

    @Test fun contextSplit() {
        val ctx = ImmutableContext(
            "u1",
            mapOf(
                "plan" to Value.String("pro"),
                "groups" to Value.Structure(mapOf("org" to Value.String("acme"))),
                "groupProperties" to Value.Structure(mapOf("org" to Value.Structure(mapOf("size" to Value.Integer(5))))),
            ),
        )
        val s = ContextMap.split(ctx)
        assertEquals("u1", s.targetingKey)
        assertEquals(mapOf("org" to "acme"), s.groups)
        assertEquals(mapOf("org" to mapOf("size" to 5)), s.groupProperties)
        assertEquals(mapOf("plan" to "pro"), s.personProperties)
    }
}
