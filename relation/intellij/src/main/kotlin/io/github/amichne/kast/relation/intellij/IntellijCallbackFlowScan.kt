package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
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
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtValueArgument

private data class CallbackScanFrame(
    val prepared: PreparedCallbackFlow,
    val forwardings: List<CallbackParameterForwarding>,
    val aliases: MutableMap<KtProperty, List<ValueTransfer>> = linkedMapOf(),
)

private data class CallbackValueRoute(val source: ValueSite, val transfers: List<ValueTransfer>)

/** One bounded mapped-body scan; aliases reuse the existing compiler-confirmed local value-flow facts. */
internal class IntellijCallbackFlowScan(
    private val context: IntellijCallbackFlowContext,
    private val prepared: PreparedCallbackFlow,
    private val body: RelationCallableBody.Anonymous,
) {
    private val invocations = mutableListOf<CallbackParameterInvocation>()
    private val obligations = linkedSetOf<CallbackInvocationFlowCause>()
    private val ownerBindings = linkedMapOf<RelationCallableBody.Anonymous, CallbackBodyBinding>()
    private val retention = CallbackFlowRetention(context.scope.request.budget)
    private var capacityExhausted = false
    private var scanExhausted = true

    fun read(): CallbackInvocationFlowRead {
        when (val origin = prepared.origin) {
            is PreparedCallbackOrigin.Argument -> {
                if (
                    origin.binding.invocationOwner !is RelationCallableBody.Named ||
                        origin.binding.invocationOwner.compilerIdentity !=
                            origin.binding.invocation.enclosing.compilerIdentity
                )
                    obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                observeOwnerBinding(origin.call, origin.binding.invocationOwner, origin.binding.invocation.enclosing)
            }
            is PreparedCallbackOrigin.Default -> Unit
        }
        scan(CallbackScanFrame(prepared, emptyList()))
        val complete =
            scanExhausted &&
                !capacityExhausted &&
                obligations.all {
                    it == CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                }
        if (invocations.isEmpty() && !complete) obligations += CallbackInvocationFlowCause.NO_INVOCATION_PROVEN
        val result =
            context.observed(
                body = body,
                binding = prepared.binding,
                invocations = invocations.toList(),
                obligations = obligations.toSet(),
                scan = if (complete) CallbackInvocationScan.EXHAUSTIVE else CallbackInvocationScan.INCOMPLETE,
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

    private fun scan(frame: CallbackScanFrame) {
        if (
            !PsiTreeUtil.processElements(
                frame.prepared.function,
                PsiElementProcessor<PsiElement> { visit(it, frame) },
            )
        )
            scanExhausted = false
    }

    private fun visit(element: PsiElement, frame: CallbackScanFrame): Boolean {
        ProgressManager.checkCanceled()
        if (capacityExhausted) return false
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> {
                obligations += allowed.failure
                return false
            }
        }
        if (element is KtNameReferenceExpression && eligible(element, frame)) observe(element, frame)
        return true
    }

    private fun eligible(expression: KtNameReferenceExpression, frame: CallbackScanFrame): Boolean =
        expression.getReferencedName() == frame.prepared.parameter.name ||
            frame.aliases.keys.any { it.name == expression.getReferencedName() }

    private fun observe(expression: KtNameReferenceExpression, frame: CallbackScanFrame) {
        val reference =
            expression.references
                .filterIsInstance<KtReference>()
                .filterNot { it is KtInvokeFunctionReference }
                .singleOrNull()
        val resolved = reference?.let(::resolveLocalValueReference) ?: NativeLocalValueReferenceResolution.Unresolved
        when (resolved) {
            NativeLocalValueReferenceResolution.Unresolved ->
                obligations += CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
            is NativeLocalValueReferenceResolution.Resolved -> observeResolved(expression, resolved.declaration, frame)
        }
    }

    private fun observeResolved(expression: KtNameReferenceExpression, resolved: PsiElement, frame: CallbackScanFrame) {
        val route =
            when {
                resolved === frame.prepared.parameter -> rootRoute(expression, frame)
                resolved is KtProperty && resolved in frame.aliases -> aliasRoute(expression, resolved, frame)
                else -> return
            }
        when (route) {
            is Refinement.Rejected -> obligations += route.failure
            is Refinement.Refined -> {
                val call = context.parameterInvocation(expression)
                if (call == null) recordSupply(expression, route.value, frame)
                else recordInvocation(call, route.value, frame)
            }
        }
    }

    private fun rootRoute(
        expression: KtNameReferenceExpression,
        frame: CallbackScanFrame,
    ): Refinement<CallbackValueRoute, CallbackInvocationFlowCause> {
        val source =
            context.site(expression, frame.prepared.target, ValueRole.ExpressionResult)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        return Refinement.Refined(CallbackValueRoute(source, emptyList()))
    }

    private fun aliasRoute(
        expression: KtNameReferenceExpression,
        property: KtProperty,
        frame: CallbackScanFrame,
    ): Refinement<CallbackValueRoute, CallbackInvocationFlowCause> {
        val source =
            context.site(expression, frame.prepared.target, ValueRole.LocalRead)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        if (valueNativeOwnership(expression, frame.prepared.function) != NativeValueOwnership.ADMITTED)
            return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val prior = frame.aliases.getValue(property)
        return when (
            val transfer = ValueTransfer.fromCompiler(prior.last().target, source, ValueTransferKind.LOCAL_READ)
        ) {
            is Refinement.Refined -> Refinement.Refined(CallbackValueRoute(source, prior + transfer.value))
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        }
    }

    private fun recordSupply(
        expression: KtNameReferenceExpression,
        route: CallbackValueRoute,
        frame: CallbackScanFrame,
    ) {
        var value: PsiElement = expression
        while (value.parent is KtParenthesizedExpression) value = value.parent
        if (value.parent !is KtValueArgument) {
            recordAlias(expression, route, frame)
            return
        }
        if (route.transfers.isNotEmpty()) {
            obligations += CallbackInvocationFlowCause.PARAMETER_ESCAPES
            return
        }
        when (
            val forwarded =
                IntellijCallbackForwardingReader(context)
                    .read(frame.prepared, expression, value.parent as KtValueArgument)
        ) {
            is CallbackForwardingRead.Unavailable -> obligations += forwarded.cause
            is CallbackForwardingRead.Observed -> recordForwarding(expression, frame, forwarded)
        }
    }

    private fun recordForwarding(
        expression: KtNameReferenceExpression,
        frame: CallbackScanFrame,
        forwarded: CallbackForwardingRead.Observed,
    ) {
        val destination = forwarded.prepared
        if (
            destination.parameter === prepared.parameter ||
                frame.forwardings.any {
                    it.source.callable.valueIdentity == destination.target.valueIdentity &&
                        it.source.parameter == forwarded.forwarding.target.parameter
                }
        ) {
            obligations += CallbackInvocationFlowCause.CALLBACK_CYCLE
            return
        }
        when (
            val allowed = retention.admit(4096L + forwarded.forwarding.target.invocation.resultSite().retainedBytes)
        ) {
            is Refinement.Rejected -> stopCapacity(allowed.failure)
            is Refinement.Refined -> {
                if (
                    forwarded.forwarding.target.invocationOwner !is RelationCallableBody.Named ||
                        forwarded.forwarding.target.invocationOwner.compilerIdentity !=
                            forwarded.forwarding.source.callable.compilerIdentity
                )
                    obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                observeOwnerBinding(expression, forwarded.forwarding.target.invocationOwner, frame.prepared.target)
                scan(CallbackScanFrame(destination, frame.forwardings + forwarded.forwarding))
            }
        }
    }

    private fun recordAlias(
        expression: KtNameReferenceExpression,
        route: CallbackValueRoute,
        frame: CallbackScanFrame,
    ) {
        val property = immutableProperty(expression, frame)
        val destination = property?.let { context.site(it, frame.prepared.target, ValueRole.LocalBinding) }
        if (property == null || destination == null) {
            obligations += CallbackInvocationFlowCause.PARAMETER_ESCAPES
            return
        }
        when (val transfer = ValueTransfer.fromCompiler(route.source, destination, ValueTransferKind.LOCAL_BINDING)) {
            is Refinement.Refined -> retainAlias(property, route, transfer.value, frame)
            is Refinement.Rejected -> obligations += CallbackInvocationFlowCause.PARAMETER_ESCAPES
        }
    }

    private fun retainAlias(
        property: KtProperty,
        route: CallbackValueRoute,
        transfer: ValueTransfer,
        frame: CallbackScanFrame,
    ) {
        val bytes = transfer.source.retainedBytes + transfer.target.retainedBytes + route.transfers.size * 16L
        when (val allowed = retention.admit(bytes)) {
            is Refinement.Refined -> frame.aliases[property] = route.transfers + transfer
            is Refinement.Rejected -> stopCapacity(allowed.failure)
        }
    }

    private fun recordInvocation(call: KtCallExpression, route: CallbackValueRoute, frame: CallbackScanFrame) {
        val occurrence = context.occurrence(call.valueInvocationExpression())
        val owner = context.owner(call)
        if (occurrence == null || owner == null) {
            obligations += CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
            return
        }
        when (
            val invocation =
                CallbackParameterInvocation.fromCompiler(occurrence, owner, route.transfers, frame.forwardings)
        ) {
            is Refinement.Refined -> retainInvocation(invocation.value)
            is Refinement.Rejected -> obligations += CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
        }
        if (
            owner !is RelationCallableBody.Named ||
                owner.evidence.compilerIdentity != frame.prepared.target.compilerIdentity
        )
            obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
        observeOwnerBinding(call, owner, frame.prepared.target)
    }

    private fun retainInvocation(invocation: CallbackParameterInvocation) {
        val bytes = invocation.retainedBytes
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
        val literal = (element.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunction
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

    private fun immutableProperty(expression: KtNameReferenceExpression, frame: CallbackScanFrame): KtProperty? {
        var current: PsiElement = expression
        while (current.parent is KtParenthesizedExpression) current = current.parent
        val property = current.parent as? KtProperty ?: return null
        if (property.initializer !== current || !property.isLocal || property.isVar) return null
        return property.takeIf { valueNativeOwnership(it, frame.prepared.function) == NativeValueOwnership.ADMITTED }
    }
}
