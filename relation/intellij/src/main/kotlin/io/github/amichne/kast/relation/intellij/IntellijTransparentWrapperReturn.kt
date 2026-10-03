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
    val body =
        when (val expression = callable.bodyExpression) {
            is KtBlockExpression -> (expression.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
            else -> expression
        }
            as? KtNameReferenceExpression ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    // Resolution compares actual parameter PSI in this native read, not spelling, signature or formal type.
    val same =
        analyze(body) {
            val reference = body.references.filterIsInstance<KtReference>().singleOrNull()
            reference?.resolveToSymbol()?.psi === parameter
        }
    if (!same) return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    return Refinement.Refined(Unit)
}
