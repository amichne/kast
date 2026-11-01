package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal enum class LedgerScopeCallable(val callable: String, val parameter: String, val typeParameters: Int) {
    LET("let", "(T)->R", 2),
    ALSO("also", "(T)->kotlin/Unit", 1),
}

/** Pure detached evidence exercises the existing ledger and its native observation inventory. */
internal class QueryImpactScopeFixture(private val callable: LedgerScopeCallable) {
    private val base = QueryImpactLedgerFixture()
    private val owner = base.owner
    val domain = base.domain
    private val target = target()
    private val bodyRange = scopeRange(40, 80)
    private val body =
        RelationCallableBody.Anonymous.fromCompiler(
                owner.file,
                bodyRange,
                CanonicalCompilerSignature.function(
                        RelationCallableBody.Anonymous.sourceIdentity(owner.file, bodyRange),
                        null,
                        emptyList(),
                        listOf("T"),
                        0,
                    )
                    .scopeEvidence() as CanonicalCompilerSignature.Function,
            )
            .scopeEvidence()
    private val occurrence = RelationOccurrence.fromBoundary(owner.file, 20, 100).scopeEvidence()
    private val supplier = RelationCallableBody.Named.fromCompiler(owner.evidence).scopeEvidence()
    private val position = ValueArgumentPosition.parse(0).scopeEvidence()
    private val selected = ReviewedLibraryCallable.admit(target, position).scopeEvidence()
    private val artifact =
        ReviewedLibraryArtifact.admit(
                selected,
                DependencyArtifactDigest.parse("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
                    .scopeEvidence(),
                DependencyArtifactDigest.parse("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")
                    .scopeEvidence(),
            )
            .scopeEvidence()
    val reviewed =
        ReviewedCallbackValueBinding.admit(owner, body, occurrence, supplier, selected, artifact).scopeEvidence()
    val declared =
        DeclaredCallbackValueBinding.admit(
                owner,
                body,
                CallbackDependencyContract.fromCompiler(
                        owner.lease.identity,
                        occurrence,
                        supplier,
                        target,
                        position,
                        DependencyClassDigest.parse("f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05")
                            .scopeEvidence(),
                        CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                        CallbackDependencyInvocationKind.EXACTLY_ONCE,
                    )
                    .scopeEvidence(),
                DeclaredCallbackResultEffect.UNSPECIFIED,
            )
            .scopeEvidence()
    val source = source()
    val edge =
        when (callable) {
            LedgerScopeCallable.LET -> ReviewedValueTransfer.callbackResult(source, reviewed)
            LedgerScopeCallable.ALSO -> ReviewedValueTransfer.receiverResult(source, reviewed, declared)
        }.scopeEvidence()
    private val returned =
        ValueSite.fromCompiler(
                owner,
                edge.target.range,
                ValueRole.Return,
                valuePath = edge.target.valuePath,
                conditions = edge.target.conditions,
                callStack = edge.target.callStack,
            )
            .scopeEvidence()
    val compiler = ValueTransfer.fromCompiler(edge.target, returned, ValueTransferKind.RETURN).scopeEvidence()
    val observations =
        listOf(
            observe(source, reviewed = listOf(edge)),
            observe(edge.target, compiler = listOf(compiler)),
            observe(returned),
        )
    val path =
        QueryImpactPath.fromEvidence(
                source,
                listOf(QueryImpactStep.ReviewedLibrary(edge), QueryImpactStep.Compiler(compiler)),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).scopeEvidence(),
            )
            .scopeEvidence()

    fun observe(
        source: ValueSite,
        reviewed: List<ReviewedValueTransfer> = emptyList(),
        obligations: List<ValueFlowObligation> = emptyList(),
        compiler: List<ValueTransfer> = emptyList(),
    ): ValueFlowStep =
        ValueFlowStep.fromEvidence(
                source,
                compiler,
                obligations,
                if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted else ValueFlowTerminal.Unresolved,
                domain,
                RelationWorkCount.parse(1).scopeEvidence(),
                reviewedTransfers = reviewed,
            )
            .scopeEvidence()

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep> = this.observations) =
        QueryImpactLedger.fromEvidence(
            listOf(source),
            domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observations,
            paths,
        )

    private fun source(): ValueSite =
        when (callable) {
            LedgerScopeCallable.LET ->
                ValueSite.fromCompiler(
                    owner,
                    scopeRange(50, 60),
                    ValueRole.Return,
                    ValueExecutionOwner.declaredCallback(declared),
                    valuePath =
                        ValueAccessPath.fromSegments(listOf(ValuePathSegment.Element, ValuePathSegment.Element))
                            .scopeEvidence(),
                )
            LedgerScopeCallable.ALSO ->
                ValueSite.fromCompiler(
                    owner,
                    scopeRange(20, 30),
                    ValueRole.Receiver(
                        ValueInvocation.fromCompiler(
                                owner,
                                occurrence.range,
                                RelationEndpoint.resolve(owner.lease, owner.scope, target).scopeEvidence(),
                            )
                            .scopeEvidence(),
                        ValueReceiverKind.EXTENSION,
                    ),
                )
        }.scopeEvidence()

    private fun target(): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/StandardKt.class").scopeEvidence()
                ),
                1000,
                1100,
                callable.callable,
                "kotlin.${callable.callable}",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                        "kotlin.${callable.callable}",
                        "T",
                        emptyList(),
                        listOf(callable.parameter),
                        callable.typeParameters,
                    )
                    .scopeEvidence(),
            )
            .scopeEvidence()
}

private fun scopeRange(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).scopeEvidence()

internal fun <V, F> Refinement<V, F>.scopeEvidence(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Scope ledger fixture rejected: $failure")
    }
