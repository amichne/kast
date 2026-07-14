package io.github.amichne.kast.api.contract.result

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RelationTraversalHandle private constructor(val value: String) {
    val family: RelationTraversalFamily
        get() = parsedParts(value).family

    val opaqueId: String
        get() = parsedParts(value).opaqueId

    init {
        parsedParts(value)
    }

    companion object {
        private const val PREFIX = "rth1_"
        private const val UUID_TEXT_LENGTH = 36
        private const val MAX_HANDLE_LENGTH =
            PREFIX.length + "implementations".length + 1 + UUID_TEXT_LENGTH

        fun parse(value: String): RelationTraversalHandle = RelationTraversalHandle(value)

        fun create(family: RelationTraversalFamily, opaqueId: String): RelationTraversalHandle =
            RelationTraversalHandle("$PREFIX${family.wireName}_$opaqueId")

        private fun parsedParts(value: String): ParsedHandleParts {
            require(value.isNotBlank()) { "Relationship traversal handle must not be blank" }
            require(value.length <= MAX_HANDLE_LENGTH) { "Relationship traversal handle is too long" }
            require(value.all { character -> character.code <= 0x7f }) {
                "Relationship traversal handle must contain only ASCII characters"
            }
            require(value.startsWith(PREFIX)) {
                "Relationship traversal handle must use the rth1 version"
            }

            val familySeparator = value.indexOf('_', startIndex = PREFIX.length)
            require(familySeparator > PREFIX.length) {
                "Relationship traversal handle must contain a family and UUID"
            }
            val family = RelationTraversalFamily.fromWireName(
                value.substring(PREFIX.length, familySeparator),
            )
            val rawUuid = value.substring(familySeparator + 1)
            require(rawUuid.length == UUID_TEXT_LENGTH) {
                "Relationship traversal handle UUID must use canonical length"
            }
            val uuid = UUID.fromString(rawUuid)
            require(uuid.toString() == rawUuid) {
                "Relationship traversal handle UUID must be canonical lowercase text"
            }
            return ParsedHandleParts(family, rawUuid)
        }
    }

    private data class ParsedHandleParts(
        val family: RelationTraversalFamily,
        val opaqueId: String,
    )
}
