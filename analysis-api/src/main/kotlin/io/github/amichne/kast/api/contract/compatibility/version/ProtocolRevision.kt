package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class ProtocolRevision(
    @DocField(description = "Positive revision of the compatibility negotiation protocol.")
    val value: Int,
) {
    init {
        require(value > 0) { "Protocol revision must be positive" }
    }

    companion object {
        val CURRENT = ProtocolRevision(2)
    }
}
