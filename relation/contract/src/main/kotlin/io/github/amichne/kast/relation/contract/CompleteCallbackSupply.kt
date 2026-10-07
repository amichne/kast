package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** One compiler-selected argument/default at one call, independent of source-wide use exhaustion. */
class CompleteCallbackSupply
private constructor(
    val supplier: CallbackParameterSupplier,
    val summary: CallbackParameterSummary,
) {
    val retainedBytes = supplier.retainedBytes.addBytes(summary.retainedBytes)

    companion object {
        fun fromCompiler(
            supplier: CallbackParameterSupplier,
            summary: CallbackParameterSummary,
        ): Refinement<CompleteCallbackSupply, ImmutableCallbackInvocationFlowFailure> =
            when (
                val admitted =
                    ImmutableCallbackInvocationUse.Supplied(supplier, summary).admitSupplied(supplier.value.source)
            ) {
                is Refinement.Refined -> Refinement.Refined(CompleteCallbackSupply(supplier, summary))
                is Refinement.Rejected -> admitted
            }
    }
}

internal fun CompleteCallbackSupply.canonicalProjection(): String = buildString {
    fun field(value: String) {
        append(value.length).append(':').append(value)
    }
    field("CALLBACK_SUPPLY")
    field(supplier.canonicalProjection())
    field(summary.formal.canonicalProjection())
    field(summary.invocations.size.toString())
    summary.invocations.forEach { field(it.canonicalProjection()) }
    when (val proof = summary.forwarding) {
        CallbackForwardingEvidence.InvocationRoutes -> field("INVOCATION_ROUTES")
        is CallbackForwardingEvidence.ExhaustedGraph -> {
            field("EXHAUSTED_GRAPH")
            field(proof.graph.root.canonicalProjection())
            field(proof.graph.formals.size.toString())
            proof.graph.formals.forEach { field(it.canonicalProjection()) }
            field(proof.graph.forwardings.size.toString())
            proof.graph.forwardings.forEach { field(it.canonicalProjection()) }
        }
    }
}

enum class CompleteCallbackSuppliesFailure {
    EMPTY,
    DUPLICATE,
    INVOCATION_MISMATCH,
    FORMAL_INVENTORY_MISMATCH,
}

/** All selected callback values at a single call are retained atomically. */
class CompleteCallbackSupplies
private constructor(val values: List<CompleteCallbackSupply>, val formals: List<CallbackParameterIdentity>) {
    val retainedBytes = values.fold(4096L) { bytes, value -> bytes.addBytes(value.retainedBytes) }

    companion object {
        fun fromCompiler(
            values: List<CompleteCallbackSupply>,
            formals: List<CallbackParameterIdentity>,
        ): Refinement<CompleteCallbackSupplies, CompleteCallbackSuppliesFailure> {
            if (values.isEmpty() || formals.isEmpty()) return Refinement.Rejected(CompleteCallbackSuppliesFailure.EMPTY)
            if (formals.distinct().size != formals.size || values.map { it.supplier.formal }.toSet() != formals.toSet())
                return Refinement.Rejected(CompleteCallbackSuppliesFailure.FORMAL_INVENTORY_MISMATCH)
            val invocation = values.first().supplier.binding.invocation
            if (values.any { it.supplier.binding.invocation != invocation })
                return Refinement.Rejected(CompleteCallbackSuppliesFailure.INVOCATION_MISMATCH)
            if (values.map { it.canonicalProjection() }.distinct().size != values.size)
                return Refinement.Rejected(CompleteCallbackSuppliesFailure.DUPLICATE)
            return Refinement.Refined(
                CompleteCallbackSupplies(
                    java.util.Collections.unmodifiableList(values.toList()),
                    java.util.Collections.unmodifiableList(formals.toList()),
                )
            )
        }
    }
}

internal fun CompleteCallbackSupplies.canonicalProjection(): String = buildString {
    fun field(value: String) {
        append(value.length).append(':').append(value)
    }
    field("CALLBACK_SUPPLIES")
    field(formals.size.toString())
    formals.forEach { field(it.canonicalProjection()) }
    field(values.size.toString())
    values.forEach { field(it.canonicalProjection()) }
}
