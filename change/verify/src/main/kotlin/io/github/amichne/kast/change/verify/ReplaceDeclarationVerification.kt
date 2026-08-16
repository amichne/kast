package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.ReplaceDeclarationChangePlan
import io.github.amichne.kast.change.contract.ReplaceDeclarationObligation
import io.github.amichne.kast.change.contract.ReplacementDeclarationSourceText
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class ObservedReplaceDeclarationDeltaFailure {
    DECLARATION_COUNT_INVALID,
    DECLARATION_MISSING,
    DECLARATION_AMBIGUOUS,
    DECLARATION_SOURCE_INVALID,
    RANGE_LENGTH_MISMATCH,
}

/** Exact compiler observation of one replacement declaration in G1. */
class ObservedReplaceDeclarationDelta private constructor(
    val declaration: ReplacementDeclarationSourceText,
    val range: ExactDeclarationTextRange,
) {
    companion object {
        /**
         * Proof transition: `(String, ExactDeclarationTextRange, Int) -> Refinement<
         * ObservedReplaceDeclarationDelta, ObservedReplaceDeclarationDeltaFailure>`.
         *
         * Establishes exactly one compiler-visible canonical replacement declaration source whose
         * exact range has the same UTF-16 length. [ObservedReplaceDeclarationDeltaFailure] closes
         * invalid, absent, ambiguous, invalid-source, or range-mismatch observations. Raw compiler
         * source and counts may enter only at the resulting generation observation boundary.
         */
        fun fromCompilerBoundary(
            rawDeclaration: String,
            range: ExactDeclarationTextRange,
            matchingDeclarationCount: Int,
        ): Refinement<
            ObservedReplaceDeclarationDelta,
            ObservedReplaceDeclarationDeltaFailure,
        > = when {
            matchingDeclarationCount < 0 -> Refinement.Rejected(
                ObservedReplaceDeclarationDeltaFailure.DECLARATION_COUNT_INVALID,
            )
            matchingDeclarationCount == 0 -> Refinement.Rejected(
                ObservedReplaceDeclarationDeltaFailure.DECLARATION_MISSING,
            )
            matchingDeclarationCount > 1 -> Refinement.Rejected(
                ObservedReplaceDeclarationDeltaFailure.DECLARATION_AMBIGUOUS,
            )
            range.endExclusive - range.startInclusive != rawDeclaration.length ->
                Refinement.Rejected(
                    ObservedReplaceDeclarationDeltaFailure.RANGE_LENGTH_MISMATCH,
                )
            else -> when (val source = ReplacementDeclarationSourceText.parse(rawDeclaration)) {
                is Refinement.Refined -> Refinement.Refined(
                    ObservedReplaceDeclarationDelta(source.value, range),
                )
                is Refinement.Rejected -> Refinement.Rejected(
                    ObservedReplaceDeclarationDeltaFailure.DECLARATION_SOURCE_INVALID,
                )
            }
        }
    }
}

data class ReplaceDeclarationVerificationEvidence(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    val diagnostics: List<DiagnosticCheckResult>,
    val observedDelta: ObservedReplaceDeclarationDelta,
) : ChangeVerificationEvidence

enum class ReplaceDeclarationProofFailure : ChangeProofFailure {
    RESULT_SOURCE_MISMATCH,
    RESULT_SOURCE_CONTENT_MISMATCH,
    DIAGNOSTIC_EVIDENCE_REQUIRED,
    DIAGNOSTIC_EVIDENCE_INCOMPLETE,
    DIAGNOSTIC_LEASE_MISMATCH,
    DIAGNOSTIC_SCOPE_MISMATCH,
    COMPILER_DIAGNOSTICS_REJECTED,
    REPLACEMENT_DECLARATION_MISMATCH,
    REPLACEMENT_RANGE_MISMATCH,
    OBLIGATION_SET_MISMATCH,
}

