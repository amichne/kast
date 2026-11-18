package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueJavaIntDivision
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

internal enum class NativeJavaValueRead {
    Continued,
    MatchedReturn,
}

internal fun PsiMethod.exactJavaValueElement(range: ExactDeclarationTextRange): PsiElement? =
    exactSourceElements(range).firstOrNull { it is PsiParameter || it is PsiLocalVariable || it is PsiExpression }

/** Emits one hop from the admitted straight-line body and leaves call return to the shared frame owner. */
internal class NativeJavaValueFlow(
    private val source: ValueSite,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    fun read(method: PsiMethod, element: PsiElement): NativeJavaValueRead {
        val frame = source.callStack.frames.lastOrNull()
        if (frame == null || frame.callee != source.executionOwner)
            return unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN, element)
        val reads =
            when (val admitted = NativeJavaValueBody.admit(method, permit)) {
                is Refinement.Refined -> admitted.value.reads
                is Refinement.Rejected -> return unsupported(admitted.failure.cause, admitted.failure.element)
            }
        return when (val role = source.role) {
            is ValueRole.Formal -> readFormal(method, element, role, reads)
            ValueRole.LocalBinding -> {
                if (element is PsiLocalVariable && reads.any { it.destination == JavaValueDestination.Bind(element) })
                    emitReads(element, reads)
                else unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
            }
            ValueRole.LocalRead -> readReference(element, reads)
            ValueRole.ExpressionResult -> {
                val read =
                    reads.singleOrNull { (it.operation as? JavaReadOperation.IntDivision)?.result == element }
                        ?: return unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
                emitDestination(read)
            }
            ValueRole.Return ->
                if (reads.any { it.result == element && it.destination == JavaValueDestination.Return })
                    NativeJavaValueRead.MatchedReturn
                else unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN, element)
            else -> unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
        }
    }

    private fun readFormal(
        method: PsiMethod,
        element: PsiElement,
        role: ValueRole.Formal,
        reads: List<JavaValueRead>,
    ): NativeJavaValueRead {
        val formal = (role.position as? ValueFormalPosition.Parameter)?.parameter
        if (element !is PsiParameter || formal == null)
            return unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE, element)
        val position = formal.position
        if (
            method.parameterList.getParameter(position.value) != element ||
                nativeValueFormalParameter(source.enclosing, position, element) != Refinement.Refined(formal)
        )
            return unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE, element)
        return emitReads(element, reads)
    }

    private fun emitReads(binding: PsiVariable, reads: List<JavaValueRead>): NativeJavaValueRead {
        for (read in reads.filter { it.binding == binding }) {
            if (permit() is Refinement.Rejected) break
            retained.emit(read.expression, ValueRole.LocalRead, ValueTransferKind.LOCAL_READ)
        }
        return NativeJavaValueRead.Continued
    }

    private fun readReference(element: PsiElement, reads: List<JavaValueRead>): NativeJavaValueRead {
        val read =
            reads.singleOrNull { it.expression == element }
                ?: return unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE, element)
        return when (val operation = read.operation) {
            JavaReadOperation.Identity -> emitDestination(read)
            is JavaReadOperation.IntDivision -> emitDivision(operation)
        }
    }

    private fun emitDivision(operation: JavaReadOperation.IntDivision): NativeJavaValueRead {
        val proof =
            ValueJavaIntDivision.fromJavaIntOperands(
                source = source,
                divisorRange = sourceValueRange(operation.literal),
                resultRange = sourceValueRange(operation.result),
                divisor = operation.divisor,
            )
        when (proof) {
            is Refinement.Refined ->
                retained.emit(
                    operation.result,
                    ValueRole.ExpressionResult,
                    ValueTransferKind.PRIMITIVE_DERIVATION,
                    proof.value,
                )
            is Refinement.Rejected ->
                return unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, operation.result)
        }
        return NativeJavaValueRead.Continued
    }

    private fun emitDestination(read: JavaValueRead): NativeJavaValueRead {
        when (val destination = read.destination) {
            is JavaValueDestination.Bind ->
                retained.emit(destination.local, ValueRole.LocalBinding, ValueTransferKind.LOCAL_BINDING)
            JavaValueDestination.Return -> retained.emit(read.result, ValueRole.Return, ValueTransferKind.RETURN)
        }
        return NativeJavaValueRead.Continued
    }

    private fun unsupported(cause: ValueFlowUnsupportedCause, element: PsiElement): NativeJavaValueRead {
        retained.unresolvedAt(cause, element)
        return NativeJavaValueRead.Continued
    }
}

internal data class JavaValueRead(
    val expression: PsiReferenceExpression,
    val binding: PsiVariable,
    val destination: JavaValueDestination,
    val operation: JavaReadOperation = JavaReadOperation.Identity,
) {
    val result: PsiExpression
        get() =
            when (operation) {
                JavaReadOperation.Identity -> expression
                is JavaReadOperation.IntDivision -> operation.result
            }
}

internal sealed interface JavaReadOperation {
    data object Identity : JavaReadOperation

    data class IntDivision(val result: PsiBinaryExpression, val literal: PsiLiteralExpression, val divisor: Int) :
        JavaReadOperation
}

internal sealed interface JavaValueDestination {
    data class Bind(val local: PsiLocalVariable) : JavaValueDestination

    data object Return : JavaValueDestination
}
