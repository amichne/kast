package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.DurableAddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationVerificationScope
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.contract.LivePlannedRelationRead
import io.github.amichne.kast.change.contract.LivePlannedTraversal
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.file.Path

/** Narrow semantic services bound by the caller to one current original-owner read context. */
data class LiveAddDeclarationVerificationPorts(
    val relations: RelationOperations,
    val traversals: TraversalOperations,
    val diagnostics: DiagnosticOperations,
)

enum class LiveVerificationFailure {
    ORIGINAL_OWNER_MISMATCH,
    MODEL_MOVED,
    CURRENT_STATE_UNAVAILABLE,
    SOURCE_POSTIMAGE_MISMATCH,
    COMPILER_OBSERVATION_REJECTED,
    RELATION_EVIDENCE_REJECTED,
    TRAVERSAL_EVIDENCE_REJECTED,
    DIAGNOSTIC_EVIDENCE_REJECTED,
    SEMANTIC_DELTA_REJECTED,
}

class LiveAddDeclarationReplayedEvidence(
    relations: List<RelationReadResult>,
    traversals: List<TraversalResult>,
    diagnostics: List<DiagnosticCheckResult>,
) {
    val relations: List<RelationReadResult> = relations.toList()
    val traversals: List<TraversalResult> = traversals.toList()
    val diagnostics: List<DiagnosticCheckResult> = diagnostics.toList()
}

/** Runs every retained request with its original budget and scope, under the current compiler anchor. */
object LiveAddDeclarationVerificationReplay {
    suspend fun observe(
        plan: LiveAddDeclarationChangePlan,
        anchor: SymbolSelector,
        ports: LiveAddDeclarationVerificationPorts,
    ): Refinement<LiveAddDeclarationReplayedEvidence, LiveVerificationFailure> {
        val original = plan.basis.observation.reference
        val live =
            anchor.lease as? LiveSemanticReadAuthority
                ?: return Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
        if (live.workspaceRoot != original.workspaceRoot || live.reference.host != original.host) {
            return Refinement.Rejected(LiveVerificationFailure.ORIGINAL_OWNER_MISMATCH)
        }
        if (
            anchor.scope != plan.target.scope ||
                anchor.constraints != plan.target.constraints ||
                CompilerReobservedMutationAnchor.admit(
                    plan.target.evidence,
                    CompilerGroundedSymbolEvidence.fromSelector(anchor),
                ) is Refinement.Rejected
        ) {
            return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
        }
        val relations = observeRelations(plan, anchor, ports.relations)
        val traversals =
            plan.verificationScope.traversals.map { replay ->
                val request =
                    when (val admitted = TraversalPlan.start(anchor, replay.meaning, replay.budget)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(LiveVerificationFailure.TRAVERSAL_EVIDENCE_REJECTED)
                    }
                ports.traversals.run(request)
            }
        val diagnostics =
            plan.verificationScope.diagnostics.map { replay ->
                val scope =
                    when (
                        val admitted =
                            DiagnosticScope.fromCanonicalPaths(anchor.lease, replay.files.map { Path.of(it.value) })
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED)
                    }
                ports.diagnostics.check(DiagnosticCheckRequest(scope))
            }
        return Refinement.Refined(LiveAddDeclarationReplayedEvidence(relations, traversals, diagnostics))
    }

    private suspend fun observeRelations(
        plan: LiveAddDeclarationChangePlan,
        anchor: SymbolSelector,
        operations: RelationOperations,
    ): List<RelationReadResult> =
        plan.verificationScope.relations.map { read ->
            operations.read(
                RelationRequest.start(
                    selector = anchor,
                    meaning = read.meaning,
                    budget = read.budget,
                    boundary = read.boundary,
                )
            )
        }
}

/** Fresh source and semantic observations obtained under one current native read authority. */
data class LiveAddDeclarationVerificationInput(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val expectedPostimage: WorkspaceSourceContentHash,
    val observedSource: ObservedMutationSource,
    val semantic: HostedAddDeclarationSemanticEvidence,
    val evidence: LiveAddDeclarationReplayedEvidence,
)

