package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedDirectFormalKey
import io.github.amichne.kast.relation.contract.ReviewedDistinctStringKeys
import io.github.amichne.kast.relation.contract.ReviewedStringListArtifact
import io.github.amichne.kast.relation.contract.ReviewedStringListInput
import io.github.amichne.kast.relation.contract.ReviewedStringVarargArgument
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ReviewedVarargFormal
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ReviewedDistinctLedgerFixture(value: String = " value ") {
    val base = ReviewedComponentLedgerFixture()
    private val construction = base.construct()
    private val origin = (construction.target.valuePath.segments.first() as ValuePathSegment.PairComponent).origin
    private val formal =
        ValueFormalParameter.fromCompiler(
                base.owner,
                ValueArgumentPosition.parse(0).componentValue(),
                SymbolDiscoveryCandidateName.parse("item").componentValue(),
                componentRange(41, 45),
                ValueExecutionOwner.reviewedCallback(base.association),
            )
            .componentValue()
    private val keyUse =
        ValueSite.fromCompiler(
                base.owner,
                construction.source.range,
                construction.source.role,
                construction.source.executionOwner,
            )
            .componentValue()
    val key =
        ReviewedDirectFormalKey.fromCompiler(
                base.association,
                formal,
                origin,
                keyUse,
                listOf(origin.binding.invocation.range),
            )
            .componentValue()
    private val target = listTarget()
    private val listInvocation =
        ValueInvocation.fromCompiler(
                base.owner,
                componentRange(30, 35),
                RelationEndpoint.resolve(base.owner.lease, base.owner.scope, target).componentValue(),
            )
            .componentValue()
    private val listFormal =
        ReviewedVarargFormal.fromCompiler(listInvocation, target, ValueArgumentPosition.parse(0).componentValue())
            .componentValue()
    private val argumentRanges = listOf(componentRange(31, 32), componentRange(33, 34))
    val input =
        ReviewedStringListInput.fromCompiler(
                listFormal,
                ReviewedStringListArtifact.admit(
                        listFormal,
                        base.association.artifact.binaryArtifact,
                        base.association.artifact.sourceArtifact,
                    )
                    .componentValue(),
                2,
                argumentRanges,
                listOf("", value).mapIndexed { index, value ->
                    ReviewedStringVarargArgument.fromCompiler(
                            listFormal,
                            ValueArgumentPosition.parse(index).componentValue(),
                            argumentRanges[index],
                            value,
                        )
                        .componentValue()
                },
            )
            .componentValue()
    private val associationInvocation =
        ValueInvocation.fromCompiler(
                base.owner,
                base.association.occurrence.range,
                RelationEndpoint.resolve(base.owner.lease, base.owner.scope, base.association.target).componentValue(),
            )
            .componentValue()
    private val receiver =
        ValueSite.fromCompiler(
                base.owner,
                listInvocation.range,
                ValueRole.Receiver(associationInvocation, ValueReceiverKind.EXTENSION),
            )
            .componentValue()
    val proof = ReviewedDistinctStringKeys.admit(input, key, receiver).componentValue()
    val source =
        ValueSite.fromCompiler(
                base.owner,
                key.returnRange,
                ValueRole.Return,
                ValueExecutionOwner.reviewedCallback(base.association),
                valuePath = construction.target.valuePath,
            )
            .componentValue()
    val conditional = ReviewedValueTransfer.associatedReturn(source, base.association).componentValue()
    val distinct = ReviewedValueTransfer.associatedReturn(source, base.association, proof).componentValue()

    private fun listTarget(): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/collections/CollectionsKt.class")
                        .componentValue()
                ),
                1000,
                1100,
                "listOf",
                "kotlin.collections.listOf",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("kotlin.collections.listOf", null, emptyList(), listOf("T"), 1)
                    .componentValue(),
            )
            .componentValue()
}
