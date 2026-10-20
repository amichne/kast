package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactObservation
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
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
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** Existing callback proof supplies real detached context. Library facts remain assumed compiler metadata. */
internal class ImpactValueModelProjectionCase {
    val callback = ImpactCallbackProjectionCase()
    val references = callback.references
    val host = callback.formal.enclosing
    val invocation =
        ValueInvocation.fromCompiler(host, range(44, 70), library(), callback.formal.executionOwner).modelValue()
    val predicates =
        ValuePredicates.fromCompiler(
                listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(callback.formal)))
            )
            .modelValue()
    val source =
        ValueSite.fromCompiler(
                host,
                range(46, 50),
                ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
                callback.formal.executionOwner,
                callStack = callback.formal.callStack,
                predicates = predicates,
            )
            .modelValue()
    val artifact =
        LibraryArtifactObservation(
            host.lease.identity,
            DetachedVirtualFileUrl.parse(ROOT).modelValue(),
            LibraryModelModuleName.parse("packet-stdlib").modelValue(),
            LibraryArtifactDigest.parse(BINARY_SHA).modelValue(),
            LibraryArtifactVersion.parse(VERSION).modelValue(),
            LibraryModelDeclarationOrigin.LIBRARY,
        )
    val binding = ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.FirstOrNull, artifact).modelValue()
    val application = ValueModelApplication.CollectionSelection.selectFirst(source, binding).modelValue()

    private fun library(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function(
                    "kotlin.collections.firstOrNull",
                    "kotlin/collections/List<T>",
                    emptyList(),
                    emptyList(),
                    1,
                )
                .modelValue()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse(ROOT + "kotlin/collections/CollectionsKt.class").modelValue()
                    ),
                    22846,
                    22929,
                    "firstOrNull",
                    "kotlin.collections.firstOrNull",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .modelValue()
        return RelationEndpoint.resolve(
                host.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                evidence,
            )
            .modelValue()
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).modelValue()

    companion object {
        const val ROOT = "jar:///fixture/kotlin-stdlib.jar!/"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val SOURCE_SHA = "5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"
        const val VERSION = "2.3.20-release-208"
    }
}

internal fun <V, F> Refinement<V, F>.modelValue(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Model projection prerequisite rejected: $failure")
    }
