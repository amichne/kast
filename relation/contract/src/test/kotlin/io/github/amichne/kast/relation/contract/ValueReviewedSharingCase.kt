package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Assumed compiler facts isolate physical sharing from native analysis and body summaries. */
internal class ValueReviewedSharingCase(val model: ReviewedValueModel) {
    val metadata = ReviewedModelOracleCase()
    val prior = observation(path = ValueAccessPath.Whole)
    val path = metadata.suffix.prepend(ValuePathSegment.AnyElement).flowRefined()
    val fresh = observation(path = path)

    fun observation(
        path: ValueAccessPath = this.path,
        callable: RelationEndpoint = endpoint(),
        artifact: LibraryArtifactObservation = metadata.artifact.copy(),
    ): ValueFlowStep {
        val call = metadata.call(endpoint = callable)
        val source = metadata.receiver(path = path, call = call)
        val binding = ReviewedLibraryBinding.fromCompiler(call, model, artifact).flowRefined()
        val application = application(source, binding)
        return ValueFlowStep.fromCompiler(
                source = source,
                transfers = emptyList(),
                obligations =
                    listOf(
                        ValueFlowObligation(source, ValueFlowUnsupportedCause.EXTERNAL_CALL),
                        ValueFlowObligation(source, ValueFlowUnsupportedCause.OUTSIDE_DOMAIN),
                    ),
                terminal = ValueFlowTerminal.Unresolved,
                domain = request(source),
                examinedWorkUnits = RelationWorkCount.parse(2).flowRefined(),
                applications = listOf(application),
            )
            .flowRefined()
    }

    fun endpoint(): RelationEndpoint =
        when (model) {
            ReviewedValueModel.SingleElementListOf -> error("The receiver-sharing fixture needs a receiver model")
            ReviewedValueModel.FirstOrNull -> metadata.endpoint()
            ReviewedValueModel.Let -> letEndpoint()
        }

    private fun application(source: ValueSite, binding: ReviewedLibraryBinding): ValueModelApplication =
        when (model) {
            ReviewedValueModel.SingleElementListOf -> error("The receiver-sharing fixture needs a receiver model")
            ReviewedValueModel.FirstOrNull ->
                ValueModelApplication.CollectionSelection.selectFirst(source, binding).flowRefined()
            ReviewedValueModel.Let -> {
                val body = callbackOracleOwner(metadata.host, 52, 58)
                val formal =
                    ValueFormalParameter.fromCompilerImplicit(
                            body,
                            ValueArgumentPosition.parse(0).flowRefined(),
                            SymbolDiscoveryCandidateName.parse("it").flowRefined(),
                        )
                        .flowRefined()
                val argument = RelationOccurrence.fromSource(metadata.host.file, body.range)
                val frame = ValueCallFrame.reviewedCallback(binding, argument, formal).flowRefined()
                ValueModelApplication.ScopeCallbackInput.enter(source, frame).flowRefined()
            }
        }

    private fun request(source: ValueSite) =
        RelationRequest.start(
            subject = source.enclosing,
            meaning = RelationMeaning.References,
            budget =
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(4).flowRefined(),
                        WorkUnitLimit.parse(100).flowRefined(),
                        ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                    ),
                    RelationByteLimit.parse(4_000_000).flowRefined(),
                ),
            boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
        )

    private fun letEndpoint(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.let",
                    rawReceiverType = "T",
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("(T)->R"),
                    rawTypeParameterCount = 2,
                )
                .flowRefined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file =
                        SymbolDiscoveryFileIdentity.External(
                            DetachedVirtualFileUrl.parse(ReviewedModelOracleCase.ROOT + "kotlin/StandardKt.class")
                                .flowRefined()
                        ),
                    rawStartInclusive = 100,
                    rawEndExclusive = 200,
                    rawName = "let",
                    rawQualifiedIdentity = "kotlin.let",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .flowRefined()
        return RelationEndpoint.resolve(metadata.host.lease, metadata.callable.scope, evidence).flowRefined()
    }
}
