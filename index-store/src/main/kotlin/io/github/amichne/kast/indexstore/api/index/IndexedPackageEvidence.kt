package io.github.amichne.kast.indexstore.api.index

sealed interface IndexedPackageEvidence {
    data object ProvenRoot : IndexedPackageEvidence

    data class ProvenNamed(val canonicalName: CanonicalName) : IndexedPackageEvidence

    data class Unproven(val reason: IndexedPackageUnprovenReason) : IndexedPackageEvidence

    @JvmInline
    value class CanonicalName private constructor(val value: String) {
        companion object {
            fun parse(raw: String): CanonicalName {
                require(raw.isNotBlank()) { "Proven Kotlin package name must not be blank" }
                require(raw == raw.trim()) { "Proven Kotlin package name must be canonical" }
                require(raw.none(Char::isISOControl)) {
                    "Proven Kotlin package name must not contain control characters"
                }
                require(raw.split('.').all(String::isNotEmpty)) {
                    "Proven Kotlin package name must not contain empty segments"
                }
                return CanonicalName(raw)
            }
        }
    }
}
