package io.github.amichne.kast.relation.intellij

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

/** The whole method must fit the grammar before a read can establish either transfer or absence. */
internal class NativeJavaValueBody private constructor(val reads: List<JavaValueRead>) {
    data class Failure(val cause: ValueFlowUnsupportedCause, val element: PsiElement)

    companion object {
        fun admit(
            method: PsiMethod,
            permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        ): Refinement<NativeJavaValueBody, Failure> {
            if (!method.isSupportedJavaTarget()) return unsupportedJavaBody(method)
            if (method is PsiCompiledElement) return unavailableJavaBody(method)
            val body = method.body ?: return unavailableJavaBody(method)
            val returnType = method.returnType ?: return unsupportedJavaBody(method)
            val statements = body.statements
            if (body.rBrace == null || statements.lastOrNull() !is PsiReturnStatement) return unsupportedJavaBody(body)
            val reads =
                bodyReads(
                    statements = statements,
                    bindings = method.parameterList.parameters.toMutableSet(),
                    returnType = returnType,
                    permit = permit,
                )
            return when (reads) {
                is Refinement.Refined -> Refinement.Refined(NativeJavaValueBody(reads.value))
                is Refinement.Rejected -> reads
            }
        }
    }
}

private fun bodyReads(
    statements: Array<PsiStatement>,
    bindings: MutableSet<PsiVariable>,
    returnType: PsiType,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<List<JavaValueRead>, NativeJavaValueBody.Failure> {
    val reads = mutableListOf<JavaValueRead>()
    for ((index, statement) in statements.withIndex()) {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected ->
                return Refinement.Rejected(NativeJavaValueBody.Failure(allowed.failure, statement))
        }
        val read =
            readJavaStatement(
                statement = statement,
                last = index == statements.lastIndex,
                bindings = bindings,
                returnType = returnType,
            )
        when (read) {
            is JavaValueInput.Read -> reads += read.read
            JavaValueInput.Literal -> Unit
            JavaValueInput.Unresolved ->
                return Refinement.Rejected(
                    NativeJavaValueBody.Failure(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE, statement)
                )
            JavaValueInput.Unsupported -> return unsupportedJavaBody(statement)
        }
    }
    return Refinement.Refined(reads.toList())
}

private fun readJavaStatement(
    statement: PsiStatement,
    last: Boolean,
    bindings: MutableSet<PsiVariable>,
    returnType: PsiType,
): JavaValueInput {
    val result =
        when (statement) {
            is PsiReturnStatement ->
                if (last) readJavaValue(statement.returnValue, returnType, bindings, JavaValueDestination.Return)
                else JavaValueInput.Unsupported
            is PsiDeclarationStatement -> readJavaAlias(statement, bindings)
            else -> JavaValueInput.Unsupported
        }
    return if (result == JavaValueInput.Unsupported || PsiTreeUtil.hasErrorElements(statement))
        JavaValueInput.Unsupported
    else result
}

private fun readJavaAlias(
    statement: PsiDeclarationStatement,
    bindings: MutableSet<PsiVariable>,
): JavaValueInput {
    val local = statement.declaredElements.singleOrNull() as? PsiLocalVariable ?: return JavaValueInput.Unsupported
    if (!local.hasModifierProperty(PsiModifier.FINAL)) return JavaValueInput.Unsupported
    val value = readJavaValue(local.initializer, local.type, bindings, JavaValueDestination.Bind(local))
    bindings += local
    return value
}

private fun readJavaValue(
    expression: PsiExpression?,
    expected: PsiType,
    bindings: Set<PsiVariable>,
    destination: JavaValueDestination,
): JavaValueInput {
    return when (val value = PsiUtil.skipParenthesizedExprDown(expression)) {
        is PsiLiteralExpression ->
            if (TypeConversionUtil.areTypesAssignmentCompatible(expected, value)) JavaValueInput.Literal
            else JavaValueInput.Unsupported
        is PsiBinaryExpression -> readJavaDivision(value, expected, bindings, destination)
        is PsiReferenceExpression -> readBinding(value, expected, bindings, destination)
        else -> JavaValueInput.Unsupported
    }
}

private fun readBinding(
    value: PsiReferenceExpression,
    expected: PsiType,
    bindings: Set<PsiVariable>,
    destination: JavaValueDestination,
): JavaValueInput {
    if (value.qualifierExpression != null) return JavaValueInput.Unsupported
    val binding = value.resolve() ?: return JavaValueInput.Unresolved
    if (binding !is PsiVariable || binding !in bindings) return JavaValueInput.Unsupported
    val actual = value.type ?: return JavaValueInput.Unresolved
    if (!sameJavaValue(actual, expected)) return JavaValueInput.Unsupported
    return JavaValueInput.Read(JavaValueRead(value, binding, destination))
}

private fun readJavaDivision(
    expression: PsiBinaryExpression,
    expected: PsiType,
    bindings: Set<PsiVariable>,
    destination: JavaValueDestination,
): JavaValueInput {
    val int = PsiTypes.intType()
    if (expression.operationTokenType != JavaTokenType.DIV || expected != int || expression.type != int)
        return JavaValueInput.Unsupported
    val dividend =
        PsiUtil.skipParenthesizedExprDown(expression.lOperand) as? PsiReferenceExpression
            ?: return JavaValueInput.Unsupported
    val literal =
        PsiUtil.skipParenthesizedExprDown(expression.rOperand) as? PsiLiteralExpression
            ?: return JavaValueInput.Unsupported
    val divisor = literal.value as? Int ?: return JavaValueInput.Unsupported
    if (divisor == 0 || literal.type != int) return JavaValueInput.Unsupported
    return when (val input = readBinding(dividend, int, bindings, destination)) {
        is JavaValueInput.Read ->
            JavaValueInput.Read(
                input.read.copy(operation = JavaReadOperation.IntDivision(expression, literal, divisor))
            )
        else -> input
    }
}

private fun sameJavaValue(actual: PsiType, expected: PsiType): Boolean =
    if (actual is PsiPrimitiveType || expected is PsiPrimitiveType) actual == expected && actual != PsiTypes.voidType()
    else expected.isAssignableFrom(actual)

private fun unsupportedJavaBody(element: PsiElement): Refinement.Rejected<NativeJavaValueBody.Failure> =
    Refinement.Rejected(NativeJavaValueBody.Failure(ValueFlowUnsupportedCause.UNMODELED_CALL, element))

private fun unavailableJavaBody(element: PsiElement): Refinement.Rejected<NativeJavaValueBody.Failure> =
    Refinement.Rejected(NativeJavaValueBody.Failure(ValueFlowUnsupportedCause.EXTERNAL_CALL, element))

private sealed interface JavaValueInput {
    data class Read(val read: JavaValueRead) : JavaValueInput

    data object Literal : JavaValueInput

    data object Unresolved : JavaValueInput

    data object Unsupported : JavaValueInput
}
