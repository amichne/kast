package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** Assumed compiler metadata exercises pure decisions. It establishes no native resolution or artifact membership. */
internal class ReviewedModelOracleCase(generation: Long = 1) {
    private val callback = ValueCallbackOracleCase(generation)
    val host = callback.host
    val execution = callback.callback
    val stack = callback.entered().callStack
    val callable = endpoint()
    val invocation = call()
    val artifact =
        LibraryArtifactObservation(
            basis = host.lease.identity,
            root = DetachedVirtualFileUrl.parse(ROOT).flowRefined(),
            moduleName = LibraryModelModuleName.parse("packet-stdlib").flowRefined(),
            binaryDigest = LibraryArtifactDigest.parse(BINARY_SHA).flowRefined(),
            version = LibraryArtifactVersion.parse(VERSION).flowRefined(),
            origin = LibraryModelDeclarationOrigin.LIBRARY,
        )
    private val tested =
        ValueSite.fromCompiler(
                enclosing = host,
                range = flowRange(40, 42),
                role = ValueRole.LocalRead,
                executionOwner = execution,
                callStack = stack,
            )
            .flowRefined()
    val predicates =
        ValuePredicates.fromCompiler(
                listOf(
                    ValuePredicate.Subtype.fromCompiler(
                            ValuePredicateSubject.fromCompiler(tested),
                            predicateOracleClass(generation),
                        )
                        .flowRefined()
                )
            )
            .flowRefined()
    val suffix = flowPath(ValuePathSegment.Field(flowField("Payload.link", 700, 720, generation)))

    fun call(start: Int = 44, endpoint: RelationEndpoint = callable): ValueInvocation =
        ValueInvocation.fromCompiler(
                enclosing = host,
                range = flowRange(start, start + 15),
                callable = endpoint,
                executionOwner = execution,
            )
            .flowRefined()

    fun receiver(
        path: ValueAccessPath = ValueAccessPath.Whole,
        kind: ValueReceiverKind = ValueReceiverKind.EXTENSION,
        call: ValueInvocation = invocation,
    ): ValueSite =
        ValueSite.fromCompiler(
                enclosing = host,
                range = flowRange(46, 50),
                role = ValueRole.Receiver(call, kind),
                executionOwner = execution,
                valuePath = path,
                callStack = stack,
                predicates = predicates,
            )
            .flowRefined()

    fun binding(): ReviewedLibraryBinding =
        ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.FirstOrNull, artifact).flowRefined()

    fun endpoint(
        signature: CanonicalCompilerSignature = signature(),
        file: SymbolDiscoveryFileIdentity =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse(ROOT + "kotlin/collections/CollectionsKt.class").flowRefined()
            ),
    ): RelationEndpoint {
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file = file,
                    rawStartInclusive = 22846,
                    rawEndExclusive = 22929,
                    rawName = "firstOrNull",
                    rawQualifiedIdentity = "kotlin.collections.firstOrNull",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .flowRefined()
        return RelationEndpoint.resolve(
                host.lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
                evidence,
            )
            .flowRefined()
    }

    companion object {
        const val ROOT = "jar:///fixture/kotlin-stdlib.jar!/"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val SOURCE_SHA = "5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"
        const val VERSION = "2.3.20-release-208"

        fun signature(receiver: String = "kotlin/collections/List<T>") =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.collections.firstOrNull",
                    rawReceiverType = receiver,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 1,
                )
                .flowRefined()
    }
}
