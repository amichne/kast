@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

/** All live elements and K2 values remain inside the caller's one native read action. */
internal class IntellijValueFlowNative(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val observation: IntellijReadObservation,
    private val started: Long,
    private val onWork: (RelationWorkCount) -> Unit,
) {
    private val edges = mutableListOf<ValueTransfer>()
    private val obligations = mutableListOf<ValueFlowObligation>()
    private var examined = 1L

    fun read(element: PsiElement): ValueFlowRead {
        if (request.source.role == ValueRole.LocalBinding && element is KtProperty && element.isImmutableLocal())
            return IntellijLocalBindingReads(request, owner, scope, observation, started, onWork).read(element)

        val base = ValueFlowStep.detachedByteCount(request.source, scope.request, emptyList(), emptyList()).value
        val reserved = reservedObligationBytes()
        if (base > request.budget.returnedBytes.value || reserved > request.budget.returnedBytes.value - base)
            return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, nativeWorkCount())
        when (permitted()) {
            Allowance.READY -> readRole(element)
            Allowance.EXHAUSTED -> Unit
        }
        val terminal =
            if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted else ValueFlowTerminal.Unresolved
        return when (
            val step =
                ValueFlowStep.fromCompiler(
                    request.source,
                    edges,
                    obligations,
                    terminal,
                    scope.request,
                    nativeWorkCount(),
                )
        ) {
            is Refinement.Refined -> ValueFlowRead.Observed(step.value)
            is Refinement.Rejected -> ValueFlowRead.ContractRejected(step.failure, nativeWorkCount())
        }
    }

    private fun reservedObligationBytes(): Long {
        val count = ValueFlowUnsupportedCause.entries.size.toLong()
        val siteBytes = request.source.retainedBytes
        return if (siteBytes > Long.MAX_VALUE / count - VALUE_OBLIGATION_OVERHEAD_BYTES) Long.MAX_VALUE
        else (VALUE_OBLIGATION_OVERHEAD_BYTES + siteBytes) * count
    }

    private fun readRole(element: PsiElement) {
        when (request.source.role) {
            ValueRole.LocalBinding -> localReads(element as KtProperty)
            is ValueRole.Argument -> wrapperReturn(request.source.role as ValueRole.Argument)
            ValueRole.Return -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            ValueRole.PropertyAssignment -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
            ValueRole.ExpressionResult,
            ValueRole.LocalRead -> {
                val expression =
                    element as? KtExpression
                        ?: run {
                            unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                            return
                        }
                transferExpression(expression)
            }
        }
    }

    private fun wrapperReturn(argument: ValueRole.Argument) {
        when (val result = nativeTransparentWrapperReturn(argument, scope, projection)) {
            is Refinement.Refined -> {
                val target = argument.call.resultSite()
                emitTarget(target, ValueTransferKind.WRAPPER_RETURN)
                if (edges.any { it.kind == ValueTransferKind.WRAPPER_RETURN })
                    observation.count(IntellijReadCounter.VALUE_FLOW_WRAPPER_RETURNS)
            }
            is Refinement.Rejected -> unresolved(result.failure)
        }
    }

    private fun nativeWorkCount(): RelationWorkCount =
        when (val count = RelationWorkCount.parse(examined)) {
            is Refinement.Refined -> count.value
            is Refinement.Rejected -> error("Native work counter cannot be negative")
        }

    private enum class Allowance {
        READY,
        EXHAUSTED,
    }

    private fun permitted(): Allowance {
        ProgressManager.checkCanceled()
        val grant = request.budget.resources
        return when {
            examined >= grant.workUnitLimit.value -> stopped(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
            edges.size >= grant.resultLimit.value -> stopped(ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED)
            System.nanoTime() - started >=
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(grant.elapsedTimeLimit.value) ->
                stopped(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
            else -> {
                examined++
                onWork(nativeWorkCount())
                Allowance.READY
            }
        }
    }

    private fun stopped(cause: ValueFlowUnsupportedCause): Allowance {
        unresolved(cause)
        return Allowance.EXHAUSTED
    }

    private fun localReads(property: KtProperty) {
        if (property.isVar) {
            unresolved(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
            return
        }
        if (!property.isLocal) {
            unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
            return
        }
        error("Immutable local binding must use the production reference scan")
    }

    private fun transferExpression(expression: KtExpression) {
        when (permitted()) {
            Allowance.READY -> Unit
            Allowance.EXHAUSTED -> return
        }
        when (val parent = expression.parent) {
            is KtParenthesizedExpression -> transferExpression(parent)
            is KtProperty -> propertyDestination(expression, parent)
            is KtValueArgument -> argument(expression, parent)
            is KtReturnExpression -> emit(expression, ValueRole.Return, ValueTransferKind.RETURN)
            is KtNamedFunction -> functionDestination(expression, parent)
            is KtContainerNodeForControlStructureBody -> ifDestination(expression, parent)
            is KtWhenEntry -> {
                val branch = parent.parent as? KtWhenExpression
                if (branch != null && parent.expression === expression) branchResult(branch)
                else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            }
            is KtBlockExpression -> {
                // Only a function statement whose result is discarded is terminal. Branch/lambda block values need
                // proof.
                if (parent.parent !is KtNamedFunction) unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            }
            else -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun propertyDestination(expression: KtExpression, property: KtProperty) {
        if (property.initializer !== expression) {
            unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        if (property.isLocal) emit(property, ValueRole.LocalBinding, ValueTransferKind.LOCAL_BINDING)
        else emit(property, ValueRole.PropertyAssignment, ValueTransferKind.PROPERTY_ASSIGNMENT)
    }

    private fun functionDestination(expression: KtExpression, function: KtNamedFunction) {
        if (function.bodyExpression === expression) emit(expression, ValueRole.Return, ValueTransferKind.RETURN)
        else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun ifDestination(expression: KtExpression, container: KtContainerNodeForControlStructureBody) {
        val branch = container.parent as? KtIfExpression
        if (branch != null && branch.condition !== expression) branchResult(branch)
        else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun branchResult(branch: KtExpression) {
        val carriesValue =
            analyze(branch) {
                when (val type = branch.expressionType) {
                    null,
                    is KaErrorType -> false
                    is KaClassType -> type.classId.asSingleFqName().asString() != "kotlin.Unit"
                    else -> false
                }
            }
        if (carriesValue) emit(branch, ValueRole.ExpressionResult, ValueTransferKind.BRANCH_ALTERNATIVE)
        else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun argument(expression: KtExpression, argument: KtValueArgument) {
        when (val role = nativeValueArgument(expression, argument, request.source.enclosing, scope, projection)) {
            is Refinement.Refined -> emit(expression, role.value, ValueTransferKind.ARGUMENT)
            is Refinement.Rejected -> unresolved(role.failure)
        }
    }

    private fun emit(element: PsiElement, role: ValueRole, kind: ValueTransferKind) {
        when (valueNativeOwnership(element, owner)) {
            NativeValueOwnership.ADMITTED -> Unit
            NativeValueOwnership.NESTED -> {
                unresolved(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                return
            }
            NativeValueOwnership.UNAVAILABLE -> {
                unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                return
            }
        }
        val file = element.containingFile.virtualFile
        if (!scope.nativeScope.contains(file)) {
            unresolved(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
            return
        }
        val range =
            when (val result = nativeRange(element)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> {
                    unresolved(result.failure)
                    return
                }
            }
        val target =
            when (val result = ValueSite.fromCompiler(request.source.enclosing, range, role)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> {
                    unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                    return
                }
            }
        emitTarget(target, kind)
    }

    private fun emitTarget(target: ValueSite, kind: ValueTransferKind) {
        when (val transfer = ValueTransfer.fromCompiler(request.source, target, kind)) {
            is Refinement.Refined -> {
                if (transfer.value !in edges) {
                    val bytes =
                        ValueFlowStep.detachedByteCount(
                                request.source,
                                scope.request,
                                edges + transfer.value,
                                obligations,
                            )
                            .value
                    if (
                        bytes > request.budget.returnedBytes.value ||
                            reservedObligationBytes() > request.budget.returnedBytes.value - bytes
                    ) {
                        unresolved(ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED)
                        return
                    }
                    edges += transfer.value
                    observation.count(IntellijReadCounter.VALUE_FLOW_TRANSFERS)
                }
            }
            is Refinement.Rejected -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun nativeRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
        when (val range = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
            is Refinement.Refined -> range
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }

    private fun unresolved(cause: ValueFlowUnsupportedCause) {
        val obligation = ValueFlowObligation(request.source, cause)
        if (obligation !in obligations) {
            obligations += obligation
            observation.count(IntellijReadCounter.VALUE_FLOW_OBLIGATIONS)
        }
    }
}

private const val VALUE_OBLIGATION_OVERHEAD_BYTES = 2048L

private fun KtProperty.isImmutableLocal(): Boolean = !isVar && isLocal
