package io.amichne.konditional.values

import io.amichne.konditional.values.IdentifierEncoding.SEPARATOR

@JvmInline
value class FeatureId private constructor(
    val plainId: String,
) : Comparable<FeatureId> {
    override fun compareTo(other: FeatureId): Int = plainId.compareTo(other.plainId)

    override fun toString(): String = plainId

    companion object {
        const val PREFIX: String = "feature"

        private const val LEGACY_PREFIX: String = "value"
        private const val EXPECTED_PARTS: Int = 3

        fun create(
            namespaceSeed: NamespaceId,
            key: String,
        ): FeatureId =
            FeatureId(IdentifierEncoding.encode(prefix = PREFIX, components = listOf(namespaceSeed.value, key)))

        /**
         * Parses a serialized identifier into a canonical [FeatureId].
         *
         * Supports the legacy `value::...` prefix for backwards compatibility with older snapshots.
         */
        fun parse(plainId: String): FeatureId {
            val parts = IdentifierEncoding.split(plainId)
            require(parts.size == EXPECTED_PARTS) {
                "FeatureId must be encoded as '$PREFIX$SEPARATOR<namespaceSeed>$SEPARATOR<key>': '$plainId'"
            }

            val prefix = parts[0]
            val namespaceSeed = parts[1]
            val key = parts[2]

            require(prefix == PREFIX || prefix == LEGACY_PREFIX) { "FeatureId prefix must be '$PREFIX': '$plainId'" }
            require(namespaceSeed.isNotBlank()) { "FeatureId namespaceSeed must not be blank: '$plainId'" }
            require(key.isNotBlank()) { "FeatureId key must not be blank: '$plainId'" }

            return create(namespaceSeed = NamespaceId(namespaceSeed), key = key)
        }
    }
}
