package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

enum class ObservedAddDeclarationDeltaFailure {
    DECLARATION_COUNT_INVALID,
    DECLARATION_MISSING,
    DECLARATION_AMBIGUOUS,
    PACKAGE_NAME_INVALID,
    DECLARATION_NAME_INVALID,
}

/** Detached compiler observation of the declaration added in the resulting generation. */
class ObservedAddDeclarationDelta private constructor(
    private val identity: ExpectedAddDeclarationDelta,
) {
    val packageName: String
        get() = identity.packageName

    val declarationName: String
        get() = identity.declarationName

    val declarationKind: AddDeclarationKind
        get() = identity.declarationKind

    companion object {
        /**
         * Proof transition: `(String, String, AddDeclarationKind, Int) -> Refinement<
         * ObservedAddDeclarationDelta, ObservedAddDeclarationDeltaFailure>`.
         *
         * Establishes one unique compiler-projected canonical package and declaration identity.
         * [ObservedAddDeclarationDeltaFailure] is the closed expected failure. Raw compiler names
         * and match count may enter only at the result-generation semantic observation boundary.
         */
        fun fromCompilerBoundary(
            packageName: String,
            declarationName: String,
            declarationKind: AddDeclarationKind,
            matchingDeclarationCount: Int,
        ): Refinement<ObservedAddDeclarationDelta, ObservedAddDeclarationDeltaFailure> = when (
            matchingDeclarationCount
        ) {
            in Int.MIN_VALUE until 0 ->
                Refinement.Rejected(ObservedAddDeclarationDeltaFailure.DECLARATION_COUNT_INVALID)
            0 -> Refinement.Rejected(ObservedAddDeclarationDeltaFailure.DECLARATION_MISSING)
            1 -> admitObservedDeclaration(packageName, declarationName, declarationKind)
            else -> Refinement.Rejected(ObservedAddDeclarationDeltaFailure.DECLARATION_AMBIGUOUS)
        }

        private fun admitObservedDeclaration(
            packageName: String,
            declarationName: String,
            declarationKind: AddDeclarationKind,
        ): Refinement<ObservedAddDeclarationDelta, ObservedAddDeclarationDeltaFailure> = when (
            val admitted = ExpectedAddDeclarationDelta.admit(
                packageName,
                declarationName,
                declarationKind,
            )
        ) {
            is Refinement.Refined -> Refinement.Refined(ObservedAddDeclarationDelta(admitted.value))
            is Refinement.Rejected -> Refinement.Rejected(
                when (admitted.failure) {
                    io.github.amichne.kast.change.contract.ExpectedAddDeclarationDeltaFailure.PACKAGE_NAME_INVALID ->
                        ObservedAddDeclarationDeltaFailure.PACKAGE_NAME_INVALID
                    io.github.amichne.kast.change.contract.ExpectedAddDeclarationDeltaFailure.DECLARATION_NAME_INVALID ->
                        ObservedAddDeclarationDeltaFailure.DECLARATION_NAME_INVALID
                },
            )
        }
    }
}

enum class AddDeclarationSemanticDeltaFailure {
    PACKAGE_MISMATCH,
    DECLARATION_NAME_MISMATCH,
    DECLARATION_KIND_MISMATCH,
}

/** Exact accepted semantic delta retaining both expected and observed typed identities. */
class AcceptedAddDeclarationSemanticDelta private constructor(
    val expected: ExpectedAddDeclarationDelta,
    val observed: ObservedAddDeclarationDelta,
) {
    companion object {
        /**
         * Proof transition: `(ExpectedAddDeclarationDelta, ObservedAddDeclarationDelta) ->
         * Refinement<AcceptedAddDeclarationSemanticDelta, AddDeclarationSemanticDeltaFailure>`.
         *
         * Establishes exact package, declaration name, and declaration kind equality.
         * [AddDeclarationSemanticDeltaFailure] is the closed expected failure. No raw extraction is
         * permitted beyond this pure comparator.
         */
        fun compare(
            expected: ExpectedAddDeclarationDelta,
            observed: ObservedAddDeclarationDelta,
        ): Refinement<AcceptedAddDeclarationSemanticDelta, AddDeclarationSemanticDeltaFailure> = when {
            observed.packageName != expected.packageName ->
                Refinement.Rejected(AddDeclarationSemanticDeltaFailure.PACKAGE_MISMATCH)
            observed.declarationName != expected.declarationName ->
                Refinement.Rejected(AddDeclarationSemanticDeltaFailure.DECLARATION_NAME_MISMATCH)
            observed.declarationKind != expected.declarationKind ->
                Refinement.Rejected(AddDeclarationSemanticDeltaFailure.DECLARATION_KIND_MISMATCH)
            else -> Refinement.Refined(AcceptedAddDeclarationSemanticDelta(expected, observed))
        }
    }
}

