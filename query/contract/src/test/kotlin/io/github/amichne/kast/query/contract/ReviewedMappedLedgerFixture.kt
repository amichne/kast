package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ReviewedMappedLedgerFixture {
    val base = ReviewedComponentLedgerFixture()
    private val selected =
        ReviewedLibraryCallable.admit(
                CompilerGroundedSymbolEvidence.fromBoundary(
                        SymbolDiscoveryFileIdentity.External(
                            DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/collections/MapsKt.class")
                                .componentValue()
                        ),
                        1000,
                        1100,
                        "mapValues",
                        "kotlin.collections.mapValues",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function(
                                "kotlin.collections.mapValues",
                                "kotlin/collections/Map<outK,V>",
                                emptyList(),
                                listOf("(kotlin/collections/Map.Entry<K,V>)->R"),
                                3,
                            )
                            .componentValue(),
                    )
                    .componentValue(),
                ValueArgumentPosition.parse(0).componentValue(),
            )
            .componentValue()
    val binding =
        ReviewedCallbackValueBinding.admit(
                base.owner,
                base.base.binding.body,
                base.base.binding.occurrence,
                RelationCallableBody.Named.fromCompiler(base.owner.evidence).componentValue(),
                selected,
                ReviewedLibraryArtifact.admit(
                        selected,
                        DependencyArtifactDigest.parse(
                                "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"
                            )
                            .componentValue(),
                        DependencyArtifactDigest.parse(
                                "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
                            )
                            .componentValue(),
                    )
                    .componentValue(),
            )
            .componentValue()
    val source =
        ValueSite.fromCompiler(
                base.owner,
                componentRange(50, 60),
                ValueRole.Return,
                ValueExecutionOwner.reviewedCallback(binding),
                valuePath =
                    ValueAccessPath.fromSegments(listOf(ValuePathSegment.Element, ValuePathSegment.Element))
                        .componentValue(),
            )
            .componentValue()
    val transfer = ReviewedValueTransfer.callbackResult(source, binding).componentValue()
}
