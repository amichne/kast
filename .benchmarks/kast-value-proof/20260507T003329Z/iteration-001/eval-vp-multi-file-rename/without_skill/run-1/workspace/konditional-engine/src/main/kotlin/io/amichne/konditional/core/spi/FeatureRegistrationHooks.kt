package io.amichne.konditional.core.spi

import io.amichne.konditional.core.features.Feature
import java.util.ServiceLoader

internal object FeatureRegistrationHooks {
    private val hooks: List<FeatureRegistrationHook> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            ServiceLoader.load(FeatureRegistrationHook::class.java).toList()
        }.getOrElse { emptyList() }
    }

    fun notifyFeatureDefined(feature: Feature<*, *, *>) {
        hooks.forEach { hook -> hook.onFeatureDefined(feature) }
    }
}
