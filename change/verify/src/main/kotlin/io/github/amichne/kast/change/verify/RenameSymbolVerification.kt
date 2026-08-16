package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.KotlinIdentifier
import io.github.amichne.kast.change.contract.RenameSymbolChangePlan
import io.github.amichne.kast.change.contract.RenameSymbolObligation
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceRole
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class ObservedRenameSymbolDeltaFailure {
    NEGATIVE_COUNT,
}

/** Detached compiler counts for the old identity and exact renamed identity in G1. */
class ObservedRenameSymbolDelta private constructor(
    val oldName: KotlinIdentifier,
    val newName: KotlinIdentifier,
    val oldDeclarationCount: Int,
    val newDeclarationCount: Int,
    val remainingOldReferenceCount: Int,
    val renamedReferenceCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(KotlinIdentifier, KotlinIdentifier, Int, Int, Int, Int) ->
         * Refinement<ObservedRenameSymbolDelta, ObservedRenameSymbolDeltaFailure>`.
         *
         * Establishes non-negative compiler-observed declaration and reference counts for both
         * identities. [ObservedRenameSymbolDeltaFailure] closes invalid counts. Raw counts may
         * enter only at the result-generation compiler boundary.
         */
        fun fromCompilerBoundary(
            oldName: KotlinIdentifier,
            newName: KotlinIdentifier,
            oldDeclarationCount: Int,
            newDeclarationCount: Int,
            remainingOldReferenceCount: Int,
            renamedReferenceCount: Int,
        ): Refinement<ObservedRenameSymbolDelta, ObservedRenameSymbolDeltaFailure> {
            val counts = listOf(
                oldDeclarationCount,
                newDeclarationCount,
                remainingOldReferenceCount,
                renamedReferenceCount,
            )
            return if (counts.any { it < 0 }) {
                Refinement.Rejected(ObservedRenameSymbolDeltaFailure.NEGATIVE_COUNT)
            } else {
                Refinement.Refined(
                    ObservedRenameSymbolDelta(
                        oldName,
                        newName,
                        oldDeclarationCount,
                        newDeclarationCount,
                        remainingOldReferenceCount,
                        renamedReferenceCount,
                    ),
                )
            }
        }
    }
}

data class RenameSymbolVerificationEvidence(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    val diagnostics: List<DiagnosticCheckResult>,
    val observedDelta: ObservedRenameSymbolDelta,
) : ChangeVerificationEvidence

enum class RenameSymbolProofFailure : ChangeProofFailure {
    RESULT_SOURCE_MISMATCH,
    RESULT_SOURCE_CONTENT_MISMATCH,
    DIAGNOSTIC_EVIDENCE_REQUIRED,
    DIAGNOSTIC_EVIDENCE_INCOMPLETE,
    DIAGNOSTIC_LEASE_MISMATCH,
    DIAGNOSTIC_SCOPE_MISMATCH,
    COMPILER_DIAGNOSTICS_REJECTED,
    OLD_NAME_MISMATCH,
    NEW_NAME_MISMATCH,
    OLD_DECLARATION_REMAINS,
    NEW_DECLARATION_NOT_UNIQUE,
    OLD_REFERENCE_REMAINS,
    RENAMED_REFERENCE_COUNT_MISMATCH,
    OBLIGATION_SET_MISMATCH,
}

/** Complete G0-to-G1 proof for one exact RenameSymbol plan. */
class CompleteRenameSymbolVerification private constructor(
    override val plan: RenameSymbolChangePlan,
    override val applied: AppliedUnverified,
    override val resulting: DistinctResultingWorkspace,
    val evidence: RenameSymbolVerificationEvidence,
) : CompleteChangeVerification {
    override val obligations = plan.requiredVerification

    companion object {
        /**
         * Proof transition: `(RenameSymbolChangePlan, AppliedUnverified,
         * DistinctResultingWorkspace, RenameSymbolVerificationEvidence) -> Refinement<
         * CompleteRenameSymbolVerification, Set<RenameSymbolProofFailure>>`.
         *
         * Establishes the exact authority-derived postimage, complete source-scoped clean
         * diagnostics, absence of the old declaration and references, one new declaration, exact
         * retargeted-reference count, and the exhaustive obligation set.
         * [RenameSymbolProofFailure] is the closed expected failure. Raw compiler extraction is
         * confined to construction of [ObservedRenameSymbolDelta].
         */
        fun admit(
            plan: RenameSymbolChangePlan,
            applied: AppliedUnverified,
            resulting: DistinctResultingWorkspace,
            evidence: RenameSymbolVerificationEvidence,
        ): Refinement<CompleteRenameSymbolVerification, Set<RenameSymbolProofFailure>> {
            val failures = linkedSetOf<RenameSymbolProofFailure>()
            if (evidence.source != applied.source) {
                failures += RenameSymbolProofFailure.RESULT_SOURCE_MISMATCH
            }
            if (evidence.content != applied.postimage) {
                failures += RenameSymbolProofFailure.RESULT_SOURCE_CONTENT_MISMATCH
            }
            val completeDiagnostics = evidence.diagnostics.mapNotNull {
                it as? DiagnosticCheckResult.Complete
            }
            when {
                evidence.diagnostics.isEmpty() ->
                    failures += RenameSymbolProofFailure.DIAGNOSTIC_EVIDENCE_REQUIRED
                completeDiagnostics.size != evidence.diagnostics.size ->
                    failures += RenameSymbolProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE
                else -> {
                    if (completeDiagnostics.any {
                            it.batch.scope.lease != resulting.workspace.readLease
                        }
                    ) {
                        failures += RenameSymbolProofFailure.DIAGNOSTIC_LEASE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.scope.files.mapTo(linkedSetOf()) { it.value } !=
                                setOf(applied.source.path.value)
                        }
                    ) {
                        failures += RenameSymbolProofFailure.DIAGNOSTIC_SCOPE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.facts.any { it.severity == DiagnosticSeverity.ERROR }
                        }
                    ) {
                        failures += RenameSymbolProofFailure.COMPILER_DIAGNOSTICS_REJECTED
                    }
                }
            }
            val delta = evidence.observedDelta
            if (delta.oldName != plan.intent.occurrences.currentName) {
                failures += RenameSymbolProofFailure.OLD_NAME_MISMATCH
            }
            if (delta.newName != plan.intent.newName) {
                failures += RenameSymbolProofFailure.NEW_NAME_MISMATCH
            }
            if (delta.oldDeclarationCount != 0) {
                failures += RenameSymbolProofFailure.OLD_DECLARATION_REMAINS
            }
            if (delta.newDeclarationCount != 1) {
                failures += RenameSymbolProofFailure.NEW_DECLARATION_NOT_UNIQUE
            }
            if (delta.remainingOldReferenceCount != 0) {
                failures += RenameSymbolProofFailure.OLD_REFERENCE_REMAINS
            }
            val expectedReferences = plan.intent.occurrences.occurrences.count {
                it.role == RenameSymbolOccurrenceRole.REFERENCE
            }
            if (delta.renamedReferenceCount != expectedReferences) {
                failures += RenameSymbolProofFailure.RENAMED_REFERENCE_COUNT_MISMATCH
            }
            if (plan.requiredVerification != RenameSymbolObligation.entries) {
                failures += RenameSymbolProofFailure.OBLIGATION_SET_MISMATCH
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(
                    CompleteRenameSymbolVerification(plan, applied, resulting, evidence),
                )
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}
