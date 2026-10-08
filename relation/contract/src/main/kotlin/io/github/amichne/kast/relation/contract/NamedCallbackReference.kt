package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence

/** Receiver application is proved by K2 at the reference creation site, independently of later invocation. */
sealed interface CallbackReferenceReceiver {
    data object Absent : CallbackReferenceReceiver

    data object Unbound : CallbackReferenceReceiver

    data class Bound(val occurrence: RelationOccurrence) : CallbackReferenceReceiver

    data class Implicit(val declaration: CompilerGroundedSymbolEvidence) : CallbackReferenceReceiver
}

data class CallbackReferenceReceivers(
    val dispatch: CallbackReferenceReceiver,
    val extension: CallbackReferenceReceiver,
)

sealed interface NamedCallbackReferenceFlow {
    data class Immutable(val flow: ImmutableCallbackInvocationFlow) : NamedCallbackReferenceFlow

    data class Supplied(val binding: CallbackArgumentBinding, val summary: CallbackParameterSummary) :
        NamedCallbackReferenceFlow

    data class Direct(val binding: CallbackDirectInvocationBinding) : NamedCallbackReferenceFlow

    class Unavailable(
        cause: CallbackInvocationFlowCause,
        additionalCauses: Set<CallbackInvocationFlowCause> = emptySet(),
    ) : NamedCallbackReferenceFlow {
        val causes: Set<CallbackInvocationFlowCause> =
            java.util.Collections.unmodifiableSet((additionalCauses + cause).toSortedSet(compareBy { it.ordinal }))
        val cause: CallbackInvocationFlowCause
            get() = causes.first()

        override fun equals(other: Any?): Boolean = other is Unavailable && causes == other.causes

        override fun hashCode(): Int = causes.hashCode()
    }
}

enum class NamedCallbackReferenceFailure {
    TARGET_NOT_CALLABLE,
    BASIS_MISMATCH,
    REFERENCE_OUTSIDE_SUPPLY,
    RECEIVER_OUTSIDE_REFERENCE,
    FORMAL_MISMATCH,
    INCOMPLETE_SUMMARY,
    UNPROVEN_SUPPLY_OWNER,
}

