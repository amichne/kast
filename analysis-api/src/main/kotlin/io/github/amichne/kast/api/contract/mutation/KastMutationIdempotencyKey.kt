package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class KastMutationIdempotencyKey(
    val value: String,
) {
    init {
        require(value == value.trim()) { "Mutation idempotency key must not have surrounding whitespace" }
        require(value.length in 1..MAX_LENGTH) {
            "Mutation idempotency key length must be between 1 and $MAX_LENGTH characters"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 128
    }
}