private data class LiveVerifiedSemanticEvidence(
    val anchor: CompilerReobservedMutationAnchor,
    val delta: AcceptedAddDeclarationSemanticDelta,
    val replayed: LiveAddDeclarationReplayedEvidence,
    val historical: DurableAddDeclarationPlanningEvidence,
)

/** Current saved/committed post-state proof. Pre-write obligations remain owned by the applied-write proof. */
class CompleteLiveAddDeclarationVerification
private constructor(
    val plan: LiveAddDeclarationChangePlan,
    val resulting: LiveChangeBasis,
    val postimage: WorkspaceSourceContentHash,
    private val semantics: LiveVerifiedSemanticEvidence,
) {
    val anchor: CompilerReobservedMutationAnchor
        get() = semantics.anchor

    val semanticDelta: AcceptedAddDeclarationSemanticDelta
        get() = semantics.delta

    val evidence: LiveAddDeclarationReplayedEvidence
        get() = semantics.replayed

    val historicalEvidence: DurableAddDeclarationPlanningEvidence
        get() = semantics.historical

    companion object {
        fun admit(
            plan: LiveAddDeclarationChangePlan,
            input: LiveAddDeclarationVerificationInput,
        ): Refinement<CompleteLiveAddDeclarationVerification, LiveVerificationFailure> {
            val resulting =
                when (val admitted = currentBasis(plan, input)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val anchor =
                when (val admitted = currentAnchor(plan, input)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val historical =
                when (val admitted = completeEvidence(plan, input)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val delta =
                when (
                    val admitted =
                        AcceptedAddDeclarationSemanticDelta.compare(plan.expectedSemanticDelta, input.semantic.delta)
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveVerificationFailure.SEMANTIC_DELTA_REJECTED)
                }
            return Refinement.Refined(
                CompleteLiveAddDeclarationVerification(
                    plan = plan,
                    resulting = resulting,
                    postimage = input.observedSource.content,
                    semantics =
                        LiveVerifiedSemanticEvidence(
                            anchor = anchor,
                            delta = delta,
                            replayed = input.evidence,
                            historical = historical,
                        ),
                )
            )
        }
    }
}

private fun currentBasis(
    plan: LiveAddDeclarationChangePlan,
    input: LiveAddDeclarationVerificationInput,
): Refinement<LiveChangeBasis, LiveVerificationFailure> {
    val original = plan.basis.observation
    val authority = input.authority
    if (
        authority.reference.host != original.reference.host ||
            authority.workspaceRoot != original.reference.workspaceRoot ||
            authority.reference.version != LiveSemanticReadReference.VERSION
    ) {
        return Refinement.Rejected(LiveVerificationFailure.ORIGINAL_OWNER_MISMATCH)
    }
    if (
        input.model.workspaceRoot != original.model.workspaceRoot ||
            input.model.sourceRoots != original.model.sourceRoots
    ) {
        return Refinement.Rejected(LiveVerificationFailure.MODEL_MOVED)
    }
    if (
        authority.reference.contentView != IdeReadContentView.SAVED_PSI_COMMITTED ||
            input.semantic.anchor.lease !== authority
    ) {
        return Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
    }
    if (input.observedSource.source != plan.target.file || input.observedSource.content != input.expectedPostimage) {
        return Refinement.Rejected(LiveVerificationFailure.SOURCE_POSTIMAGE_MISMATCH)
    }
    return when (val basis = LiveChangeBasis.observe(authority.reference, input.model)) {
        is Refinement.Refined -> basis
        is Refinement.Rejected -> Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
    }
}

private fun currentAnchor(
    plan: LiveAddDeclarationChangePlan,
    input: LiveAddDeclarationVerificationInput,
): Refinement<CompilerReobservedMutationAnchor, LiveVerificationFailure> {
    val anchor = input.semantic.anchor
    if (anchor.scope != plan.target.scope || anchor.constraints != plan.target.constraints) {
        return Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
    }
    return when (
        val admitted =
            CompilerReobservedMutationAnchor.admit(
                plan.target.evidence,
                CompilerGroundedSymbolEvidence.fromSelector(anchor),
            )
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(LiveVerificationFailure.COMPILER_OBSERVATION_REJECTED)
    }
}

private fun completeEvidence(
    plan: LiveAddDeclarationChangePlan,
    input: LiveAddDeclarationVerificationInput,
): Refinement<DurableAddDeclarationPlanningEvidence, LiveVerificationFailure> {
    val evidence = input.evidence
    val expected =
        ExpectedAddDeclarationSemanticEvidence(
            prior = plan.target.evidence,
            scope = plan.target.scope,
            authority = input.authority,
            planned = plan.evidence,
            diagnosticScopes =
                plan.verificationScope.diagnostics.map { it.files.mapTo(linkedSetOf()) { file -> file.value } },
        )
    val failures = addDeclarationSemanticEvidenceFailures(expected, evidence.relations, evidence.diagnostics)
    if (failures.isNotEmpty()) return Refinement.Rejected(failures.first().liveFailure())
    when (val replay = validateLiveReplayedRequests(plan.verificationScope, input.semantic.anchor, evidence)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return replay
    }
    return when (
        val captured =
            DurableAddDeclarationPlanningEvidence.capture(
                input.semantic.anchor,
                plan.target.file,
                AddDeclarationPlanningEvidenceInput(evidence.relations, evidence.traversals, evidence.diagnostics),
            )
    ) {
        is Refinement.Refined -> captured
        is Refinement.Rejected -> Refinement.Rejected(LiveVerificationFailure.CURRENT_STATE_UNAVAILABLE)
    }
}

private fun AddDeclarationProofFailure.liveFailure(): LiveVerificationFailure =
    when (this) {
        AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_REQUIRED,
        AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE,
        AddDeclarationProofFailure.DIAGNOSTIC_LEASE_MISMATCH,
        AddDeclarationProofFailure.DIAGNOSTIC_SCOPE_MISMATCH,
        AddDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED -> LiveVerificationFailure.DIAGNOSTIC_EVIDENCE_REJECTED
        else -> LiveVerificationFailure.RELATION_EVIDENCE_REJECTED
    }

/** Pure exact-request comparator; accepting historical selectors here never admits an effect. */
internal fun validateLiveReplayedRequests(
    scope: LiveAddDeclarationVerificationScope,
    anchor: SymbolSelector,
    evidence: LiveAddDeclarationReplayedEvidence,
): Refinement<Unit, LiveVerificationFailure> {
    if (
        evidence.relations.size != scope.relations.size ||
            evidence.relations.zip(scope.relations).any { (actual, expected) ->
                !matchesRelationReplay(expected, anchor, actual)
            }
    )
        return Refinement.Rejected(LiveVerificationFailure.RELATION_EVIDENCE_REJECTED)
    if (
        evidence.traversals.size != scope.traversals.size ||
            evidence.traversals.zip(scope.traversals).any { (actual, expected) ->
                !matchesTraversalReplay(expected, anchor, actual)
            }
    )
        return Refinement.Rejected(LiveVerificationFailure.TRAVERSAL_EVIDENCE_REJECTED)
    return Refinement.Refined(Unit)
}

private fun matchesRelationReplay(
    expected: LivePlannedRelationRead,
    anchor: SymbolSelector,
    result: RelationReadResult,
): Boolean {
    if (result !is RelationReadResult.Complete) return false
    val request = result.batch.request
    if (
        request.meaning != expected.meaning ||
            request.budget != expected.budget ||
            request.boundary != expected.boundary
    )
        return false
    return request.position == RelationReadPosition.Start &&
        request.subject.fingerprint == RelationEndpoint.subject(anchor).fingerprint
}

private fun matchesTraversalReplay(
    expected: LivePlannedTraversal,
    anchor: SymbolSelector,
    result: TraversalResult,
): Boolean {
    if (result !is TraversalResult.Complete) return false
    val request = result.page.plan
    if (request.start != anchor || request.meaning != expected.meaning) return false
    return request.budget == expected.budget && request.position == TraversalPosition.Start
}