/** A named callable value is not a named call. Its supplier and receiver context remain part of its identity. */
@ConsistentCopyVisibility
data class NamedCallbackReference
private constructor(
    val occurrence: RelationOccurrence,
    val target: RelationEndpoint.Resolved,
    val receivers: CallbackReferenceReceivers,
    val flow: NamedCallbackReferenceFlow,
) {
    val retainedBytes: Long =
        target
            .detachedTextUnits()
            .multiplyBytes(2)
            .addBytes(4096L)
            .addBytes(
                when (flow) {
                    is NamedCallbackReferenceFlow.Supplied -> flow.summary.retainedBytes
                    is NamedCallbackReferenceFlow.Immutable -> flow.flow.retainedBytes
                    is NamedCallbackReferenceFlow.Direct,
                    is NamedCallbackReferenceFlow.Unavailable -> 4096L
                }
            )

    val hasStaticInvocation: Boolean
        get() =
            when (val value = flow) {
                is NamedCallbackReferenceFlow.Immutable -> value.flow.hasStaticInvocation
                is NamedCallbackReferenceFlow.Supplied -> value.summary.invocations.isNotEmpty()
                is NamedCallbackReferenceFlow.Direct -> true
                is NamedCallbackReferenceFlow.Unavailable -> false
            }

    companion object {
        fun fromCompiler(
            occurrence: RelationOccurrence,
            target: RelationEndpoint.Resolved,
            receivers: CallbackReferenceReceivers,
            flow: NamedCallbackReferenceFlow,
        ): Refinement<NamedCallbackReference, NamedCallbackReferenceFailure> {
            if (target.signature !is io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature)
                return Refinement.Rejected(NamedCallbackReferenceFailure.TARGET_NOT_CALLABLE)
            for (receiver in listOf(receivers.dispatch, receivers.extension)) {
                if (
                    receiver is CallbackReferenceReceiver.Bound &&
                        (receiver.occurrence.file != occurrence.file ||
                            !occurrence.range.containsValueRange(receiver.occurrence.range))
                )
                    return Refinement.Rejected(NamedCallbackReferenceFailure.RECEIVER_OUTSIDE_REFERENCE)
            }
            when (val admitted = validateFlow(occurrence, target, receivers, flow)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(NamedCallbackReference(occurrence, target, receivers, flow))
        }

        private fun validateFlow(
            occurrence: RelationOccurrence,
            target: RelationEndpoint.Resolved,
            receivers: CallbackReferenceReceivers,
            flow: NamedCallbackReferenceFlow,
        ): Refinement<Unit, NamedCallbackReferenceFailure> =
            when (flow) {
                is NamedCallbackReferenceFlow.Immutable -> validateImmutable(occurrence, target, receivers, flow)
                is NamedCallbackReferenceFlow.Supplied -> validateSupplied(occurrence, target, flow)
                is NamedCallbackReferenceFlow.Direct -> validateDirect(occurrence, target, flow.binding)
                is NamedCallbackReferenceFlow.Unavailable -> Refinement.Refined(Unit)
            }

        private fun validateImmutable(
            occurrence: RelationOccurrence,
            target: RelationEndpoint.Resolved,
            receivers: CallbackReferenceReceivers,
            flow: NamedCallbackReferenceFlow.Immutable,
        ): Refinement<Unit, NamedCallbackReferenceFailure> {
            val origin =
                flow.flow.origin as? ImmutableCallbackValueOrigin.Named
                    ?: return Refinement.Rejected(NamedCallbackReferenceFailure.REFERENCE_OUTSIDE_SUPPLY)
            if (origin.occurrence != occurrence || origin.target != target || origin.receivers != receivers)
                return Refinement.Rejected(NamedCallbackReferenceFailure.REFERENCE_OUTSIDE_SUPPLY)
            if (flow.flow.basis != target.lease.identity)
                return Refinement.Rejected(NamedCallbackReferenceFailure.BASIS_MISMATCH)
            return Refinement.Refined(Unit)
        }

        private fun validateSupplied(
            occurrence: RelationOccurrence,
            target: RelationEndpoint.Resolved,
            flow: NamedCallbackReferenceFlow.Supplied,
        ): Refinement<Unit, NamedCallbackReferenceFailure> {
            val binding = flow.binding
            if (
                binding.invocationOwner !is RelationCallableBody.Named ||
                    binding.invocationOwner.compilerIdentity != binding.invocation.enclosing.compilerIdentity
            )
                return Refinement.Rejected(NamedCallbackReferenceFailure.UNPROVEN_SUPPLY_OWNER)
            if (binding.invocation.basis != target.lease.identity)
                return Refinement.Rejected(NamedCallbackReferenceFailure.BASIS_MISMATCH)
            if (
                binding.invocation.enclosing.file != occurrence.file ||
                    !binding.invocation.range.containsValueRange(occurrence.range)
            )
                return Refinement.Rejected(NamedCallbackReferenceFailure.REFERENCE_OUTSIDE_SUPPLY)
            val formal =
                when (val result = binding.formalIdentity()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(NamedCallbackReferenceFailure.FORMAL_MISMATCH)
                }
            if (flow.summary.formal != formal) return Refinement.Rejected(NamedCallbackReferenceFailure.FORMAL_MISMATCH)
            if (
                flow.summary.scan != CallbackInvocationScan.EXHAUSTIVE ||
                    flow.summary.obligations.isNotEmpty() ||
                    flow.summary.ownerBindings.isNotEmpty()
            )
                return Refinement.Rejected(NamedCallbackReferenceFailure.INCOMPLETE_SUMMARY)
            return Refinement.Refined(Unit)
        }

        private fun validateDirect(
            occurrence: RelationOccurrence,
            target: RelationEndpoint.Resolved,
            binding: CallbackDirectInvocationBinding,
        ): Refinement<Unit, NamedCallbackReferenceFailure> {
            if (binding.owner !is RelationCallableBody.Named)
                return Refinement.Rejected(NamedCallbackReferenceFailure.UNPROVEN_SUPPLY_OWNER)
            if (binding.basis != target.lease.identity)
                return Refinement.Rejected(NamedCallbackReferenceFailure.BASIS_MISMATCH)
            if (
                binding.occurrence.file != occurrence.file ||
                    !binding.occurrence.range.containsValueRange(occurrence.range)
            )
                return Refinement.Rejected(NamedCallbackReferenceFailure.REFERENCE_OUTSIDE_SUPPLY)
            return Refinement.Refined(Unit)
        }
    }
}

internal fun NamedCallbackReference.canonicalProjection(): String =
    listOf(
            "NAMED_REFERENCE",
            occurrence.file.stableValue,
            occurrence.range.toString(),
            target.compilerIdentity.value,
            target.file.stableValue,
            target.range.toString(),
            receivers.dispatch.canonicalProjection(),
            receivers.extension.canonicalProjection(),
            when (val value = flow) {
                is NamedCallbackReferenceFlow.Immutable -> value.flow.canonicalProjection()
                is NamedCallbackReferenceFlow.Supplied ->
                    listOf(
                            "SUPPLIED",
                            value.binding.invocation.enclosing.compilerIdentity.value,
                            value.binding.invocation.range.toString(),
                            value.binding.position.value.toString(),
                            value.summary.formal.canonicalProjection(),
                            value.summary.invocations.joinToString("\u0000") { it.canonicalProjection() },
                            when (val forwarding = value.summary.forwarding) {
                                CallbackForwardingEvidence.InvocationRoutes -> "INVOCATION_ROUTES"
                                is CallbackForwardingEvidence.ExhaustedGraph ->
                                    forwarding.graph.forwardings.joinToString("\u0000") { it.canonicalProjection() }
                            },
                        )
                        .joinToString("\u0000")
                is NamedCallbackReferenceFlow.Direct ->
                    "DIRECT:${value.binding.occurrence}:${value.binding.owner.compilerIdentity.value}"
                is NamedCallbackReferenceFlow.Unavailable -> "UNAVAILABLE:${value.causes.joinToString { it.name }}"
            },
        )
        .joinToString("\u0000")

internal fun CallbackReferenceReceiver.canonicalProjection(): String =
    when (this) {
        CallbackReferenceReceiver.Absent -> "ABSENT"
        CallbackReferenceReceiver.Unbound -> "UNBOUND"
        is CallbackReferenceReceiver.Bound -> "BOUND:${occurrence.file.stableValue}:${occurrence.range}"
        is CallbackReferenceReceiver.Implicit ->
            "IMPLICIT:${declaration.file.stableValue}:${declaration.range}:${declaration.compilerIdentity.value}"
    }
