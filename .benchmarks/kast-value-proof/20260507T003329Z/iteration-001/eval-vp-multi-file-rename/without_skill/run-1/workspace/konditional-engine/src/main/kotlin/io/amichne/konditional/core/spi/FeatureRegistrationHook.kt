package io.amichne.konditional.core.spi

import io.amichne.konditional.core.features.Feature

/**
 * SPI for optional integrations that want to observe feature definitions.
 *
 * This is primarily used to keep `:konditional-core` free of serialization/runtime dependencies while still
 * supporting automatic registration hooks when sibling modules are present on the classpath.
 */
fun interface FeatureRegistrationHook {
    fun onFeatureDefined(feature: Feature<*, *, *>)
}
