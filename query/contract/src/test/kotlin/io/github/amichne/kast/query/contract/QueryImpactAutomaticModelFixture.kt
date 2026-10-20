package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactObservation
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
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
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** Assumed compiler metadata supplies core proofs to ledger laws without native reads or supplied models. */
internal class QueryImpactAutomaticModelFixture {
    val base = QueryImpactLedgerFixture()
    val invocation = ValueInvocation.fromCompiler(base.owner, exact(70, 90), library()).modelRefined()
    val receiver =
        ValueSite.fromCompiler(
                base.owner,
                exact(72, 78),
                ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
            )
            .modelRefined()
    val artifact =
        LibraryArtifactObservation(
            base.lease.identity,
            DetachedVirtualFileUrl.parse(ROOT).modelRefined(),
            LibraryModelModuleName.parse("packet-stdlib").modelRefined(),
            LibraryArtifactDigest.parse(BINARY_SHA).modelRefined(),
            LibraryArtifactVersion.parse(VERSION).modelRefined(),
            LibraryModelDeclarationOrigin.LIBRARY,
        )
    val application = application(artifact)
    val intoReceiver = ValueTransfer.fromCompiler(base.producer, receiver, ValueTransferKind.RECEIVER).modelRefined()
    val nativeSibling = ValueTransfer.fromCompiler(receiver, base.first, ValueTransferKind.LOCAL_BINDING).modelRefined()
    val receiverObligation = ValueFlowObligation(receiver, ValueFlowUnsupportedCause.EXTERNAL_CALL)
    val resultObservation = observe(application.target)
    val nativeObservation = observe(base.first)
    val appPath =
        path(
            listOf(QueryImpactStep.Compiler(intoReceiver), QueryImpactStep.ModeledValue(application)),
            QueryImpactTerminal.SupportedDomainEnd.admit(resultObservation).modelRefined(),
        )
    val nativePath =
        path(
            listOf(QueryImpactStep.Compiler(intoReceiver), QueryImpactStep.Compiler(nativeSibling)),
            QueryImpactTerminal.SupportedDomainEnd.admit(nativeObservation).modelRefined(),
        )
    val obligationPath =
        path(
            listOf(QueryImpactStep.Compiler(intoReceiver)),
            QueryImpactTerminal.Unresolved.Flow(receiverObligation),
        )

    fun application(artifact: LibraryArtifactObservation) =
        ValueModelApplication.CollectionSelection.selectFirst(
                receiver,
                ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.FirstOrNull, artifact)
                    .modelRefined(),
            )
            .modelRefined()

    fun observedReceiver(
        applications: List<ValueModelApplication> = listOf(application),
        edges: List<ValueTransfer> = listOf(nativeSibling),
        obligations: List<ValueFlowObligation> = listOf(receiverObligation),
    ) = observe(receiver, edges, obligations, applications)

    fun ledger(
        paths: List<QueryImpactPath> = listOf(appPath, nativePath, obligationPath),
        receiverObservation: ValueFlowStep = observedReceiver(),
    ) =
        QueryImpactLedger.fromEvidence(
            seeds = listOf(base.producer),
            domain = base.domain.boundary,
            semantics = QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            representationModels = emptyList(),
            boundaryModels = emptyList(),
            observations =
                listOf(
                    observe(base.producer, listOf(intoReceiver)),
                    receiverObservation,
                    resultObservation,
                    nativeObservation,
                ),
            paths = paths,
            originalProducers = listOf(base.producerWitness),
        )

    fun observe(
        source: ValueSite,
        edges: List<ValueTransfer> = emptyList(),
        obligations: List<ValueFlowObligation> = emptyList(),
        applications: List<ValueModelApplication> = emptyList(),
    ) =
        ValueFlowStep.fromCompiler(
                source = source,
                transfers = edges,
                obligations = obligations,
                terminal =
                    if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted
                    else ValueFlowTerminal.Unresolved,
                domain =
                    RelationRequest.start(
                        source.enclosing,
                        RelationMeaning.References,
                        RelationBudget(base.domain.budget.resources, RelationByteLimit.parse(4_000_000).modelRefined()),
                        base.domain.boundary,
                    ),
                examinedWorkUnits = RelationWorkCount.parse(1).modelRefined(),
                applications = applications,
            )
            .modelRefined()

    fun path(steps: List<QueryImpactStep>, terminal: QueryImpactTerminal) =
        QueryImpactPath.fromEvidence(base.producer, steps, QueryImpactRepresentation.NotModeled, terminal)
            .modelRefined()

    private fun library(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function(
                    "kotlin.collections.firstOrNull",
                    "kotlin/collections/List<T>",
                    emptyList(),
                    emptyList(),
                    1,
                )
                .modelRefined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse(ROOT + "kotlin/collections/CollectionsKt.class").modelRefined()
                    ),
                    22846,
                    22929,
                    "firstOrNull",
                    "kotlin.collections.firstOrNull",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .modelRefined()
        return RelationEndpoint.resolve(
                base.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                evidence,
            )
            .modelRefined()
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).modelRefined()

    companion object {
        const val ROOT = "jar:///fixture/kotlin-stdlib.jar!/"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val SOURCE_SHA = "5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"
        const val VERSION = "2.3.20-release-208"
    }
}

internal fun <V, F> Refinement<V, F>.modelRefined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Automatic model fixture prerequisite rejected: $failure")
    }
