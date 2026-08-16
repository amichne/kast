package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddFileChangePlan
import io.github.amichne.kast.change.contract.AddFileObligation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class ObservedAddFileDeltaFailure {
    FILE_COUNT_INVALID,
    FILE_MISSING,
    FILE_AMBIGUOUS,
}

/** Exact compiler observation that one Kotlin file identity exists in the resulting generation. */
class ObservedAddFileDelta private constructor(
    val source: SymbolDiscoveryFileIdentity.Workspace,
) {
    companion object {
        /**
         * Proof transition: `(WorkspaceFile, Int) -> Refinement<ObservedAddFileDelta,
         * ObservedAddFileDeltaFailure>`.
         *
         * Establishes exactly one compiler-visible Kotlin file at the requested workspace identity.
         * [ObservedAddFileDeltaFailure] closes invalid, absent, and ambiguous counts. Raw compiler
         * counts may enter only at the result-generation observation boundary.
         */
        fun fromCompilerBoundary(
            source: SymbolDiscoveryFileIdentity.Workspace,
            matchingFileCount: Int,
        ): Refinement<ObservedAddFileDelta, ObservedAddFileDeltaFailure> = when {
            matchingFileCount < 0 -> Refinement.Rejected(
                ObservedAddFileDeltaFailure.FILE_COUNT_INVALID,
            )
            matchingFileCount == 0 -> Refinement.Rejected(
                ObservedAddFileDeltaFailure.FILE_MISSING,
            )
            matchingFileCount == 1 -> Refinement.Refined(ObservedAddFileDelta(source))
            else -> Refinement.Rejected(ObservedAddFileDeltaFailure.FILE_AMBIGUOUS)
        }
    }
}

data class AddFileVerificationEvidence(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    val diagnostics: List<DiagnosticCheckResult>,
    val observedDelta: ObservedAddFileDelta,
) : ChangeVerificationEvidence

enum class AddFileProofFailure : ChangeProofFailure {
    RESULT_SOURCE_MISMATCH,
    RESULT_SOURCE_CONTENT_MISMATCH,
    DIAGNOSTIC_EVIDENCE_REQUIRED,
    DIAGNOSTIC_EVIDENCE_INCOMPLETE,
    DIAGNOSTIC_LEASE_MISMATCH,
    DIAGNOSTIC_SCOPE_MISMATCH,
    COMPILER_DIAGNOSTICS_REJECTED,
    FILE_IDENTITY_MISMATCH,
    OBLIGATION_SET_MISMATCH,
}

/** Complete G0-absence-to-G1-file proof for one exact AddFile plan. */
class CompleteAddFileVerification private constructor(
    override val plan: AddFileChangePlan,
    override val applied: AppliedUnverified,
    override val resulting: DistinctResultingWorkspace,
    val evidence: AddFileVerificationEvidence,
) : CompleteChangeVerification {
    override val obligations = plan.requiredVerification

    companion object {
        /**
         * Proof transition: `(AddFileChangePlan, AppliedUnverified,
         * DistinctResultingWorkspace, AddFileVerificationEvidence) -> Refinement<
         * CompleteAddFileVerification, Set<AddFileProofFailure>>`.
         *
         * Establishes the exact authority-derived postimage, complete clean diagnostics scoped to
         * the singleton created source, one compiler-visible resulting file identity, and the
         * exhaustive obligation set. [AddFileProofFailure] is the closed expected failure. Raw
         * compiler extraction is confined to [ObservedAddFileDelta].
         */
        fun admit(
            plan: AddFileChangePlan,
            applied: AppliedUnverified,
            resulting: DistinctResultingWorkspace,
            evidence: AddFileVerificationEvidence,
        ): Refinement<CompleteAddFileVerification, Set<AddFileProofFailure>> {
            val failures = linkedSetOf<AddFileProofFailure>()
            if (evidence.source != applied.source) {
                failures += AddFileProofFailure.RESULT_SOURCE_MISMATCH
            }
            if (evidence.content != applied.postimage) {
                failures += AddFileProofFailure.RESULT_SOURCE_CONTENT_MISMATCH
            }
            val completeDiagnostics = evidence.diagnostics.mapNotNull {
                it as? DiagnosticCheckResult.Complete
            }
            when {
                evidence.diagnostics.isEmpty() ->
                    failures += AddFileProofFailure.DIAGNOSTIC_EVIDENCE_REQUIRED
                completeDiagnostics.size != evidence.diagnostics.size ->
                    failures += AddFileProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE
                else -> {
                    if (completeDiagnostics.any {
                            it.batch.scope.lease != resulting.workspace.readLease
                        }
                    ) {
                        failures += AddFileProofFailure.DIAGNOSTIC_LEASE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.scope.files.mapTo(linkedSetOf()) { it.value } !=
                                setOf(applied.source.path.value)
                        }
                    ) {
                        failures += AddFileProofFailure.DIAGNOSTIC_SCOPE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.facts.any { it.severity == DiagnosticSeverity.ERROR }
                        }
                    ) {
                        failures += AddFileProofFailure.COMPILER_DIAGNOSTICS_REJECTED
                    }
                }
            }
            if (evidence.observedDelta.source != applied.source) {
                failures += AddFileProofFailure.FILE_IDENTITY_MISMATCH
            }
            if (plan.requiredVerification != AddFileObligation.entries) {
                failures += AddFileProofFailure.OBLIGATION_SET_MISMATCH
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(CompleteAddFileVerification(plan, applied, resulting, evidence))
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}
