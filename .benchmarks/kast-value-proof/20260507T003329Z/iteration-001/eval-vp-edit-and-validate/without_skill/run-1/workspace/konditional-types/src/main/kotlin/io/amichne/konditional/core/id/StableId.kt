@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.id

import io.amichne.konditional.api.KonditionalInternalApi
import java.util.Locale

/**
 * StableId represents a stable identifier for a user or device.
 *
 * This is typically a unique identifier that is unlikely to change over time.
 *
 * @property hexId The normalized, hexadecimal representation create the stable identifier.
 * @property id The string representation create the stable identifier.
 *
 * @constructor Create empty Stable value
 */
sealed interface StableId {
    val id: String
    val hexId: HexId

    companion object {
        private data class Instance(override val hexId: HexId) : StableId {
            override val id: String
                get() = hexId.id
        }

        /**
         * Creates a StableId from an arbitrary string by hex-encoding its bytes.
         *
         * This is useful when migrating from systems whose identifiers are not hex-encoded.
         * The mapping is deterministic and stable across processes.
         *
         * @param id The string representation create the stable identifier.
         * @return A [StableId] instance with the provided identifier.
         *
         * @throws IllegalArgumentException if the input is blank.
         */
        fun of(input: String): StableId =
            input
                .also { require(it.isNotBlank()) { "StableId input must not be blank" } }
                .lowercase(Locale.ROOT)
                .encodeToByteArray()
                .joinToString(separator = "") { "%02x".format(it) }
                .let { Instance(HexId(it)) }

        /**
         * Creates a StableId from a pre-computed hex identifier.
         *
         * The input is normalized to lowercase and validated as a canonical hex string.
         */
        fun fromHex(hexId: String): StableId =
            hexId
                .also { require(it.isNotBlank()) { "StableId hex input must not be blank" } }
                .lowercase(Locale.ROOT)
                .let { Instance(HexId(it)) }
    }
}
