package com.lucida.openfeature

import com.lucida.core.FlagDetails
import com.lucida.core.Obj
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.EvaluationMetadata
import dev.openfeature.sdk.Value

/** Lucida reason -> OpenFeature reason; mirrors testdata/openfeature/reasons.json (the tests read that file). */
internal object ReasonMap {
    private val table = mapOf(
        "condition_match" to "TARGETING_MATCH", "sticky_bucket" to "CACHED", "no_condition_match" to "DEFAULT",
        "out_of_rollout" to "DEFAULT", "holdout" to "DEFAULT", "namespace_excluded" to "DEFAULT", "no_bucketing_key" to "DEFAULT",
        "prerequisite_failed" to "DEFAULT", "prerequisite_missing" to "DEFAULT", "prerequisite_cycle" to "ERROR",
        "inactive" to "DISABLED", "kill_switch" to "DISABLED", "app_context_excluded" to "DISABLED",
        "preview" to "STATIC", "user_preview" to "STATIC", "fallback" to "DEFAULT",
    )

    /** `condition_match` resolving to a variant string is a SPLIT; unknown reasons are UNKNOWN. */
    fun openFeature(lucidaReason: String, variantValue: Boolean): String =
        if (lucidaReason == "condition_match" && variantValue) "SPLIT" else table[lucidaReason] ?: "UNKNOWN"

    fun metadata(d: FlagDetails): EvaluationMetadata = EvaluationMetadata.builder().apply {
        putString("lucidaReason", d.reason)
        putBoolean("evaluatedLocally", d.evaluatedLocally)
        if (d.conditionIndex >= 0) putInt("conditionIndex", d.conditionIndex)
        d.requestId?.let { putString("requestId", it) }
    }.build()
}

internal object ContextMap {
    fun native(v: Value): Any? = when (v) {
        is Value.String -> v.string
        is Value.Boolean -> v.boolean
        is Value.Integer -> v.integer
        is Value.Double -> v.double
        is Value.Date -> v.date.toString()
        is Value.List -> v.list.map(::native)
        is Value.Structure -> v.structure.mapValues { native(it.value) }
        is Value.Null -> null
    }

    fun value(x: Any?): Value = when (x) {
        null -> Value.Null
        is Boolean -> Value.Boolean(x)
        is Int -> Value.Integer(x)
        is Number -> Value.Double(x.toDouble())
        is String -> Value.String(x)
        is List<*> -> Value.List(x.map(::value))
        is Map<*, *> -> Value.Structure(x.entries.associate { it.key.toString() to value(it.value) })
        else -> Value.String(x.toString())
    }

    class Split(
        val targetingKey: String,
        val groups: Map<String, String>,
        val groupProperties: Map<String, Obj>,
        val personProperties: Obj,
    )

    /** `groups` (type -> key) and `groupProperties` (type -> map) are reserved; every other attribute is a person property. */
    fun split(ctx: EvaluationContext?): Split {
        val groups = LinkedHashMap<String, String>()
        val gp = LinkedHashMap<String, Obj>()
        val person = LinkedHashMap<String, Any?>()
        ctx?.asMap()?.forEach { (k, v) ->
            when {
                k == "groups" && v is Value.Structure -> v.structure.forEach { (t, key) -> if (key is Value.String) groups[t] = key.string }
                k == "groupProperties" && v is Value.Structure ->
                    v.structure.forEach { (t, p) ->
                        @Suppress("UNCHECKED_CAST")
                        if (p is Value.Structure) gp[t] = native(p) as Obj
                    }
                else -> person[k] = native(v)
            }
        }
        return Split(ctx?.getTargetingKey() ?: "", groups, gp, person)
    }
}
