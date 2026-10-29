package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Argument and receiver entries borrow the enclosing coordinator's allowance and transfer collector. */
internal class NativeValueCallFlow(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Boolean,
) {
    fun read(): Refinement<Unit, ValueFlowRejection> {
        if (readReviewedCallIfSelected()) return Refinement.Refined(Unit)
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
                when (request.source.role) {
                    is ValueRole.Argument ->
                        retained.adopt(nativeValueArgumentReturn(request.source, scope, projection))
                    is ValueRole.Receiver ->
                        retained.adopt(allowance.nativeValueSourceReceiver(request.source, scope, projection))
                    else -> retained.unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                }
                if (retained.edges.any { it.kind == ValueTransferKind.WRAPPER_RETURN })
                    projection.observation.count(IntellijReadCounter.VALUE_FLOW_WRAPPER_RETURNS)
            }
            is NativeValueArgumentDispatch.Implementations -> return implementationEntries(dispatch)
            is NativeValueArgumentDispatch.Unsupported -> retained.unresolved(dispatch.cause)
            is NativeValueArgumentDispatch.Rejected -> return Refinement.Rejected(dispatch.cause.nativeFlowRejection())
        }
        return Refinement.Refined(Unit)
    }

    private fun readReviewedCallIfSelected(): Boolean {
        val invocation =
            when (val role = request.source.role) {
                is ValueRole.Receiver -> role.call
                is ValueRole.Argument -> role.call
                else -> return false
            }
        if (invocation.callable.file !is io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.External)
            return false
        val selected =
            invocation.callable.qualifiedIdentity
                as? io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available ?: return false
        when (selected.value) {
            "kotlin.also",
            "kotlin.collections.map",
            "kotlin.collections.sortedBy",
            "kotlin.collections.associate" -> {
                if (request.source.role !is ValueRole.Receiver) return false
                nativeReviewedReceiverResults(request, scope, projection, allowance, retained)
            }
            "kotlin.to",
            "kotlin.Pair.<init>",
            "kotlin.Pair.first",
            "kotlin.Pair.second" ->
                retained.adoptReviewed(nativeReviewedTupleResult(request, scope, projection, allowance))
            else -> return false
        }
        return true
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
                allowance.nativeValueImplementationEntry(
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
