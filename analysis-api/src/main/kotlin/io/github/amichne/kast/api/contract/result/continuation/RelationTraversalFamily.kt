package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class RelationTraversalFamily(val wireName: String) {
    CALLERS("callers"),
    CALLEES("callees"),
    IMPLEMENTATIONS("implementations"),
    HIERARCHY("hierarchy"),
    ;

    companion object {
        internal fun fromWireName(value: String): RelationTraversalFamily =
            entries.singleOrNull { family -> family.wireName == value }
                ?: throw IllegalArgumentException("Unknown relationship traversal family: $value")
    }
}
