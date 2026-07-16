package io.github.amichne.kast.api.contract.selector

enum class SelectorOperationFamily(internal val wireBit: Int) {
    REFERENCES(1 shl 0),
    CALLERS(1 shl 1),
    CALLEES(1 shl 2),
    IMPLEMENTATIONS(1 shl 3),
    HIERARCHY(1 shl 4),
    IMPACT(1 shl 5),
    RENAME(1 shl 6),
    REPLACE_DECLARATION(1 shl 7),
}
