package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactObservation
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueElementIndex
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Assumed compiler metadata supplies a real literal proof without a fabricated library-body invocation. */
internal class ReviewedScopeProjectionCase {
    private val callback = ImpactCallbackProjectionCase()
    val references = callback.references
    val formal = ((callback.formal.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
    val host = formal.callable
    val invocation = ValueInvocation.fromCompiler(host, range(30, 100), library()).modelValue()
    private val artifact =
        LibraryArtifactObservation(
            host.lease.identity,
            DetachedVirtualFileUrl.parse(ImpactValueModelProjectionCase.ROOT).modelValue(),
            LibraryModelModuleName.parse("packet-stdlib").modelValue(),
            LibraryArtifactDigest.parse(ImpactValueModelProjectionCase.BINARY_SHA).modelValue(),
            LibraryArtifactVersion.parse(ImpactValueModelProjectionCase.VERSION).modelValue(),
            LibraryModelDeclarationOrigin.LIBRARY,
        )
    val binding = ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.Let, artifact).modelValue()
    val argument = RelationOccurrence.fromSource(host.file, formal.executionOwner.range)
    val frame = ValueCallFrame.reviewedCallback(binding, argument, formal).modelValue()
    private val receiver =
        ValueSite.fromCompiler(
                host,
                range(31, 38),
                ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
                valuePath =
                    ValueAccessPath.fromSegments(
                            listOf(ValuePathSegment.Element(ValueElementIndex.parse(3).modelValue()))
                        )
                        .modelValue(),
            )
            .modelValue()
    val source =
        receiver
            .withState(
                predicates =
                    ValuePredicates.fromCompiler(
                            listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(receiver)))
                        )
                        .modelValue()
            )
            .modelValue()
    val input = ValueModelApplication.ScopeCallbackInput.enter(source, frame).modelValue()
    private val returned =
        ValueSite.fromCompiler(
                host,
                range(75, 79),
                ValueRole.Return,
                formal.executionOwner,
                input.target.valuePath,
                input.target.callStack,
                input.target.predicates,
            )
            .modelValue()
    val output = ValueModelApplication.ScopeCallbackReturn.leave(returned, frame).modelValue()

    private fun library(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function("kotlin.let", "T", emptyList(), listOf("(T)->R"), 2).modelValue()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse(ImpactValueModelProjectionCase.ROOT + "kotlin/StandardKt.class")
                            .modelValue()
                    ),
                    100,
                    200,
                    "let",
                    "kotlin.let",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .modelValue()
        return RelationEndpoint.resolve(host.lease, host.scope, evidence).modelValue()
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).modelValue()
}
