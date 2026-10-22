package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/**
 * Stores one native read's compiler transfers, model applications, and obligations under its detached capacity grant.
 */
internal class NativeValueFlowTransfers(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val observation: IntellijReadObservation,
) {
    private val emitted = mutableListOf<ValueTransfer>()
    private val unresolved = mutableListOf<ValueFlowObligation>()
    val edges: List<ValueTransfer>
        get() = emitted

    val obligations: List<ValueFlowObligation>
        get() = unresolved

    val resultCount: Int
        get() = edges.size

    fun adopt(result: Refinement<ValueTransfer, ValueFlowUnsupportedCause>) {
        when (result) {
            is Refinement.Refined -> emitTarget(result.value.target, result.value.kind, result.value.evidence)
            is Refinement.Rejected -> unresolved(result.failure)
        }
    }

    fun emit(
        element: PsiElement,
        role: ValueRole,
        kind: ValueTransferKind,
        evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
    ) {
        when (val result = target(element, role)) {
            is Refinement.Refined -> emitTarget(result.value, kind, evidence)
            is Refinement.Rejected -> unresolved(result.failure)
        }
    }

    private fun target(element: PsiElement, role: ValueRole): Refinement<ValueSite, ValueFlowUnsupportedCause> {
        when (valueNativeOwnership(element, owner)) {
            NativeValueOwnership.ADMITTED -> Unit
            NativeValueOwnership.NESTED -> return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            NativeValueOwnership.UNAVAILABLE ->
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
        val file = element.containingFile.virtualFile
        if (!scope.nativeScope.contains(file)) return Refinement.Rejected(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
        val range =
            when (val result = nativeRange(element)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        return when (
            val result =
                ValueSite.fromCompiler(
                    enclosing = request.source.enclosing,
                    range = range,
                    role = role,
                    executionOwner = request.source.executionOwner,
                    valuePath = request.source.valuePath,
                    callStack = request.source.callStack,
                )
        ) {
            is Refinement.Refined -> result
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    fun emitTarget(
        target: ValueSite,
        kind: ValueTransferKind,
        evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
    ) {
        when (
            val transfer =
                ValueTransfer.fromCompiler(source = request.source, target = target, kind = kind, evidence = evidence)
        ) {
            is Refinement.Refined -> {
                if (transfer.value !in edges) {
                    if (resultCount >= request.budget.outputResults.value) {
                        unresolved(ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED)
                        return
                    }
                    val bytes =
                        ValueFlowStep.detachedByteCount(
                                source = request.source,
                                domain = scope.request,
                                transfers = edges + transfer.value,
                                obligations = obligations,
                            )
                            .value
                    if (!request.budget.permitsReturnedBytes(bytes + reservedValueObligationBytes(request.source))) {

                        unresolved(ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED)
                        return
                    }
                    emitted += transfer.value
                    observation.count(IntellijReadCounter.VALUE_FLOW_TRANSFERS)
                }
            }
            is Refinement.Rejected -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    fun unresolved(cause: ValueFlowUnsupportedCause) {
        record(ValueFlowObligation(request.source, cause))
    }

    fun unresolvedImplementation(
        cause: ValueFlowUnsupportedCause,
        dispatch: ValueImplementationDispatch,
    ): Refinement<Unit, ValueFlowRejection> {
        val obligation =
            when (val value = ValueFlowObligation.fromImplementation(request.source, cause, dispatch)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return Refinement.Rejected(ValueFlowRejection.STALE_SITE)
            }
        return recordEvidence(obligation)
    }

    private fun recordEvidence(obligation: ValueFlowObligation): Refinement<Unit, ValueFlowRejection> {
        val bytes =
            ValueFlowStep.detachedByteCount(
                    source = request.source,
                    domain = scope.request,
                    transfers = edges,
                    obligations = (obligations + obligation).distinct(),
                )
                .value
        if (!request.budget.permitsReturnedBytes(bytes + reservedValueObligationBytes(request.source))) {

            return Refinement.Rejected(ValueFlowRejection.GRANT_TOO_SMALL)
        }
        record(obligation)
        return Refinement.Refined(Unit)
    }

    private fun record(obligation: ValueFlowObligation) {
        if (obligation !in obligations) {
            unresolved += obligation
            observation.count(IntellijReadCounter.VALUE_FLOW_OBLIGATIONS)
        }
    }
}

private fun nativeRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
    when (val range = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
        is Refinement.Refined -> range
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
