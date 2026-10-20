package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** One argument read borrows the enclosing coordinator's allowance and transfer collector. */
internal class NativeValueArgumentFlow(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Boolean,
) {
    fun read(): Refinement<Unit, ValueFlowRejection> {
        val dispatch =
            NativeValueImplementationDispatch(
                    request = request,
                    scope = scope,
                    projection = projection,
                    allowance = allowance,
                )
                .read()
        when (dispatch) {
            NativeValueArgumentDispatch.Direct -> {
                retained.adopt(nativeValueArgumentReturn(request.source, scope, projection))
                if (retained.edges.any { it.kind == ValueTransferKind.WRAPPER_RETURN })
                    projection.observation.count(IntellijReadCounter.VALUE_FLOW_WRAPPER_RETURNS)
            }
            is NativeValueArgumentDispatch.Implementations -> return implementationEntries(dispatch)
            is NativeValueArgumentDispatch.Unsupported -> retained.unresolved(dispatch.cause)
            is NativeValueArgumentDispatch.Rejected -> return Refinement.Rejected(dispatch.cause.nativeFlowRejection())
        }
        return Refinement.Refined(Unit)
    }

    private fun implementationCauses(
        read: NativeValueArgumentDispatch.Implementations
    ): List<ValueFlowUnsupportedCause> {
        val dispatch = read.evidence
        val causes =
            when (val coverage = dispatch.coverage) {
                is ValueImplementationCoverage.Exhausted ->
                    if (dispatch.batch.facts.isEmpty()) listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)
                    else emptyList()
                is ValueImplementationCoverage.Incomplete ->
                    coverage.evidence.limitations.map { it.nativeImplementationCause() }
            }
        return when (read.declaredBody) {
            NativeValueDeclaredBody.ABSTRACT -> causes
            NativeValueDeclaredBody.CONCRETE -> causes + ValueFlowUnsupportedCause.UNMODELED_CALL
        }
    }

    private fun implementationEntries(
        read: NativeValueArgumentDispatch.Implementations
    ): Refinement<Unit, ValueFlowRejection> {
        val dispatch = read.evidence
        val unresolved = implementationCauses(read)
        for (cause in unresolved.distinct()) {
            when (val value = retained.unresolvedImplementation(cause, dispatch)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return value
            }
        }
        for (relation in dispatch.batch.facts) {
            if (!permit()) break
            retained.adopt(
                nativeValueImplementationEntry(
                    source = request.source,
                    dispatch = dispatch,
                    relation = relation,
                    scope = scope,
                    projection = projection,
                )
            )
        }
        return Refinement.Refined(Unit)
    }
}
