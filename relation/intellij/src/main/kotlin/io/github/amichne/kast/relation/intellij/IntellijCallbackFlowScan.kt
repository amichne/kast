package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CompleteCallbackForwardingGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtValueArgument

internal data class CallbackScanFrame(
    val prepared: PreparedCallbackFlow,
    val forwardings: List<CallbackParameterForwarding>,
    val aliases: MutableMap<KtProperty, List<ValueTransfer>> = linkedMapOf(),
)

/** One bounded mapped-body scan; aliases reuse the existing compiler-confirmed local value-flow facts. */
internal class IntellijCallbackFlowScan(
    private val context: IntellijCallbackFlowContext,
    private val prepared: PreparedCallbackFlow,
    private val summaries: CallbackParameterSummaries,
) {
    private val routes = CallbackValueRoutes(context)
    private val invocations = mutableListOf<CallbackParameterInvocation>()
    private val obligations = linkedSetOf<CallbackInvocationFlowCause>()
    private val ownerBindings = linkedMapOf<RelationCallableBody.Anonymous, CallbackBodyBinding>()
    private val retention = summaries.retention
    private val forwardings = linkedSetOf<CallbackParameterForwarding>()
    private var capacityExhausted = false
    private var scanExhausted = true

    fun read(
        formal: CallbackParameterIdentity
    ): Refinement<CallbackFormalScanSnapshot, io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure> =
        when (val proof = scanGraph(formal)) {
            is Refinement.Rejected -> proof
            is Refinement.Refined ->
                Refinement.Refined(
                    CallbackFormalScanSnapshot(
                        invocations.toList(),
                        obligations.toSet(),
                        ownerBindings.values.toList(),
                        scanProof(),
                        proof.value,
                    )
                )
        }

    private fun scanGraph(
        formal: CallbackParameterIdentity
    ): Refinement<CallbackForwardingEvidence, io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure> {
        when (val allowed = retention.admitFormal(formal)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> {
                stopCapacity(allowed.failure)
                summaries.observation.count(IntellijReadCounter.CALLBACK_FIXED_POINTS_REJECTED)
                return Refinement.Refined(CallbackForwardingEvidence.InvocationRoutes)
            }
        }
        val worklist = CallbackFormalWorklist(formal, CallbackScanFrame(prepared, emptyList()))
        summaries.observation.count(IntellijReadCounter.CALLBACK_FORWARDING_FORMALS)
        var work = worklist.next()
        while (work is CallbackFormalWork.Pending && scanExhausted && !capacityExhausted) {
            scan(work.value, worklist)
            work = worklist.next()
        }
        if (scanProof() != CallbackInvocationScan.EXHAUSTIVE) {
            summaries.observation.count(IntellijReadCounter.CALLBACK_FIXED_POINTS_REJECTED)
            return Refinement.Refined(CallbackForwardingEvidence.InvocationRoutes)
        }
        return when (
            val graph =
                CompleteCallbackForwardingGraph.fromCompiler(
                    formal,
                    worklist.formals,
                    forwardings.toList(),
                    scanProof(),
                    obligations,
                )
        ) {
            is Refinement.Refined -> {
                summaries.observation.count(IntellijReadCounter.CALLBACK_FIXED_POINTS_COMPLETED)
                Refinement.Refined(CallbackForwardingEvidence.ExhaustedGraph(graph.value))
            }
            is Refinement.Rejected -> {
                summaries.observation.count(IntellijReadCounter.CALLBACK_FIXED_POINTS_REJECTED)
                graph
            }
        }
    }

    private fun scanProof(): CallbackInvocationScan =
        if (
            scanExhausted &&
                !capacityExhausted &&
                obligations.all { it == CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }
        )
            CallbackInvocationScan.EXHAUSTIVE
        else CallbackInvocationScan.INCOMPLETE

    private fun scan(frame: CallbackScanFrame, worklist: CallbackFormalWorklist<CallbackScanFrame>) {
        when (
            observeCallbackBodyScan(summaries.observation) {
                PsiTreeUtil.processElements(
                    frame.prepared.function,
                    PsiElementProcessor<PsiElement> { visit(it, frame, worklist) },
                )
            }
        ) {
            CallbackBodyScanOutcome.EXHAUSTED -> Unit
            CallbackBodyScanOutcome.STOPPED -> scanExhausted = false
        }
    }

    private fun visit(
        element: PsiElement,
        frame: CallbackScanFrame,
        worklist: CallbackFormalWorklist<CallbackScanFrame>,
    ): Boolean {
        ProgressManager.checkCanceled()
        if (capacityExhausted) return false
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> {
                obligations += allowed.failure
                return false
            }
        }
        if (element is KtNameReferenceExpression && routes.eligible(element, frame)) observe(element, frame, worklist)
        return true
    }

    private fun observe(
        expression: KtNameReferenceExpression,
        frame: CallbackScanFrame,
        worklist: CallbackFormalWorklist<CallbackScanFrame>,
    ) {
        val reference =
            expression.references
                .filterIsInstance<KtReference>()
                .filterNot { it is KtInvokeFunctionReference }
                .singleOrNull()
        val resolved = reference?.let(::resolveLocalValueReference) ?: NativeLocalValueReferenceResolution.Unresolved
        when (resolved) {
            NativeLocalValueReferenceResolution.Unresolved ->
                obligations += CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE
            is NativeLocalValueReferenceResolution.Resolved ->
                observeResolved(expression, resolved.declaration, frame, worklist)
        }
    }

    private fun observeResolved(
        expression: KtNameReferenceExpression,
        resolved: PsiElement,
        frame: CallbackScanFrame,
        worklist: CallbackFormalWorklist<CallbackScanFrame>,
    ) {
        val route =
            when {
                resolved === frame.prepared.parameter -> routes.root(expression, frame)
                resolved is KtProperty && resolved in frame.aliases -> routes.alias(expression, resolved, frame)
                else -> return
            }
        when (route) {
            is Refinement.Rejected -> obligations += route.failure
            is Refinement.Refined -> {
                val call = context.parameterInvocation(expression)
                if (call == null) recordSupply(expression, route.value, frame, worklist)
                else recordInvocation(call, route.value, frame)
            }
        }
    }

    private fun recordSupply(
        expression: KtNameReferenceExpression,
        route: CallbackValueRoute,
        frame: CallbackScanFrame,
        worklist: CallbackFormalWorklist<CallbackScanFrame>,
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
            is CallbackForwardingRead.Observed -> recordForwarding(expression, frame, forwarded, worklist)
        }
    }

    private fun recordForwarding(
        expression: KtNameReferenceExpression,
        frame: CallbackScanFrame,
        forwarded: CallbackForwardingRead.Observed,
        worklist: CallbackFormalWorklist<CallbackScanFrame>,
    ) {
        val destination = forwarded.prepared
        if (forwarded.forwarding in forwardings) return
        val formal =
            when (val admitted = destination.formal()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> {
                    obligations += admitted.failure
                    return
                }
            }
        when (val allowed = retention.admitForwarding(forwarded.forwarding)) {
            is Refinement.Rejected -> stopCapacity(allowed.failure)
            is Refinement.Refined -> {
                if (
                    forwarded.forwarding.target.invocationOwner !is RelationCallableBody.Named ||
                        forwarded.forwarding.target.invocationOwner.compilerIdentity !=
                            forwarded.forwarding.source.callable.compilerIdentity
                )
                    obligations += CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
                observeOwnerBinding(expression, forwarded.forwarding.target.invocationOwner, frame.prepared.target)
                forwardings += forwarded.forwarding
                summaries.observation.count(IntellijReadCounter.CALLBACK_FORWARDING_EDGES)
                when (
                    worklist.schedule(formal, CallbackScanFrame(destination, frame.forwardings + forwarded.forwarding))
                ) {
                    CallbackFormalSchedule.SCHEDULED ->
                        summaries.observation.count(IntellijReadCounter.CALLBACK_FORWARDING_FORMALS)
                    CallbackFormalSchedule.ALREADY_DISCOVERED -> Unit
                }
            }
        }
    }

    private fun recordAlias(
        expression: KtNameReferenceExpression,
        route: CallbackValueRoute,
        frame: CallbackScanFrame,
    ) {
        val property = routes.immutableProperty(expression, frame)
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
        when (val allowed = retention.admitInvocation(invocation)) {
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
                when (val permitted = retention.admitOwner(read.value)) {
                    is Refinement.Refined -> {
                        ownerBindings[owner] = read.value
                        obligations += read.value.obligations
                    }
                    is Refinement.Rejected -> stopCapacity(permitted.failure)
                }
        }
    }
}
