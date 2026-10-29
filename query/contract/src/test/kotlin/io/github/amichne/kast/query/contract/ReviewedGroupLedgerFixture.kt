package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ReviewedGroupLedgerFixture {
    val components = ReviewedComponentLedgerFixture()
    val base = components.base
    private val owner = components.owner
    val selector = callback(false, 0)
    val transformedSelector = callback(true, 0)
    val transform = callback(true, 1)

    fun original(whole: Boolean = false): ReviewedValueTransfer =
        ReviewedValueTransfer.groupedReceiverElement(receiver(whole), selector).componentValue()

    fun transformed(): ReviewedValueTransfer =
        ReviewedValueTransfer.groupedCallbackResult(returned(transform), transformedSelector, transform)
            .componentValue()

    fun key(): ReviewedValueTransfer =
        ReviewedValueTransfer.groupedKeyResult(returned(selector), selector).componentValue()

    private fun receiver(whole: Boolean): ValueSite {
        val target = RelationEndpoint.resolve(owner.lease, owner.scope, selector.target).componentValue()
        val call = ValueInvocation.fromCompiler(owner, selector.occurrence.range, target).componentValue()
        return ValueSite.fromCompiler(
                owner,
                componentRange(30, 35),
                ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                valuePath =
                    if (whole) ValueAccessPath.Whole
                    else
                        ValueAccessPath.fromSegments(listOf(ValuePathSegment.Element) + components.suffix.segments)
                            .componentValue(),
            )
            .componentValue()
    }

    private fun returned(binding: ReviewedCallbackValueBinding): ValueSite =
        ValueSite.fromCompiler(
                owner,
                componentRange(binding.body.range.startInclusive + 5, binding.body.range.startInclusive + 10),
                ValueRole.Return,
                ValueExecutionOwner.reviewedCallback(binding),
                valuePath = components.suffix,
            )
            .componentValue()

    private fun selected(transformed: Boolean, index: Int) =
        ReviewedLibraryCallable.admit(
                target(transformed),
                ValueArgumentPosition.parse(index).componentValue(),
            )
            .componentValue()

    private fun target(transformed: Boolean): CompilerGroundedSymbolEvidence {
        val signature =
            CanonicalCompilerSignature.function(
                    "kotlin.collections.groupBy",
                    "kotlin/collections/Iterable<T>",
                    emptyList(),
                    if (transformed) listOf("(T)->K", "(T)->V") else listOf("(T)->K"),
                    if (transformed) 3 else 2,
                )
                .componentValue()
        return CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/collections/CollectionsKt.class")
                        .componentValue()
                ),
                1000,
                1100,
                "groupBy",
                "kotlin.collections.groupBy",
                CompilerSymbolKind.FUNCTION,
                signature,
            )
            .componentValue()
    }

    private fun callback(transformed: Boolean, index: Int): ReviewedCallbackValueBinding {
        val selected = selected(transformed, index)
        val range = if (index == 0) componentRange(40, 60) else componentRange(70, 90)
        val body =
            RelationCallableBody.Anonymous.fromCompiler(
                    owner.file,
                    range,
                    CanonicalCompilerSignature.function(
                            RelationCallableBody.Anonymous.sourceIdentity(owner.file, range),
                            null,
                            emptyList(),
                            listOf("kotlin/String"),
                            0,
                        )
                        .componentValue() as CanonicalCompilerSignature.Function,
                )
                .componentValue()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    selected,
                    DependencyArtifactDigest.parse("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
                        .componentValue(),
                    DependencyArtifactDigest.parse("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")
                        .componentValue(),
                )
                .componentValue()
        return ReviewedCallbackValueBinding.admit(
                owner,
                body,
                RelationOccurrence.fromBoundary(owner.file, 30, 100).componentValue(),
                RelationCallableBody.Named.fromCompiler(owner.evidence).componentValue(),
                selected,
                artifact,
            )
            .componentValue()
    }
}
