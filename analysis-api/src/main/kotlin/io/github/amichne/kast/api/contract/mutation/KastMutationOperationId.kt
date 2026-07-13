package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@JvmInline
value class KastMutationOperationId(
    val value: String,
) {
    init {
        require(runCatching { UUID.fromString(value) }.isSuccess) {
            "Mutation operation ID must be a UUID"
        }
    }

    override fun toString(): String = value

    companion object {
        fun random(): KastMutationOperationId = KastMutationOperationId(UUID.randomUUID().toString())
    }
}
