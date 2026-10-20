package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.LibraryArtifactDigest
import io.github.amichne.kast.relation.contract.LibraryArtifactObservation
import io.github.amichne.kast.relation.contract.LibraryArtifactVersion
import io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
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

/** Assumed issued compiler metadata supplies one real core application to the existing strict script. */
internal class AutomaticValueQueryCase {
    val fixture = QueryImpactExecutionFixture()
    val invocation = ValueInvocation.fromCompiler(fixture.owner, exact(70, 90), library()).autoRefined()
    val receiver =
        ValueSite.fromCompiler(
                enclosing = fixture.owner,
                range = exact(72, 78),
                role = ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
            )
            .autoRefined()
    val result = invocation.resultSite()
    val consumption = fixture.argument(fixture.call("opaqueSink", 110, 120))
    val intoReceiver = fixture.edge(fixture.producer.site, receiver, ValueTransferKind.RECEIVER)
    val intoSink = fixture.edge(result, consumption, ValueTransferKind.ARGUMENT)
    private val artifact =
        LibraryArtifactObservation(
            basis = fixture.lease.identity,
            root = DetachedVirtualFileUrl.parse(ROOT).autoRefined(),
            moduleName = LibraryModelModuleName.parse("packet-stdlib").autoRefined(),
            binaryDigest = LibraryArtifactDigest.parse(BINARY_SHA).autoRefined(),
            version = LibraryArtifactVersion.parse("2.3.20-release-208").autoRefined(),
            origin = LibraryModelDeclarationOrigin.LIBRARY,
        )
    private val binding =
        ReviewedLibraryBinding.fromCompiler(
                invocation,
                ReviewedValueModel.FirstOrNull,
                artifact,
            )
            .autoRefined()
    val application = ValueModelApplication.CollectionSelection.selectFirst(receiver, binding).autoRefined()
    private val identity =
        ContractModelIdentity(
            id("automatic-value-query"),
            ModelVersion.parse(1).autoRefined(),
            id("review:query-tracer"),
        )
    private val vocabulary = RepresentationDomain.admit(identity, listOf(id("OPAQUE"))).autoRefined()
    val originRule =
        RepresentationRule.Origin.admit(
                ModelRuleReference(identity, id("origin")),
                fixture.bind(fixture.producer.invocation.callable, ModelValuePosition.Result),
                vocabulary.state(id("OPAQUE")).autoRefined(),
            )
            .autoRefined()
    val origin =
        RepresentationEvidence.origin(fixture.producer.site, fixture.producer.invocation, originRule).autoRefined()

    private fun library(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.collections.firstOrNull",
                    rawReceiverType = "kotlin/collections/List<T>",
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 1,
                )
                .autoRefined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file =
                        SymbolDiscoveryFileIdentity.External(
                            DetachedVirtualFileUrl.parse(ROOT + "kotlin/collections/CollectionsKt.class").autoRefined()
                        ),
                    rawStartInclusive = 22846,
                    rawEndExclusive = 22929,
                    rawName = "firstOrNull",
                    rawQualifiedIdentity = "kotlin.collections.firstOrNull",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .autoRefined()
        return RelationEndpoint.resolve(
                fixture.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                evidence,
            )
            .autoRefined()
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).autoRefined()

    private fun id(value: String) = ModelIdentifier.parse(value).autoRefined()

    private companion object {
        const val ROOT = "jar:///fixture/kotlin-stdlib.jar!/"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
    }
}

internal fun <V, F> Refinement<V, F>.autoRefined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Automatic value fixture prerequisite rejected: $failure")
    }
