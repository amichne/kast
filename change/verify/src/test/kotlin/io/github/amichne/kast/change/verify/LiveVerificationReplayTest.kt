package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveVerificationReplayTest {
    private val plan =
        LiveAddDeclarationPlanCodec.decode(
                checkNotNull(javaClass.getResource("/live-add-declaration-plan-v1.json")).readText()
            )
            .refined()
    // Historical selectors can exercise this pure request comparator; no live authority or source effect is created.
    private val anchor = VerifiedMutationFixture().plan.target.selector
    private val scope = plan.verificationScope

    @Test
    fun `every exact complete replay request is accepted`() {
        assertInstanceOf<Refinement.Refined<Unit>>(validateLiveReplayedRequests(scope, anchor, complete()))
    }

    @Test
    fun `dropped or rejected traversal cannot satisfy complete original scope`() {
        assertEquals(
            LiveVerificationFailure.TRAVERSAL_EVIDENCE_REJECTED,
            validateLiveReplayedRequests(scope, anchor, complete().copy(traversals = emptyList())).rejected(),
        )
        assertEquals(
            LiveVerificationFailure.TRAVERSAL_EVIDENCE_REJECTED,
            validateLiveReplayedRequests(
                    scope,
                    anchor,
                    complete()
                        .copy(
                            traversals =
                                listOf(TraversalResult.Rejected(TraversalRejection.RequiredEvidenceUnavailable))
                        ),
                )
                .rejected(),
        )
    }

    @Test
    fun `narrower traversal depth and changed relation search boundary are rejected`() {
        val original = scope.traversals.single()
        val narrowed =
            traversal(
                TraversalPlan.start(
                        anchor,
                        original.meaning,
                        original.budget.copy(depth = TraversalDepthLimit.parse(1).refined()),
                    )
                    .refined()
            )
        assertEquals(
            LiveVerificationFailure.TRAVERSAL_EVIDENCE_REJECTED,
            validateLiveReplayedRequests(scope, anchor, complete().copy(traversals = listOf(narrowed))).rejected(),
        )
        val relation = scope.relations.single()
        val expanded =
            relation(
                RelationRequest.start(
                    selector = anchor,
                    meaning = relation.meaning,
                    budget = relation.budget,
                    boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
            )
        assertEquals(
            LiveVerificationFailure.RELATION_EVIDENCE_REJECTED,
            validateLiveReplayedRequests(scope, anchor, complete().copy(relations = listOf(expanded))).rejected(),
        )
    }

    @Test
    fun `borrowed relation authority and subject are rejected even with identical budgets`() {
        val differentAnchor =
            (VerifiedMutationFixture().completeEvidence().relations.single() as RelationReadResult.Complete)
                .batch
                .request
                .subject as RelationEndpoint.Subject
        val relation = scope.relations.single()
        val borrowed =
            relation(
                RelationRequest.start(
                    selector = differentAnchor.selector,
                    meaning = relation.meaning,
                    budget = relation.budget,
                    boundary = relation.boundary,
                )
            )
        assertEquals(
            LiveVerificationFailure.RELATION_EVIDENCE_REJECTED,
            validateLiveReplayedRequests(scope, anchor, complete().copy(relations = listOf(borrowed))).rejected(),
        )
    }

    @Test
    fun `bounded diagnostic subset cannot discharge a wider original diagnostic scope`() {
        val fixture = VerifiedMutationFixture()
        val observed = fixture.completeEvidence()
        val failures =
            addDeclarationSemanticEvidenceFailures(
                ExpectedAddDeclarationSemanticEvidence(
                    prior =
                        io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                            fixture.plan.target.selector
                        ),
                    scope = fixture.plan.target.selector.scope,
                    authority = fixture.resultingWorkspace.readLease,
                    planned = fixture.plan.evidence,
                    diagnosticScopes = listOf(setOf(fixture.applied.source.path.value, "/workspace/Other.kt")),
                ),
                observed.relations,
                observed.diagnostics,
            )
        org.junit.jupiter.api.Assertions.assertTrue(AddDeclarationProofFailure.DIAGNOSTIC_SCOPE_MISMATCH in failures)
    }

    private fun complete() =
        LiveAddDeclarationReplayedEvidence(
            scope.relations.map { replay ->
                relation(
                    RelationRequest.start(
                        selector = anchor,
                        meaning = replay.meaning,
                        budget = replay.budget,
                        boundary = replay.boundary,
                    )
                )
            },
            scope.traversals.map { traversal(TraversalPlan.start(anchor, it.meaning, it.budget).refined()) },
            emptyList(),
        )

    private fun relation(request: RelationRequest): RelationReadResult.Complete {
        val batch =
            RelationBatch.create(
                    request = request,
                    facts = emptyList(),
                    encodedBytes = RelationByteCount.parse(0).refined(),
                    examinedWorkUnits = RelationWorkCount.parse(0).refined(),
                    resultCount = RelationResultCount.parse(0).refined(),
                )
                .refined()
        val complete = RelationCompilation.complete(batch)
        return RelationReadResult.Complete(complete.batch, complete.coverage)
    }

    private fun traversal(plan: TraversalPlan) =
        TraversalResult.complete(
            TraversalPage.fromBoundary(
                    plan = plan,
                    records = emptyList(),
                    encodedBytes = 0,
                    examinedWorkUnits = 0,
                    elapsedMillis = 0,
                    expandedFrontier = 0,
                )
                .refined()
        )

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private fun <T, F> Refinement<T, F>.rejected(): F = assertInstanceOf<Refinement.Rejected<F>>(this).failure

    private fun LiveAddDeclarationReplayedEvidence.copy(
        relations: List<RelationReadResult> = this.relations,
        traversals: List<TraversalResult> = this.traversals,
        diagnostics: List<io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult> = this.diagnostics,
    ) = LiveAddDeclarationReplayedEvidence(relations, traversals, diagnostics)
}
