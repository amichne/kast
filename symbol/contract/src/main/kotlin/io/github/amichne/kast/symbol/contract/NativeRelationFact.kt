package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceReadLease
import java.nio.charset.StandardCharsets

/**
 * Exact detached endpoint resolved by one native relation search and bound to the subject's
 * current-workspace lease and compiled scope.
 */
@ConsistentCopyVisibility
data class ExactRelationEndpoint private constructor(
    val lease: CurrentWorkspaceReadLease,
    val scope: SymbolSearchScope,
    val evidence: ExactDeclarationEvidence,
    val fingerprint: ExactDeclarationFingerprint,
) {
    companion object {
        /**
         * Proof transition:
         * ExactDeclarationSelector + ExactDeclarationEvidence to ExactRelationEndpoint.
         *
         * Establishes a deterministic exact identity for native resolved endpoint evidence under
         * the subject selector's root, current epoch, and scope. Live PSI may be extracted into the
         * evidence only by the request-local IntelliJ relation projector.
         */
        fun bind(
            subject: ExactDeclarationSelector,
            evidence: ExactDeclarationEvidence,
        ): ExactRelationEndpoint = ExactRelationEndpoint(
            lease = subject.lease,
            scope = subject.scope,
            evidence = evidence,
            fingerprint = exactDeclarationFingerprint(
                subject.lease,
                subject.scope,
                evidence,
            ),
        )
    }
}

/**
 * Exact source occurrence for a one-hop relation. [file] identifies the physical or virtual source
 * and [range] is an absolute, non-empty source range within that file.
 */
data class NativeRelationOccurrence(
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
) {
    companion object {
        /**
         * Proof transition:
         * SymbolDiscoveryFileIdentity + Int + Int to
         * Refinement<NativeRelationOccurrence, ExactDeclarationEvidenceFailure>.
         *
         * Establishes an exact detached file plus a non-negative, non-empty absolute source range.
         * [ExactDeclarationEvidenceFailure] is the closed expected failure. Raw offsets may enter
         * only from a request-local IntelliJ reference or declaration occurrence.
         */
        fun fromBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<NativeRelationOccurrence, ExactDeclarationEvidenceFailure> =
            when (
                val range = ExactDeclarationTextRange.parse(
                    rawStartInclusive,
                    rawEndExclusive,
                )
            ) {
                is Refinement.Refined ->
                    Refinement.Refined(NativeRelationOccurrence(file, range.value))
                is Refinement.Rejected -> range
            }
    }
}

enum class NativeRelationFactFailure {
    ENDPOINT_LEASE_MISMATCH,
    ENDPOINT_SCOPE_MISMATCH,
}

@ConsistentCopyVisibility
data class NativeRelationFact private constructor(
    val subject: ExactDeclarationSelector,
    val family: NativeRelationFamily,
    val related: ExactRelationEndpoint,
    val occurrence: NativeRelationOccurrence,
) : Comparable<NativeRelationFact> {
    override fun compareTo(other: NativeRelationFact): Int =
        NATIVE_RELATION_FACT_ORDER.compare(this, other)

    /**
     * Proof transition: NativeRelationFact to NativeRelationByteCount.
     *
     * Establishes the exact non-negative UTF-8 byte size of the canonical detached fact projection.
     * Raw bytes may be extracted only by bounded collectors and transport encoders.
     */
    fun projectedUtf8Size(): NativeRelationByteCount =
        when (
            val parsed = NativeRelationByteCount.parse(
                canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong(),
            )
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("UTF-8 byte size cannot be negative")
        }

    private fun canonicalProjection(): String = buildString {
        appendField(subject.fingerprint.value)
        appendField(family.name)
        appendField(related.fingerprint.value)
        appendField(occurrence.file.stableValue)
        appendField(occurrence.range.startInclusive.toString())
        appendField(occurrence.range.endExclusive.toString())
    }

    companion object {
        /**
         * Proof transition:
         * ExactDeclarationSelector + NativeRelationFamily + ExactRelationEndpoint +
         * NativeRelationOccurrence to Refinement<NativeRelationFact, NativeRelationFactFailure>.
         *
         * Establishes one exact one-hop fact whose related endpoint retains the subject selector's
         * root, current epoch, and scope. [NativeRelationFactFailure] is the closed expected failure.
         * Raw IntelliJ values may enter only through the already-refined endpoint and occurrence.
         */
        fun create(
            subject: ExactDeclarationSelector,
            family: NativeRelationFamily,
            related: ExactRelationEndpoint,
            occurrence: NativeRelationOccurrence,
        ): Refinement<NativeRelationFact, NativeRelationFactFailure> {
            if (related.lease != subject.lease) {
                return Refinement.Rejected(NativeRelationFactFailure.ENDPOINT_LEASE_MISMATCH)
            }
            if (related.scope != subject.scope) {
                return Refinement.Rejected(NativeRelationFactFailure.ENDPOINT_SCOPE_MISMATCH)
            }
            return Refinement.Refined(
                NativeRelationFact(subject, family, related, occurrence),
            )
        }

        private val NATIVE_RELATION_FACT_ORDER = compareBy<NativeRelationFact>(
            { it.family.ordinal },
            { it.related.fingerprint.value },
            { it.occurrence.file.stableValue },
            { it.occurrence.range.startInclusive },
            { it.occurrence.range.endExclusive },
        )
    }
}

private fun StringBuilder.appendField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
