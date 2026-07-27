package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class CliImplementationVersion(
    @DocField(description = "CLI release version participating in compatibility negotiation.")
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "CLI implementation version must not be blank" }
        require(value.none(Char::isWhitespace)) {
            "CLI implementation version must not contain whitespace"
        }
    }
}
