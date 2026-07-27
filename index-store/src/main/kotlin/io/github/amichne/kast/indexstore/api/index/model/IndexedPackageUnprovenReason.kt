package io.github.amichne.kast.indexstore.api.index

enum class IndexedPackageUnprovenReason {
    NOT_SCANNED,
    SEMANTIC_ANALYSIS_UNAVAILABLE,
    SEMANTIC_ANALYSIS_FAILED,
    LEGACY_TEXT_ONLY,
}
