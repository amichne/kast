package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Declared targets and source-function suppliers keep different compiler facts. */
sealed interface ValueProducerOrigin {
    val invocation: ValueCallOccurrence

    data class Declared(override val invocation: ValueInvocation) : ValueProducerOrigin

    /** Native K2 confirms an immutable, nondelegated SOURCE property as this exact invoke receiver. */
    class SourceFunction
    private constructor(
        override val invocation: ValueFunctionInvocation,
        val property: RevalidatedRelationEndpoint,
        val reference: RelationOccurrence,
    ) : ValueProducerOrigin {
        companion object {
            /** This shape admission does not establish SOURCE, immutability, or reference resolution from syntax. */
            fun fromCompiler(
                invocation: ValueFunctionInvocation,
                property: RevalidatedRelationEndpoint,
                reference: RelationOccurrence,
            ): Refinement<SourceFunction, ValueProducerSeedFailure> =
                when {
                    property.endpoint.lease.identity != invocation.basis ->
                        Refinement.Rejected(ValueProducerSeedFailure.BASIS_MISMATCH)
                    property.endpoint.file !is SymbolDiscoveryFileIdentity.Workspace ||
                        property.endpoint.kind != CompilerSymbolKind.PROPERTY ||
                        property.endpoint.signature !is CanonicalCompilerSignature.Property ->
                        Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH)
                    reference.file != invocation.enclosing.file ||
                        !invocation.range.containsValueRange(reference.range) ->
                        Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH)
                    else -> Refinement.Refined(SourceFunction(invocation, property, reference))
                }
        }
    }
}
