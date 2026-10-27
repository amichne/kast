package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import org.jetbrains.kotlin.psi.KtForExpression

/** Reconstructs the selected synthetic result instead of conflating it with the loop's Unit expression. */
internal fun nativeInvocationResultSite(
    invocation: ValueInvocation,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueSite, ValueFlowUnsupportedCause> {
    when (val allowed = permit()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return allowed
    }
    val host =
        when (val found = projection.subject(scope, invocation.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> found.declaration
            is IntellijRelationSubjectLookup.Rejected -> return resultRejected()
        }
    val element = host.exactValueElement(invocation.range) ?: return resultRejected()
    if (element !is KtForExpression) return stringOrOrdinaryResult(element, invocation, scope, projection)
    val result =
        when (val admitted = nativeLoopInvocationResult(element, invocation, scope, projection, permit)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return when (
        val site =
            ValueSite.fromCompiler(
                enclosing = invocation.enclosing,
                range = invocation.range,
                role = ValueRole.LoopResult(result),
                executionOwner = invocation.executionOwner,
            )
    ) {
        is Refinement.Refined -> site
        is Refinement.Rejected -> resultRejected()
    }
}

private fun stringOrOrdinaryResult(
    element: org.jetbrains.kotlin.psi.KtExpression,
    invocation: ValueInvocation,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueSite, ValueFlowUnsupportedCause> {
    if (element.parent !is org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression)
        return Refinement.Refined(invocation.resultSite())
    val selected = nativeStringConversionDeclaration(element, projection)
    if (selected !is Refinement.Refined) return Refinement.Refined(invocation.resultSite())
    val endpoint = nativeValueCallableEndpoint(selected.value, invocation.enclosing, scope, projection)
    if (endpoint !is Refinement.Refined || endpoint.value.valueIdentity != invocation.callable.valueIdentity)
        return Refinement.Refined(invocation.resultSite())
    return when (
        val site =
            ValueSite.fromCompiler(
                enclosing = invocation.enclosing,
                range = invocation.range,
                role = ValueRole.StringConversionResult(invocation),
                executionOwner = invocation.executionOwner,
            )
    ) {
        is Refinement.Refined -> site
        is Refinement.Rejected -> resultRejected()
    }
}

private fun resultRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
