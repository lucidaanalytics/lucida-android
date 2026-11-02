package com.lucida.openfeature

import com.lucida.core.FlagDetails
import com.lucida.core.FlagsStatus
import com.lucida.core.Lucida
import com.lucida.core.Obj
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * OpenFeature provider over the Lucida Android SDK (static-context paradigm). `initialize` / `onContextSet`
 * identify the targeting key, apply attributes as person properties and reload flags; reads are synchronous
 * over the loaded flags. Written against dev.openfeature:kotlin-sdk 0.6.2.
 */
class LucidaProvider(private val lucida: Lucida) : FeatureProvider {
    override val hooks: List<Hook<*>> = emptyList()
    override val metadata: ProviderMetadata = object : ProviderMetadata { override val name = "Lucida" }
    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = 16)

    init {
        lucida.flags.onFlagsStatus {
            events.tryEmit(
                when (it) {
                    FlagsStatus.CHANGED -> OpenFeatureProviderEvents.ProviderConfigurationChanged
                    FlagsStatus.STALE -> OpenFeatureProviderEvents.ProviderStale
                    FlagsStatus.ERROR -> OpenFeatureProviderEvents.ProviderError(OpenFeatureError.GeneralError("Lucida flag load failed"))
                },
            )
        }
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override suspend fun initialize(initialContext: EvaluationContext?) {
        apply(initialContext)
        events.tryEmit(OpenFeatureProviderEvents.ProviderReady)
    }

    override fun shutdown() {}

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) = apply(newContext)

    private suspend fun apply(ctx: EvaluationContext?) = withContext(Dispatchers.IO) {
        val s = ContextMap.split(ctx)
        // core.* (not the facade) so identity changes do not start a second background reload.
        if (s.targetingKey.isNotEmpty() && s.targetingKey != lucida.distinctId) lucida.core.identify(s.targetingKey)
        s.groups.forEach { (t, key) -> lucida.core.group(t, key, s.groupProperties[t]) }
        lucida.flags.setPersonPropertiesForFlags(s.personProperties, reload = false)
        s.groupProperties.forEach { (t, p) -> if (t !in s.groups) lucida.flags.setGroupPropertiesForFlags(t, p, reload = false) }
        lucida.flags.reload()
    }

    // Evaluations

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?) =
        resolve(key, defaultValue) { it as? Boolean }

    override fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?) =
        resolve(key, defaultValue) { it as? String }

    override fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?) =
        resolve(key, defaultValue) { (it as? Number)?.toInt() }

    override fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?) =
        resolve(key, defaultValue) { (it as? Number)?.toDouble() }

    override fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?): ProviderEvaluation<Value> {
        // A json flag's value is its payload; boolean/variant flags read as their value.
        val d = lucida.flags.getFeatureFlagDetails(key) ?: return missing(key, defaultValue)
        return ProviderEvaluation(
            ContextMap.value(d.payload ?: d.value), d.variant,
            ReasonMap.openFeature(d.reason, d.variant != null), metadata = ReasonMap.metadata(d),
        )
    }

    private fun <T : Any> resolve(key: String, def: T, pick: (Any?) -> T?): ProviderEvaluation<T> {
        val d: FlagDetails = lucida.flags.getFeatureFlagDetails(key) ?: return missing(key, def)
        val v = pick(d.value) ?: pick(d.payload)
            ?: return ProviderEvaluation(
                def, reason = "ERROR", errorCode = ErrorCode.TYPE_MISMATCH,
                errorMessage = "flag $key is not of the requested type", metadata = ReasonMap.metadata(d),
            )
        return ProviderEvaluation(v, d.variant, ReasonMap.openFeature(d.reason, d.variant != null), metadata = ReasonMap.metadata(d))
    }

    private fun <T : Any> missing(key: String, def: T) =
        ProviderEvaluation(def, reason = "ERROR", errorCode = ErrorCode.FLAG_NOT_FOUND, errorMessage = "flag $key not found")

    /** Event = [trackingEventName]; properties = the details' fields plus `$value`. Identity is the one set by the context. */
    override fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?) {
        val props = LinkedHashMap<String, Any?>()
        details?.asMap()?.forEach { (k, v) -> props[k] = ContextMap.native(v) }
        details?.value?.let { props["\$value"] = it }
        lucida.capture(trackingEventName, props as Obj)
    }
}
