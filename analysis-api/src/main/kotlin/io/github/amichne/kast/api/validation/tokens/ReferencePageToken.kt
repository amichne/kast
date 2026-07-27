package io.github.amichne.kast.api.validation

import java.util.UUID

@JvmInline
value class ReferencePageToken private constructor(val value: String) {
    companion object {
        fun parse(value: String): ReferencePageToken {
            val parsed = UUID.fromString(value)
            require(parsed.toString() == value) { "Reference page token must be a canonical UUID" }
            return ReferencePageToken(value)
        }

        fun random(): ReferencePageToken = ReferencePageToken(UUID.randomUUID().toString())
    }
}
