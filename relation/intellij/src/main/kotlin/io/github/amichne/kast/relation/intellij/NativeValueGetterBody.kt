@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtReturnExpression

/** A bounded getter body proves a read, not a storage alias for the property being invoked. */
internal data class NativeValueGetterBody(val expression: KtNameReferenceExpression, val parameter: KtParameter)

internal fun nativeGetterBody(
    property: KtProperty,
    projection: IntellijK2RelationProjection,
): Refinement<NativeValueGetterBody, ValueFlowUnsupportedCause> {
    val getter = property.getter ?: return getterBodyRejected()
    return projection.observation.observedAnalyze(getter) {
        val expression = getterExpression(getter) ?: return@observedAnalyze getterBodyRejected()
        getterField(expression, property)
    }
}

/** A sole explicit return has the same bounded effect budget as an expression body. */
private fun KaSession.getterExpression(getter: KtPropertyAccessor): KtNameReferenceExpression? {
    val body = getter.bodyExpression ?: return null
    if (body !is KtBlockExpression) return body as? KtNameReferenceExpression
    val returned = body.statements.singleOrNull() as? KtReturnExpression ?: return null
    return if (returned.resolveSymbol()?.psi?.originalElement == getter.originalElement)
        returned.returnedExpression as? KtNameReferenceExpression
    else null
}

private fun KaSession.getterField(
    expression: KtNameReferenceExpression,
    property: KtProperty,
): Refinement<NativeValueGetterBody, ValueFlowUnsupportedCause> {
    val call = expression.resolveCall() ?: return getterBodyRejected()
    val field = call.signature.symbol as? KaKotlinPropertySymbol ?: return getterBodyRejected()
    val parameter = field.psi as? KtParameter ?: return getterBodyRejected()
    val owner = parameter.ownerFunction?.parent as? KtClass ?: return getterBodyRejected()
    if (
        parameter !in owner.primaryConstructorParameters ||
            call.extensionReceiver != null ||
            field.returnType is KaErrorType
    )
        return getterBodyRejected()
    val receiver = call.dispatchReceiver as? KaImplicitReceiverValue
    val getterOwner = property.symbol.containingDeclaration
    if (field.containingDeclaration != getterOwner || receiver?.symbol != getterOwner) return getterBodyRejected()
    return when ((parameter.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty) {
        field -> Refinement.Refined(NativeValueGetterBody(expression, parameter))
        else -> getterBodyRejected()
    }
}

private fun getterBodyRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
