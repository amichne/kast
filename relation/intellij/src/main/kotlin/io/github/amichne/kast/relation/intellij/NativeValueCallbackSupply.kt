package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal data class NativeValueCallbackSupply(
    val literal: KtFunction,
    val lexicalOwner: CompilerGroundedSymbolEvidence,
    val supplyingFrame: ValueCallFrame,
)

/** The existing callback formal reader identifies the one explicit supply under the active source frame. */
internal class NativeValueCallbackSupplies(
    private val source: ValueSite,
    private val role: ValueRole.FunctionArgument,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val context: IntellijCallbackFlowContext,
) {
    fun read(): Refinement<NativeValueCallbackSupply, CallbackInvocationFlowCause> {
        val frame = source.callStack.frames.lastOrNull() ?: return unavailable()
        if (frame.callee != role.call.executionOwner) return unavailable()
        val supplyingCall = frame.invocation as? ValueInvocation ?: return unavailable()
        val formal =
            when (val value = readFormal()) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        if (formal.formal.callable.valueIdentity != frame.callee.enclosing.valueIdentity) return unavailable()
        return suppliedLiteral(supplyingCall, formal, frame)
    }

    internal fun readFormal(): Refinement<NativeCallbackSupplierFormal.Found, CallbackInvocationFlowCause> {
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val host =
            when (val value = projection.subject(scope, role.call.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> value
                is IntellijRelationSubjectLookup.Rejected -> return unavailable()
            }
        val call =
            host.declaration.exactValueElement(role.call.range).asCallbackCall() as? KtCallExpression
                ?: return unavailable()
        val actual = host.declaration.exactValueElement(source.range) ?: return unavailable()
        val mapped =
            when (val value = nativeMappedArgumentBinding(call, actual, context.observation)) {
                is Refinement.Refined -> value.value as? NativeMappedArgument.Function ?: return unavailable()
                is Refinement.Rejected -> return unavailable()
            }
        val fresh = nativeFunctionArgument(call, mapped, source.executionOwner)
        if (fresh !is Refinement.Refined || fresh.value != role) return unavailable()
        val receiver = callbackInvocationReceiver(call) ?: return unavailable()
        val formal =
            when (val value = IntellijCallbackSupplierFormal(context).read(receiver, host.evidence)) {
                is Refinement.Refined -> value.value as? NativeCallbackSupplierFormal.Found ?: return unavailable()
                is Refinement.Rejected -> return value
            }
        return Refinement.Refined(formal)
    }

    internal fun suppliedLiteral(
        invocation: ValueInvocation,
        formal: NativeCallbackSupplierFormal.Found,
        frame: ValueCallFrame,
    ): Refinement<NativeValueCallbackSupply, CallbackInvocationFlowCause> {
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val host =
            when (val value = projection.subject(scope, invocation.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> value
                is IntellijRelationSubjectLookup.Rejected -> return unavailable()
            }
        val call = host.declaration.exactValueElement(invocation.range).asCallbackCall() ?: return unavailable()
        val expression =
            when (val value = nativeSuppliedArgument(call, formal.parameter, context.observation)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return unavailable()
            }
        val literal =
            when (val value = literal(expression)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        when (val admitted = context.providerSite { literal.containingFile.virtualFile }) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined ->
                when (admitted.value) {
                    RelationProviderScopeAdmission.ADMITTED -> Unit
                    RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED ->
                        return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
                    RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED ->
                        return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
                    RelationProviderScopeAdmission.UNAVAILABLE -> return unavailable()
                }
        }
        return Refinement.Refined(NativeValueCallbackSupply(literal, host.evidence, frame))
    }

    private fun literal(expression: KtExpression): Refinement<KtFunction, CallbackInvocationFlowCause> {
        var unwrapped = expression
        while (unwrapped is KtParenthesizedExpression) {
            when (val allowed = context.permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            unwrapped = unwrapped.expression ?: return unavailable()
        }
        return when (unwrapped) {
            is KtLambdaExpression -> Refinement.Refined(unwrapped.functionLiteral)
            is KtNamedFunction -> if (unwrapped.name == null) Refinement.Refined(unwrapped) else unavailable()
            else -> unavailable()
        }
    }

    private fun unavailable() = Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
}

internal fun com.intellij.psi.PsiElement?.asCallbackCall(): KtCallElement? =
    when (this) {
        is KtCallElement -> this
        is KtQualifiedExpression -> selectorExpression as? KtCallElement
        else -> null
    }
