package io.github.amichne.kast.api.contract

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class MutationAttemptId private constructor(val value: String) {
    companion object {
        fun parse(value: String): MutationAttemptId {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) {
                "Mutation attempt ID must be a canonical lowercase UUID"
            }
            require(parsed.version() == 4 && parsed.variant() == 2) {
                "Mutation attempt ID must be an RFC 4122 UUID-v4"
            }
            return MutationAttemptId(value)
        }
    }
}
