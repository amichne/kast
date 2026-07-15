package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PluginImplementationVersion(
    @DocField(description = "IDEA plugin release version participating in compatibility negotiation.")
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Plugin implementation version must not be blank" }
        require(value.none(Char::isWhitespace)) {
            "Plugin implementation version must not contain whitespace"
        }
    }
}
