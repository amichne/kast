package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class RuntimeImplementationVersion(
    @DocField(description = "Runtime host implementation version reported by the workspace.")
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Runtime implementation version must not be blank" }
        require(value.none(Char::isWhitespace)) {
            "Runtime implementation version must not contain whitespace"
        }
    }
}
