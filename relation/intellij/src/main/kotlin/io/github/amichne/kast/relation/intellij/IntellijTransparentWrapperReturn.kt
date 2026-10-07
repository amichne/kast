@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression

/** Only a compiler-confirmed formal parameter returned directly by one body expression is transparent. */
internal fun nativeTransparentWrapperReturn(
    argument: ValueRole.Argument,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val callable =
        when (val native = projection.subject(scope, argument.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> native.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    val parameter =
        callable.valueParameters.getOrNull(argument.position.value)
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    return when (val returned = nativeTransparentReturnedParameter(callable)) {
        NativeTransparentReturnedParameter.NotTransparent ->
            Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
        NativeTransparentReturnedParameter.Unresolved ->
            Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        is NativeTransparentReturnedParameter.Formal ->
            if (returned.parameter.originalElement == parameter.originalElement) Refinement.Refined(Unit)
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    }
}

internal sealed interface NativeTransparentReturnedParameter {
    data object NotTransparent : NativeTransparentReturnedParameter

    data object Unresolved : NativeTransparentReturnedParameter

    data class Formal(val parameter: org.jetbrains.kotlin.psi.KtParameter) : NativeTransparentReturnedParameter
}

/** One syntactic return and an exact compiler formal target; branches and local aliases require separate proofs. */
internal fun nativeTransparentReturnedParameter(callable: KtNamedFunction): NativeTransparentReturnedParameter {
    var expression =
        when (val body = callable.bodyExpression) {
            is KtBlockExpression -> (body.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
            else -> body
        } ?: return NativeTransparentReturnedParameter.NotTransparent
    while (expression is org.jetbrains.kotlin.psi.KtParenthesizedExpression) expression =
        expression.expression ?: return NativeTransparentReturnedParameter.Unresolved
    val read = expression as? KtNameReferenceExpression ?: return NativeTransparentReturnedParameter.NotTransparent
    val declaration =
        analyze(read) {
            val reference = read.references.filterIsInstance<KtReference>().singleOrNull()
            reference?.resolveToSymbol()?.psi
        } ?: return NativeTransparentReturnedParameter.Unresolved
    val parameter =
        callable.valueParameters.singleOrNull { it.originalElement == declaration.originalElement }
            ?: return NativeTransparentReturnedParameter.NotTransparent
    return NativeTransparentReturnedParameter.Formal(parameter)
}
