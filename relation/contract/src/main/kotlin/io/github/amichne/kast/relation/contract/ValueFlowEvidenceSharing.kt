package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.IdentityHashMap

/**
 * One step shares full proofs and rewrites each physical site once. Reviewed applications keep their existing owner.
 */
internal class ValueFlowEvidenceSharing(previous: Collection<ValueFlowStep>, private val shareEndpoints: Boolean) {
    private val endpoints = ValueEndpointEvidenceSharing(previous)
    private val callables = linkedMapOf<RelationEndpoint.Resolved, ValueInvocation>()
    private val invocations = IdentityHashMap<ValueInvocation, ValueInvocation>()
    private val sites = IdentityHashMap<ValueSite, ValueSite>()
    private val reviewedFrames = IdentityHashMap<ValueCallFrame, ValueCallFrame>()
    private val bindings = IdentityHashMap<ValueModelApplication, ReviewedLibraryBinding>()
    private val modelApplications = IdentityHashMap<ValueModelApplication, ValueModelApplication>()

    init {
        for (step in previous) {
            retain(step.source)
            step.transfers.forEach { retain(it.target) }
            step.applications.forEach { retain(it.invocation) }
        }
    }

    fun site(original: ValueSite): ValueSite =
        sites.getOrPut(original) {
            val site = if (shareEndpoints) endpoints.site(original) else original
            val shared = site.shareCallableEvidence(::invocation)
            val stack = stack(shared.callStack)
            if (stack === shared.callStack) shared else shared.withState(callStack = stack).sharedProof()
        }

    fun evidence(original: ValueTransferEvidence): ValueTransferEvidence =
        if (shareEndpoints) endpoints.evidence(original) else original

    fun applications(originals: List<ValueModelApplication>): List<ValueModelApplication> {
        originals.forEach(::prepare)
        return originals.map { original ->
            modelApplications.getOrPut(original) {
                val shared = original.shareCallableEvidence(site(original.source), bindings.getValue(original), ::frame)
                sites[original.target] = shared.target
                shared
            }
        }
    }

    private fun invocation(original: ValueInvocation): ValueInvocation =
        invocations.getOrPut(original) { original.shareCallableEvidence(callables) }

    private fun prepare(application: ValueModelApplication) {
        val receiverCall = (application.source.role as? ValueRole.Receiver)?.call
        val originalCall =
            if (receiverCall != null && application.binding.matchesInvocation(receiverCall)) receiverCall
            else application.invocation
        val sharedCall = invocation(originalCall)
        val binding =
            if (sharedCall === application.invocation) application.binding
            else
                ReviewedLibraryBinding.fromCompiler(
                        sharedCall,
                        application.binding.model,
                        application.binding.artifact,
                    )
                    .sharedProof()
        bindings[application] = binding
        when (application) {
            is ValueModelApplication.CollectionInsertion -> Unit
            is ValueModelApplication.CollectionSelection -> Unit
            is ValueModelApplication.ScopeCallbackInput -> prepare(application.frame, binding)
            is ValueModelApplication.ScopeCallbackReturn -> prepare(application.frame, binding)
        }
    }

    private fun prepare(original: ValueCallFrame, binding: ReviewedLibraryBinding) {
        reviewedFrames.getOrPut(original) {
            val target = original.target as ValueReviewedCallbackTarget
            if (target.binding === binding) original
            else ValueCallFrame.reviewedCallback(binding, target.argument, target.formal).sharedProof()
        }
    }

    private fun frame(original: ValueCallFrame): ValueCallFrame = reviewedFrames.getValue(original)

    private fun stack(original: ValueCallStack): ValueCallStack {
        if (original.frames.none { sharedFrame(it) !== it }) return original
        return original.frames.fold(ValueCallStack.Empty) { result, frame ->
            result.enter(sharedFrame(frame)).sharedProof()
        }
    }

    private fun sharedFrame(original: ValueCallFrame): ValueCallFrame = reviewedFrames[original] ?: original

    private fun retain(site: ValueSite) {
        when (val role = site.role) {
            is ValueRole.Argument -> retain(role.call)
            is ValueRole.Receiver -> retain(role.call)
            is ValueRole.FunctionArgument,
            is ValueRole.Formal,
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> Unit
        }
    }

    private fun retain(invocation: ValueInvocation) {
        val endpoint = invocation.callable as? RelationEndpoint.Resolved ?: return
        callables.putIfAbsent(endpoint, invocation)
    }
}

internal fun <T, E> Refinement<T, E>.sharedProof(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Full admitted proof must survive physical sharing: $failure")
    }
