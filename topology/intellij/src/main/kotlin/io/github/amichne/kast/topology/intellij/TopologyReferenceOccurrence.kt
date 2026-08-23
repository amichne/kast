package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.util.TextRange

/** Optional non-empty source anchor supplied by an enclosing super-type constructor call. */
internal sealed interface EnclosingSuperTypeCallRange {
    data class Observed internal constructor(
        val range: TextRange,
    ) : EnclosingSuperTypeCallRange

    data object Unavailable : EnclosingSuperTypeCallRange
}

/** Non-empty source occurrence for one compiler-resolved topology reference. */
internal sealed interface TopologyReferenceOccurrence {
    data class Admitted internal constructor(
        val range: TextRange,
    ) : TopologyReferenceOccurrence

    data object Rejected : TopologyReferenceOccurrence

    companion object {
        /**
         * Proof transition: `(TextRange, EnclosingSuperTypeCallRange) ->
         * TopologyReferenceOccurrence`.
         *
         * Admitted establishes a non-empty source range. A direct reference range has authority;
         * a synthetic zero-width reference may refine to its enclosing super-type call range.
         * Rejected is the closed expected failure when neither range carries source evidence. Raw
         * PSI ranges may enter only from the request-local IntelliJ extraction boundary.
         */
        fun refine(
            direct: TextRange,
            enclosingSuperTypeCall: EnclosingSuperTypeCallRange,
        ): TopologyReferenceOccurrence {
            val candidate = if (!direct.isEmpty) {
                direct
            } else {
                when (enclosingSuperTypeCall) {
                    is EnclosingSuperTypeCallRange.Observed -> enclosingSuperTypeCall.range
                    EnclosingSuperTypeCallRange.Unavailable -> return Rejected
                }
            }
            return if (candidate.isEmpty) Rejected else Admitted(candidate)
        }
    }
}