/** Weaker detached G1 evidence returned by the semantic observation effect boundary. */
data class AddDeclarationVerificationEvidence(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    val relations: List<RelationReadResult>,
    val diagnostics: List<DiagnosticCheckResult>,
    val observedDelta: ObservedAddDeclarationDelta,
)

data class AddDeclarationVerificationObservationRequest(
    val plan: AddDeclarationChangePlan,
    val applied: AppliedUnverified,
    val resulting: DistinctResultingWorkspace,
)

enum class AddDeclarationVerificationObservationRejection {
    RESULTING_SEMANTIC_STATE_UNAVAILABLE,
    RESULTING_GENERATION_MOVED,
    COMPILER_OBSERVATION_REJECTED,
}

sealed interface AddDeclarationVerificationObservation {
    data class Observed(
        val evidence: AddDeclarationVerificationEvidence,
    ) : AddDeclarationVerificationObservation

    data class Rejected(
        val reason: AddDeclarationVerificationObservationRejection,
    ) : AddDeclarationVerificationObservation
}

/** Result-generation compiler observation boundary. */
fun interface AddDeclarationVerificationObserver {
    /**
     * Proof transition: `AddDeclarationVerificationObservationRequest ->
     * AddDeclarationVerificationObservation`.
     *
     * Observed output carries detached source, relation, diagnostic, and declaration identity
     * evidence for G1. Expected compiler-boundary failure is closed by
     * [AddDeclarationVerificationObservationRejection]. Live platform values never escape.
     */
    fun observe(
        request: AddDeclarationVerificationObservationRequest,
    ): AddDeclarationVerificationObservation
}

enum class AddDeclarationProofFailure {
    RESULT_SOURCE_MISMATCH,
    RESULT_SOURCE_CONTENT_MISMATCH,
    RELATION_EVIDENCE_REQUIRED,
    RELATION_EVIDENCE_INCOMPLETE,
    RELATION_LEASE_MISMATCH,
    RELATION_TARGET_MISMATCH,
    RELATION_DELTA_REJECTED,
    DIAGNOSTIC_EVIDENCE_REQUIRED,
    DIAGNOSTIC_EVIDENCE_INCOMPLETE,
    DIAGNOSTIC_LEASE_MISMATCH,
    DIAGNOSTIC_SCOPE_MISMATCH,
    COMPILER_DIAGNOSTICS_REJECTED,
    SEMANTIC_DELTA_REJECTED,
    OBLIGATION_SET_MISMATCH,
}

