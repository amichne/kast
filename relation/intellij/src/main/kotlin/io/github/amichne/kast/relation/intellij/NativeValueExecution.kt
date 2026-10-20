package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal data class NativeValueExecution(val nativeOwner: PsiElement, val owner: ValueExecutionOwner)

/** Every fresh read reacquires the nearest execution body under the exact retained lexical declaration. */
internal fun nativeValueExecutionOwner(
    element: PsiElement,
    host: PsiElement,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    observation: IntellijReadObservation,
): Refinement<NativeValueExecution, ValueFlowRejection> {
    var anonymous: KtFunction? = null
    var current: PsiElement? = element
    while (current != null) {
        if (current === host)
            return admittedExecution(
                anonymous = anonymous,
                host = host,
                enclosing = enclosing,
                scope = scope,
                observation = observation,
            )
        when (val step = current.executionStep()) {
            NativeExecutionStep.Ancestor -> Unit
            is NativeExecutionStep.Anonymous -> {
                if (anonymous == null) anonymous = step.function
            }
            NativeExecutionStep.Rejected -> return Refinement.Rejected(ValueFlowRejection.NESTED_EXECUTION)
        }
        current = current.parent
    }
    return Refinement.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE)
}

private sealed interface NativeExecutionStep {
    data object Ancestor : NativeExecutionStep

    data class Anonymous(val function: KtFunction) : NativeExecutionStep

    data object Rejected : NativeExecutionStep
}

private fun PsiElement.executionStep(): NativeExecutionStep =
    when (this) {
        is KtNamedFunction -> if (name == null) NativeExecutionStep.Anonymous(this) else NativeExecutionStep.Rejected
        is KtFunctionLiteral -> NativeExecutionStep.Anonymous(this)
        is KtClassOrObject -> NativeExecutionStep.Rejected
        is KtProperty -> if (isLocal) NativeExecutionStep.Ancestor else NativeExecutionStep.Rejected
        else -> NativeExecutionStep.Ancestor
    }

private fun admittedExecution(
    anonymous: KtFunction?,
    host: PsiElement,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    observation: IntellijReadObservation,
): Refinement<NativeValueExecution, ValueFlowRejection> {
    if (anonymous == null)
        return Refinement.Refined(NativeValueExecution(host, ValueExecutionOwner.declaration(enclosing)))
    val body =
        nativeAnonymousCallableBody(anonymous, scope, observation)
            ?: return Refinement.Rejected(ValueFlowRejection.NESTED_EXECUTION)
    return when (val admitted = ValueExecutionOwner.fromCompiler(enclosing, body, enclosing.lease.identity)) {
        is Refinement.Refined -> Refinement.Refined(NativeValueExecution(anonymous, admitted.value))
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE)
    }
}
