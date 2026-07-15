package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class SupportedRuntimeCompatibilityPair(
    @DocField(description = "Exact release, revision, capability, and runtime facts for this row.")
    val facts: RuntimeCompatibilityFacts,
    @DocField(description = "Capabilities whose absence makes this row incompatible.")
    val requiredCapabilities: Set<RuntimeCapability>,
) {
    init {
        require(requiredCapabilities.all(facts::advertises)) {
            "Required capabilities must be advertised by the supported pair"
        }
    }
}