/** Complete proof from one applied source state through one accepted resulting semantic state. */
class CompleteAddDeclarationVerification private constructor(
    val plan: AddDeclarationChangePlan,
    val applied: AppliedUnverified,
    val resulting: DistinctResultingWorkspace,
    val evidence: AddDeclarationVerificationEvidence,
    val semanticDelta: AcceptedAddDeclarationSemanticDelta,
) {
    companion object {
        /**
         * Proof transition: `(AddDeclarationChangePlan, AppliedUnverified,
         * DistinctResultingWorkspace, AddDeclarationVerificationEvidence) -> Refinement<
         * CompleteAddDeclarationVerification, Set<AddDeclarationProofFailure>>`.
         *
         * Establishes exact resulting source content, complete generation-bound relation and
         * diagnostic coverage, unchanged existing semantic relations, error-free diagnostics,
         * accepted declaration delta, and the exhaustive planned obligation set.
         * [AddDeclarationProofFailure] is the closed expected failure. Raw platform extraction is
         * prohibited; only detached contract evidence enters this pure evaluator.
         */
        fun admit(
            plan: AddDeclarationChangePlan,
            applied: AppliedUnverified,
            resulting: DistinctResultingWorkspace,
            evidence: AddDeclarationVerificationEvidence,
        ): Refinement<CompleteAddDeclarationVerification, Set<AddDeclarationProofFailure>> {
            val failures = linkedSetOf<AddDeclarationProofFailure>()
            if (evidence.source != applied.source) {
                failures += AddDeclarationProofFailure.RESULT_SOURCE_MISMATCH
            }
            if (evidence.content != applied.postimage) {
                failures += AddDeclarationProofFailure.RESULT_SOURCE_CONTENT_MISMATCH
            }
            val completeRelations = evidence.relations.mapNotNull { it as? RelationReadResult.Complete }
            when {
                evidence.relations.isEmpty() ->
                    failures += AddDeclarationProofFailure.RELATION_EVIDENCE_REQUIRED
                completeRelations.size != evidence.relations.size ->
                    failures += AddDeclarationProofFailure.RELATION_EVIDENCE_INCOMPLETE
                else -> {
                    if (completeRelations.any {
                            it.batch.request.selector.lease != resulting.workspace.readLease
                        }
                    ) {
                        failures += AddDeclarationProofFailure.RELATION_LEASE_MISMATCH
                    }
                    if (completeRelations.any { result ->
                            val selector = result.batch.request.selector
                            selector.file != plan.target.selector.file ||
                                selector.name != plan.target.selector.name ||
                                selector.qualifiedIdentity != plan.target.selector.qualifiedIdentity ||
                                selector.kind != plan.target.selector.kind ||
                                selector.scope != plan.target.selector.scope
                        }
                    ) {
                        failures += AddDeclarationProofFailure.RELATION_TARGET_MISMATCH
                    }
                    val planned = plan.evidence.relations.map(::stableRelationBatch).toSet()
                    val observed = completeRelations.map(::stableRelationBatch).toSet()
                    if (
                        planned.size != plan.evidence.relations.size ||
                        observed.size != completeRelations.size ||
                        planned != observed
                    ) {
                        failures += AddDeclarationProofFailure.RELATION_DELTA_REJECTED
                    }
                }
            }
            val completeDiagnostics = evidence.diagnostics.mapNotNull {
                it as? DiagnosticCheckResult.Complete
            }
            when {
                evidence.diagnostics.isEmpty() ->
                    failures += AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_REQUIRED
                completeDiagnostics.size != evidence.diagnostics.size ->
                    failures += AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE
                else -> {
                    if (completeDiagnostics.any {
                            it.batch.scope.lease != resulting.workspace.readLease
                        }
                    ) {
                        failures += AddDeclarationProofFailure.DIAGNOSTIC_LEASE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.scope.files.mapTo(linkedSetOf()) { it.value } !=
                                setOf(applied.source.path.value)
                        }
                    ) {
                        failures += AddDeclarationProofFailure.DIAGNOSTIC_SCOPE_MISMATCH
                    }
                    if (completeDiagnostics.any { result ->
                            result.batch.facts.any { it.severity == DiagnosticSeverity.ERROR }
                        }
                    ) {
                        failures += AddDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED
                    }
                }
            }
            val semanticDelta = AcceptedAddDeclarationSemanticDelta.compare(
                plan.expectedSemanticDelta,
                evidence.observedDelta,
            )
            if (semanticDelta is Refinement.Rejected) {
                failures += AddDeclarationProofFailure.SEMANTIC_DELTA_REJECTED
            }
            if (plan.requiredVerification.obligations != AddDeclarationObligation.entries) {
                failures += AddDeclarationProofFailure.OBLIGATION_SET_MISMATCH
            }
            if (failures.isNotEmpty()) return Refinement.Rejected(failures)
            return when (semanticDelta) {
                is Refinement.Rejected -> Refinement.Rejected(
                    setOf(AddDeclarationProofFailure.SEMANTIC_DELTA_REJECTED),
                )
                is Refinement.Refined -> Refinement.Refined(
                    CompleteAddDeclarationVerification(
                        plan,
                        applied,
                        resulting,
                        evidence,
                        semanticDelta.value,
                    ),
                )
            }
        }
    }
}

private data class StableRelationBatch(
    val meaning: RelationMeaning,
    val facts: Set<StableRelationFact>,
)

private data class StableRelationFact(
    val meaning: RelationMeaning,
    val sourceIdentity: ExactDeclarationQualifiedIdentity,
    val sourceKind: CompilerSymbolKind,
    val sourceFile: SymbolDiscoveryFileIdentity,
    val targetIdentity: ExactDeclarationQualifiedIdentity,
    val targetKind: CompilerSymbolKind,
    val targetFile: SymbolDiscoveryFileIdentity,
    val occurrenceFile: SymbolDiscoveryFileIdentity,
    val occurrenceStart: Int,
    val occurrenceEnd: Int,
    val provenance: io.github.amichne.kast.relation.contract.RelationProvenance,
)

private fun stableRelationBatch(result: RelationReadResult.Complete): StableRelationBatch =
    StableRelationBatch(
        result.batch.request.meaning,
        result.batch.facts.mapTo(linkedSetOf(), ::stableRelationFact),
    )

private fun stableRelationFact(fact: RelationFact): StableRelationFact = StableRelationFact(
    fact.meaning,
    fact.source.qualifiedIdentity,
    fact.source.kind,
    fact.source.file,
    fact.target.qualifiedIdentity,
    fact.target.kind,
    fact.target.file,
    fact.occurrence.file,
    fact.occurrence.range.startInclusive,
    fact.occurrence.range.endExclusive,
    fact.provenance,
)
