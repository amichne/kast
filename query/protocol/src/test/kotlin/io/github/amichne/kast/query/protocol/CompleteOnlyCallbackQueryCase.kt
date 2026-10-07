package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Explicit compiler facts for strict policy tests; does not execute native resolution. */
internal abstract class CompleteOnlyCallbackQueryCase : AutomaticSymbolQueryCase() {
    protected val strict
        get() =
            request.copy(
                completion =
                    QueryCompletionPolicyDocument.CompleteOnly(QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1)
            )

    protected fun walkObservation(): io.github.amichne.kast.query.contract.QueryWalkObservation {
        val relation = observation(false).callbackObservations.single()
        val bounds =
            io.github.amichne.kast.traversal.contract.TraversalBudget(
                io.github.amichne.kast.kernel.ResultLimit.parse(100).refined(),
                io.github.amichne.kast.traversal.contract.TraversalByteLimit.parse(100_000).refined(),
                budget.resources.workUnitLimit,
                budget.resources.elapsedTimeLimit,
                io.github.amichne.kast.traversal.contract.TraversalDepthLimit.parse(2).refined(),
                io.github.amichne.kast.traversal.contract.TraversalFrontierLimit.parse(100).refined(),
                RelationPagingFixture.published().budget,
            )
        val plan =
            io.github.amichne.kast.traversal.contract.TraversalPlan.start(
                    fixture.selector,
                    RelationMeaning.Callees,
                    bounds,
                )
                .refined()
        val entry =
            io.github.amichne.kast.traversal.contract.TraversalFrontierEntry.create(
                    plan,
                    io.github.amichne.kast.traversal.contract.TraversalNode.start(fixture.selector),
                    io.github.amichne.kast.traversal.contract.TraversalDepth.Zero,
                )
                .refined()
        val callback =
            io.github.amichne.kast.traversal.contract.TraversalCallbackObservation.create(plan, entry, relation)
                .refined()
        val page =
            io.github.amichne.kast.traversal.contract.TraversalPage.fromBoundary(
                    plan,
                    emptyList(),
                    relation.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong(),
                    1L,
                    0L,
                    1,
                    io.github.amichne.kast.traversal.contract.TraversalProgress.restore(1L, 1L, 0L, 0).refined(),
                    callbackObservations = listOf(callback),
                )
                .refined()
        return io.github.amichne.kast.query.contract.QueryWalkObservation.from(
            io.github.amichne.kast.traversal.contract.TraversalResult.complete(page)
                as io.github.amichne.kast.traversal.contract.TraversalResult.Complete
        )
    }

    protected fun observation(
        supported: Boolean,
        obligations: Set<CallbackInvocationFlowCause> = emptySet(),
    ): QueryRelationObservation {
        val relation =
            RelationRequest.start(fixture.selector, RelationMeaning.Callees, RelationPagingFixture.published().budget)
        val owner = CompilerGroundedSymbolEvidence.fromSelector(fixture.selector)
        val callback =
            RelationCallbackObservation.fromNativeBoundary(
                    relation,
                    occurrence(2, 3),
                    owner,
                    owner,
                    occurrence(1, 4),
                    CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, occurrence(1, 4)),
                    if (supported) boundFlow(obligations)
                    else CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                )
                .refined()
        val batch =
            RelationBatch.create(
                    relation,
                    emptyList(),
                    RelationByteCount.parse(callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(0).refined(),
                    callbackObservations = listOf(callback),
                )
                .refined()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }

    protected fun boundFlow(obligations: Set<CallbackInvocationFlowCause> = emptySet()): CallbackInvocationFlowRead {
        val caller =
            RelationEndpoint.resolve(
                    fixture.authority,
                    fixture.selector.scope,
                    CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                )
                .refined()
        val evidence = wrapperEvidence()
        val wrapper = RelationEndpoint.resolve(fixture.authority, fixture.selector.scope, evidence).refined()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(caller, range(0, 5), wrapper).refined(),
                    RelationCallableBody.Named.fromCompiler(
                            CompilerGroundedSymbolEvidence.fromSelector(fixture.selector)
                        )
                        .refined(),
                    ValueArgumentPosition.parse(0).refined(),
                    occurrence(110, 120),
                )
                .refined()
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(fixture.selector.file, range(1, 4)),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(fixture.selector.file, range(1, 4), signature).refined()
        val terminal =
            CallbackParameterInvocation.fromCompiler(
                    occurrence(160, 170),
                    RelationCallableBody.Named.fromCompiler(evidence).refined(),
                )
                .refined()
        return CallbackInvocationFlowRead.Observed(
            CallbackInvocationFlow.fromCompiler(
                    fixture.authority.identity,
                    body,
                    CallbackBindingEvidence.Bound(binding),
                    listOf(terminal),
                    obligations,
                    if (obligations.isEmpty()) CallbackInvocationScan.EXHAUSTIVE else CallbackInvocationScan.INCOMPLETE,
                )
                .refined()
        )
    }

    protected fun wrapperEvidence(): CompilerGroundedSymbolEvidence {
        return CompilerGroundedSymbolEvidence.fromBoundary(
                fixture.selector.file,
                100,
                200,
                "wrapper",
                "sample.wrapper",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("sample.wrapper", null, emptyList(), listOf("()->Unit"), 0)
                    .refined(),
            )
            .refined()
    }

    protected fun occurrence(start: Int, end: Int) =
        RelationOccurrence.fromBoundary(fixture.selector.file, start, end).refined()

    protected fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()
}
