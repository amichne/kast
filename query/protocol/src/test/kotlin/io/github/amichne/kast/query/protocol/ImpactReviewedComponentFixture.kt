package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedOrdinaryLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
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

internal class ImpactReviewedComponentFixture {
    val base = ReviewedProjectionFixture()
    val owner = base.owner
    private val binary =
        DependencyArtifactDigest.parse("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
            .componentValue()
    private val sources =
        DependencyArtifactDigest.parse("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")
            .componentValue()
    val association = callback("associate", "(T)->kotlin/Pair<K,V>", 3)
    val sorting = callback("sortedBy", "(T)->R?", 2)
    val suffix =
        ValueAccessPath.fromSegments(listOf(ValuePathSegment.Element, ValuePathSegment.Element)).componentValue()

    fun construct(first: Boolean = true, start: Int = 50): ReviewedValueTransfer {
        val selected =
            ReviewedOrdinaryLibraryCallable.admit(
                    external(
                        "kotlin.to",
                        "TuplesKt",
                        CanonicalCompilerSignature.function("kotlin.to", "A", emptyList(), listOf("B"), 2)
                            .componentValue(),
                    )
                )
                .componentValue()
        val target = RelationEndpoint.resolve(owner.lease, owner.scope, selected.target).componentValue()
        val call =
            ValueInvocation.fromCompiler(
                    owner,
                    componentRange(start, start + 20),
                    target,
                    ValueExecutionOwner.reviewedCallback(association),
                )
                .componentValue()
        val binding =
            ReviewedCallValueBinding.admit(
                    call,
                    selected,
                    ReviewedLibraryArtifact.admit(selected, binary, sources).componentValue(),
                )
                .componentValue()
        val role =
            if (first) ValueRole.Receiver(call, ValueReceiverKind.EXTENSION)
            else ValueRole.Argument(call, ValueArgumentPosition.parse(0).componentValue())
        val site =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(start, start + 5),
                    role,
                    call.executionOwner,
                    valuePath = suffix,
                )
                .componentValue()
        return ReviewedValueTransfer.tupleConstruct(site, binding).componentValue()
    }

    fun returned(construction: ReviewedValueTransfer): ValueTransfer {
        val site =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(75, 80),
                    ValueRole.Return,
                    ValueExecutionOwner.reviewedCallback(association),
                    valuePath = construction.target.valuePath,
                )
                .componentValue()
        return ValueTransfer.fromCompiler(construction.target, site, ValueTransferKind.RETURN).componentValue()
    }

    fun associated(construction: ReviewedValueTransfer): ReviewedValueTransfer =
        ReviewedValueTransfer.associatedReturn(returned(construction).target, association).componentValue()

    fun sorted(whole: Boolean): ReviewedValueTransfer {
        val target = RelationEndpoint.resolve(owner.lease, owner.scope, sorting.target).componentValue()
        val call = ValueInvocation.fromCompiler(owner, sorting.occurrence.range, target).componentValue()
        val site =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(30, 35),
                    ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                    valuePath = if (whole) ValueAccessPath.Whole else suffix,
                )
                .componentValue()
        return ReviewedValueTransfer.sortedResult(site, sorting).componentValue()
    }

    fun callback(name: String, parameter: String, count: Int): ReviewedCallbackValueBinding {
        val selected =
            ReviewedLibraryCallable.admit(
                    external(
                        "kotlin.collections.$name",
                        "collections/CollectionsKt",
                        CanonicalCompilerSignature.function(
                                "kotlin.collections.$name",
                                "kotlin/collections/Iterable<T>",
                                emptyList(),
                                listOf(parameter),
                                count,
                            )
                            .componentValue(),
                    ),
                    ValueArgumentPosition.parse(0).componentValue(),
                )
                .componentValue()
        val range = componentRange(40, 90)
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
        return ReviewedCallbackValueBinding.admit(
                owner,
                body,
                RelationOccurrence.fromBoundary(owner.file, 30, 100).componentValue(),
                RelationCallableBody.Named.fromCompiler(owner.evidence).componentValue(),
                selected,
                ReviewedLibraryArtifact.admit(selected, binary, sources).componentValue(),
            )
            .componentValue()
    }

    private fun external(
        name: String,
        file: String,
        signature: CanonicalCompilerSignature,
    ): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/$file.class").componentValue()
                ),
                1000,
                1100,
                name.substringAfterLast('.'),
                name,
                CompilerSymbolKind.FUNCTION,
                signature,
            )
            .componentValue()
}

internal fun componentRange(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).componentValue()

internal fun <V, F> Refinement<V, F>.componentValue(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid reviewed component fixture: $failure")
    }
