package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind

/** Issued metadata is assumed. Captured library signatures and artifact digests remain separate native evidence. */
internal class SingleListModelOracleCase(
    generation: Long = 1,
    val library: ReviewedModelOracleCase = ReviewedModelOracleCase(generation),
) {
    val host = library.host
    val execution = library.execution
    val stack = library.stack
    val predicates = library.predicates
    val artifact = library.artifact
    val suffix = library.suffix
    val callable = endpoint()
    val invocation = call()

    fun endpoint(signature: CanonicalCompilerSignature = signature()): RelationEndpoint {
        val original = library.callable as RelationEndpoint.Resolved
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file = original.file,
                    rawStartInclusive = original.range.startInclusive,
                    rawEndExclusive = original.range.endExclusive,
                    rawName = "listOf",
                    rawQualifiedIdentity = "kotlin.collections.listOf",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .flowRefined()
        return RelationEndpoint.resolve(
                lease = host.lease,
                scope = original.scope,
                evidence = evidence,
                constraints = original.constraints,
            )
            .flowRefined()
    }

    fun call(
        start: Int = 44,
        enclosing: RelationEndpoint = host,
        callable: RelationEndpoint = this.callable,
        execution: ValueExecutionOwner = this.execution,
    ): ValueInvocation =
        ValueInvocation.fromCompiler(
                enclosing = enclosing,
                range = flowRange(start, start + 15),
                callable = callable,
                executionOwner = execution,
            )
            .flowRefined()

    fun argument(path: ValueAccessPath = ValueAccessPath.Whole, call: ValueInvocation = invocation): ValueSite =
        ValueSite.fromCompiler(
                enclosing = call.enclosing,
                range = flowRange(46, 50),
                role = ValueRole.Argument(call, ValueArgumentPosition.parse(0).flowRefined()),
                executionOwner = call.executionOwner,
                valuePath = path,
                callStack = stack,
                predicates = predicates,
            )
            .flowRefined()

    fun binding(call: ValueInvocation = invocation): ReviewedLibraryBinding =
        ReviewedLibraryBinding.fromCompiler(call, ReviewedValueModel.SingleElementListOf, artifact).flowRefined()

    fun insert(source: ValueSite = argument()): ValueModelApplication.CollectionInsertion =
        ValueModelApplication.CollectionInsertion.insertSingle(source, binding()).flowRefined()

    companion object {
        fun signature() =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.collections.listOf",
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("T"),
                    rawTypeParameterCount = 1,
                )
                .flowRefined() as CanonicalCompilerSignature.Function
    }
}
