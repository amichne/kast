package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty

private data class CallbackValueRoute(val source: ValueSite, val transfers: List<ValueTransfer>)

/** One bounded mapped-body scan; aliases reuse the existing compiler-confirmed local value-flow facts. */
internal class IntellijCallbackFlowScan(
    private val context: IntellijCallbackFlowContext,
    private val prepared: PreparedCallbackFlow,
    private val body: RelationCallableBody.Anonymous,
) {
    private val invocations = mutableListOf<CallbackParameterInvocation>()
    private val obligations = linkedSetOf<CallbackInvocationFlowCause>()
    private val aliases = linkedMapOf<KtProperty, List<ValueTransfer>>()
    private val ownerBindings = linkedMapOf<RelationCallableBody.Anonymous, CallbackBodyBinding>()
    private val retention = CallbackFlowRetention(context.scope.request.budget)
    private var capacityExhausted = false

    fun read(): CallbackInvocationFlowRead {
        if (prepared.binding.invocationOwner is RelationCallableBody.Anonymous)
            obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
        observeOwnerBinding(
            prepared.supplyingCall,
            prepared.binding.invocationOwner,
            prepared.binding.invocation.enclosing,
        )
        PsiTreeUtil.processElements(prepared.function, PsiElementProcessor<PsiElement>(::visit))
        if (invocations.isEmpty()) obligations += CallbackInvocationFlowCause.NO_INVOCATION_PROVEN
        val result =
            context.observed(
                body = body,
                binding = CallbackBindingEvidence.Bound(prepared.binding),
                invocations = invocations.toList(),
                obligations = obligations.toSet(),
            )
        return when (result) {
            is CallbackInvocationFlowRead.Observed ->
                when (val refined = result.flow.withOwnerBindings(ownerBindings.values.toList())) {
                    is Refinement.Refined -> CallbackInvocationFlowRead.Observed(refined.value)
                    is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(refined.failure)
                }
            is CallbackInvocationFlowRead.Unavailable,
            is CallbackInvocationFlowRead.ContractRejected -> result
        }
    }

    private fun visit(element: PsiElement): Boolean {
        ProgressManager.checkCanceled()
        if (capacityExhausted) return false
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> {
                obligations += allowed.failure
                return false
            }
        }
        if (element is KtNameReferenceExpression && eligible(element)) observe(element)
        return true
    }

    private fun eligible(expression: KtNameReferenceExpression): Boolean =
        expression.getReferencedName() == prepared.parameter.name ||
            aliases.keys.any { it.name == expression.getReferencedName() }

    private fun observe(expression: KtNameReferenceExpression) {
        val reference =
            expression.references
                .filterIsInstance<KtReference>()
                .filterNot { it is KtInvokeFunctionReference }
                .singleOrNull()
        val resolved = reference?.let(::resolveLocalValueReference) ?: NativeLocalValueReferenceResolution.Unresolved
        when (resolved) {
            NativeLocalValueReferenceResolution.Unresolved ->
                obligations += CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
            is NativeLocalValueReferenceResolution.Resolved -> observeResolved(expression, resolved.declaration)
        }
    }

    private fun observeResolved(expression: KtNameReferenceExpression, resolved: PsiElement) {
        val route =
            when {
                resolved === prepared.parameter -> rootRoute(expression)
                resolved is KtProperty && resolved in aliases -> aliasRoute(expression, resolved)
                else -> return
            }
        when (route) {
            is Refinement.Rejected -> obligations += route.failure
            is Refinement.Refined -> {
                val call = context.parameterInvocation(expression)
                if (call == null) recordAlias(expression, route.value) else recordInvocation(call, route.value)
            }
        }
    }

    private fun rootRoute(
        expression: KtNameReferenceExpression
    ): Refinement<CallbackValueRoute, CallbackInvocationFlowCause> {
        val source =
            context.site(expression, prepared.target, ValueRole.ExpressionResult)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        return Refinement.Refined(CallbackValueRoute(source, emptyList()))
    }

    private fun aliasRoute(
        expression: KtNameReferenceExpression,
        property: KtProperty,
    ): Refinement<CallbackValueRoute, CallbackInvocationFlowCause> {
        val source =
            context.site(expression, prepared.target, ValueRole.LocalRead)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        if (valueNativeOwnership(expression, prepared.function) != NativeValueOwnership.ADMITTED)
            return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val prior = aliases.getValue(property)
        return when (
            val transfer = ValueTransfer.fromCompiler(prior.last().target, source, ValueTransferKind.LOCAL_READ)
        ) {
            is Refinement.Refined -> Refinement.Refined(CallbackValueRoute(source, prior + transfer.value))
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        }
    }

    private fun recordAlias(expression: KtNameReferenceExpression, route: CallbackValueRoute) {
        val property = immutableProperty(expression)
        val destination = property?.let { context.site(it, prepared.target, ValueRole.LocalBinding) }
        if (property == null || destination == null) {
            obligations += CallbackInvocationFlowCause.PARAMETER_ESCAPES
            return
        }
        when (val transfer = ValueTransfer.fromCompiler(route.source, destination, ValueTransferKind.LOCAL_BINDING)) {
            is Refinement.Refined -> retainAlias(property, route, transfer.value)
            is Refinement.Rejected -> obligations += CallbackInvocationFlowCause.PARAMETER_ESCAPES
        }
    }

    private fun retainAlias(property: KtProperty, route: CallbackValueRoute, transfer: ValueTransfer) {
        val bytes = transfer.source.retainedBytes + transfer.target.retainedBytes + route.transfers.size * 16L
        when (val allowed = retention.admit(bytes)) {
            is Refinement.Refined -> aliases[property] = route.transfers + transfer
            is Refinement.Rejected -> stopCapacity(allowed.failure)
        }
    }

    private fun recordInvocation(call: KtCallExpression, route: CallbackValueRoute) {
        val occurrence = context.occurrence(call.valueInvocationExpression())
        val owner = context.owner(call)
        if (occurrence == null || owner == null) {
            obligations += CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
            return
        }
        when (val invocation = CallbackParameterInvocation.fromCompiler(occurrence, owner, route.transfers)) {
            is Refinement.Refined -> retainInvocation(invocation.value)
            is Refinement.Rejected -> obligations += CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
        }
        if (owner !is RelationCallableBody.Named || owner.evidence.compilerIdentity != prepared.target.compilerIdentity)
            obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
        observeOwnerBinding(call, owner, prepared.target)
    }

    private fun retainInvocation(invocation: CallbackParameterInvocation) {
        val bytes = 4096L + invocation.callableTransfers.size * 16L
        when (val allowed = retention.admit(bytes)) {
            is Refinement.Refined -> invocations += invocation
            is Refinement.Rejected -> stopCapacity(allowed.failure)
        }
    }

    private fun stopCapacity(cause: CallbackInvocationFlowCause) {
        obligations += cause
        capacityExhausted = true
    }

    private fun observeOwnerBinding(element: PsiElement, owner: RelationCallableBody, enclosing: RelationEndpoint) {
        if (capacityExhausted || owner !is RelationCallableBody.Anonymous || owner in ownerBindings) return
        val literal = (element.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunctionLiteral
        if (literal == null) {
            obligations += CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
            return
        }
        when (val read = IntellijCallbackOwnerBindingReader(context).read(literal, owner, enclosing)) {
            is Refinement.Rejected -> obligations += CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
            is Refinement.Refined ->
                when (val permitted = retention.admit(read.value.retainedBytes)) {
                    is Refinement.Refined -> {
                        ownerBindings[owner] = read.value
                        obligations += read.value.obligations
                    }
                    is Refinement.Rejected -> stopCapacity(permitted.failure)
                }
        }
    }

    private fun immutableProperty(expression: KtNameReferenceExpression): KtProperty? {
        var current: PsiElement = expression
        while (current.parent is KtParenthesizedExpression) current = current.parent
        val property = current.parent as? KtProperty ?: return null
        if (property.initializer !== current || !property.isLocal || property.isVar) return null
        return property.takeIf { valueNativeOwnership(it, prepared.function) == NativeValueOwnership.ADMITTED }
    }
}
