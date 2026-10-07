package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression

/** Bounded native proof attached to callback observations without changing named-call ownership. */
internal fun readCallbackInvocationFlow(
    boundary: PsiElement,
    lexicalOwner: CompilerGroundedSymbolEvidence,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    admitWork: () -> CallbackWorkAdmission,
    summaries: CallbackParameterSummaries,
): CallbackInvocationFlowRead {
    val context = IntellijCallbackFlowContext(scope, projection, admitWork)
    when (val allowed = context.permit()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return CallbackInvocationFlowRead.Unavailable(allowed.failure)
    }
    val literal =
        boundary as? KtFunction
            ?: return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    val body =
        context.anonymous(literal)
            ?: return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    return NativeCallbackInvocationFlow(context, lexicalOwner, summaries, literal, body).read()
}

private class NativeCallbackInvocationFlow(
    private val context: IntellijCallbackFlowContext,
    private val lexicalOwner: CompilerGroundedSymbolEvidence,
    private val summaries: CallbackParameterSummaries,
    private val literal: KtFunction,
    private val body: RelationCallableBody.Anonymous,
) {
    fun read(): CallbackInvocationFlowRead =
        when (val prepared = IntellijCallbackBindingReader(context, lexicalOwner).prepare(literal)) {
            is CallbackBindingPreparation.Unavailable -> unavailable(prepared.cause)
            is CallbackBindingPreparation.ContractRejected ->
                CallbackInvocationFlowRead.ContractRejected(prepared.cause)
            is CallbackBindingPreparation.Direct -> readDirectCallbackFlow(context, prepared, body, lexicalOwner)
            is CallbackBindingPreparation.Prepared -> prepared(prepared.value)
        }

    private fun unavailable(cause: CallbackInvocationFlowCause): CallbackInvocationFlowRead {
        if (
            cause !in
                setOf(
                    CallbackInvocationFlowCause.STORED_CALLBACK,
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                    CallbackInvocationFlowCause.RETURNED_CALLBACK,
                )
        )
            return unavailableCallbackSupply(context, literal, body, lexicalOwner, cause)
        val expression = expression() ?: return unavailableCallbackSupply(context, literal, body, lexicalOwner, cause)
        return immutable(expression)
    }

    private fun prepared(value: PreparedCallbackFlow): CallbackInvocationFlowRead {
        if (value.origin is PreparedCallbackOrigin.Default) {
            return when (
                val read =
                    IntellijImmutableCallbackFlowReader(context, summaries)
                        .readDefault(value, ImmutableCallbackValueOrigin.Anonymous(body))
            ) {
                is Refinement.Refined -> CallbackInvocationFlowRead.Immutable(read.value)
                is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(read.failure)
            }
        }
        if (!returnsCallable(value.function)) return IntellijCallbackFlowRead(context, value, body, summaries).read()
        val expression =
            expression()
                ?: return CallbackInvocationFlowRead.Unavailable(
                    CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
                )
        return immutable(expression)
    }

    private fun immutable(expression: KtExpression): CallbackInvocationFlowRead =
        when (
            val read =
                IntellijImmutableCallbackFlowReader(context, summaries)
                    .read(expression, lexicalOwner, ImmutableCallbackValueOrigin.Anonymous(body))
        ) {
            is Refinement.Refined -> CallbackInvocationFlowRead.Immutable(read.value)
            is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(read.failure)
        }

    private fun expression(): KtExpression? =
        if (literal is KtFunctionLiteral) literal.parent as? KtLambdaExpression else literal as? KtExpression
}