/** Complete G0-to-G1 proof for one exact ReplaceDeclaration plan. */
class CompleteReplaceDeclarationVerification private constructor(
    override val plan: ReplaceDeclarationChangePlan,
    override val applied: AppliedUnverified,
    override val resulting: DistinctResultingWorkspace,
    val evidence: ReplaceDeclarationVerificationEvidence,
) : CompleteChangeVerification {
    override val obligations = plan.requiredVerification

    companion object {
        /**
         * Proof transition: `(ReplaceDeclarationChangePlan, AppliedUnverified,
         * DistinctResultingWorkspace, ReplaceDeclarationVerificationEvidence) -> Refinement<
         * CompleteReplaceDeclarationVerification, Set<ReplaceDeclarationProofFailure>>`.
         *
         * Establishes the exact authority-derived postimage, complete source-scoped clean
         * diagnostics, one compiler-observed declaration equal to the planned replacement, and
         * the exhaustive obligation set. [ReplaceDeclarationProofFailure] is the closed expected
         * failure. Raw compiler extraction is confined to [ObservedReplaceDeclarationDelta].
         */
        fun admit(
            plan: ReplaceDeclarationChangePlan,
            applied: AppliedUnverified,
            resulting: DistinctResultingWorkspace,
            evidence: ReplaceDeclarationVerificationEvidence,
        ): Refinement<
            CompleteReplaceDeclarationVerification,
            Set<ReplaceDeclarationProofFailure>,
        > {
            val failures = linkedSetOf<ReplaceDeclarationProofFailure>()
            if (evidence.source != applied.source) {
                failures += ReplaceDeclarationProofFailure.RESULT_SOURCE_MISMATCH
            }
            if (evidence.content != applied.postimage) {
                failures += ReplaceDeclarationProofFailure.RESULT_SOURCE_CONTENT_MISMATCH
            }
            val completeDiagnostics = evidence.diagnostics.mapNotNull {
                it as? DiagnosticCheckResult.Complete
            }
            when {
                evidence.diagnostics.isEmpty() ->
                    failures += ReplaceDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_REQUIRED
                completeDiagnostics.size != evidence.diagnostics.size ->
                    failures += ReplaceDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE
                else -> {
                    if (completeDiagnostics.any {
                            it.batch.scope.lease != resulting.workspace.readLease
                        }
                    ) {
                        failures += ReplaceDeclarationProofFailure.DIAGNOSTIC_LEASE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.scope.files.mapTo(linkedSetOf()) { it.value } !=
                                setOf(applied.source.path.value)
                        }
                    ) {
                        failures += ReplaceDeclarationProofFailure.DIAGNOSTIC_SCOPE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.facts.any { it.severity == DiagnosticSeverity.ERROR }
                        }
                    ) {
                        failures += ReplaceDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED
                    }
                }
            }
            if (evidence.observedDelta.declaration != plan.intent.replacement) {
                failures += ReplaceDeclarationProofFailure.REPLACEMENT_DECLARATION_MISMATCH
            }
            val expectedRange = plan.target.target.range.let { range ->
                ExactDeclarationTextRange.parse(
                    range.startInclusive,
                    range.startInclusive + plan.intent.replacement.value.length,
                )
            }
            when (expectedRange) {
                is Refinement.Refined -> if (evidence.observedDelta.range != expectedRange.value) {
                    failures += ReplaceDeclarationProofFailure.REPLACEMENT_RANGE_MISMATCH
                }
                is Refinement.Rejected ->
                    failures += ReplaceDeclarationProofFailure.REPLACEMENT_RANGE_MISMATCH
            }
            if (plan.requiredVerification != ReplaceDeclarationObligation.entries) {
                failures += ReplaceDeclarationProofFailure.OBLIGATION_SET_MISMATCH
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(
                    CompleteReplaceDeclarationVerification(plan, applied, resulting, evidence),
                )
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}
