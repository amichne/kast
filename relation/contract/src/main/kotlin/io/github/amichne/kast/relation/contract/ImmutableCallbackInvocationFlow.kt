package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Each source use keeps its own supplier and receiver context; shared formals never merge suppliers. */
sealed interface ImmutableCallbackInvocationUse {
    val value: ImmutableCallbackValue

    data class Supplied(val supplier: CallbackParameterSupplier, val summary: CallbackParameterSummary) :
        ImmutableCallbackInvocationUse {
        override val value: ImmutableCallbackValue
            get() = supplier.value
    }

    data class Direct(override val value: ImmutableCallbackValue, val binding: CallbackDirectInvocationBinding) :
        ImmutableCallbackInvocationUse

    data class Unused(override val value: ImmutableCallbackValue) : ImmutableCallbackInvocationUse
}

enum class ImmutableCallbackInvocationFlowFailure {
    ORIGIN_MISMATCH,
    BASIS_MISMATCH,
    DESTINATION_MISMATCH,
    DUPLICATE_USE,
    FORMAL_MISMATCH,
    INCOMPLETE_SUMMARY,
    UNPROVEN_SUPPLY_OWNER,
    INVALID_SCAN,
}

/** Complete source-use enumeration is independent from the formal-body enumeration in every supplied use. */
@ConsistentCopyVisibility
data class ImmutableCallbackInvocationFlow
private constructor(
    val origin: ImmutableCallbackValueOrigin,
    val source: ValueSite,
    val uses: List<ImmutableCallbackInvocationUse>,
    val obligations: Set<CallbackInvocationFlowCause>,
    val scan: CallbackInvocationScan,
) {
    val basis = source.basis
    val hasStaticInvocation: Boolean
        get() = uses.any { use ->
            when (use) {
                is ImmutableCallbackInvocationUse.Direct -> true
                is ImmutableCallbackInvocationUse.Supplied -> use.summary.invocations.isNotEmpty()
                is ImmutableCallbackInvocationUse.Unused -> false
            }
        }

    val retainedBytes: Long =
        uses.fold(source.retainedBytes.addBytes(4096L)) { bytes, use ->
            bytes
                .addBytes(use.value.retainedBytes)
                .addBytes(
                    when (use) {
                        is ImmutableCallbackInvocationUse.Supplied ->
                            use.summary.retainedBytes.addBytes(use.supplier.retainedBytes)
                        is ImmutableCallbackInvocationUse.Direct,
                        is ImmutableCallbackInvocationUse.Unused -> 4096L
                    }
                )
        }

    companion object {
        fun fromCompiler(
            origin: ImmutableCallbackValueOrigin,
            source: ValueSite,
            uses: List<ImmutableCallbackInvocationUse>,
            obligations: Set<CallbackInvocationFlowCause>,
            scan: CallbackInvocationScan,
        ): Refinement<ImmutableCallbackInvocationFlow, ImmutableCallbackInvocationFlowFailure> {
            if (
                ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList()) is Refinement.Rejected ||
                    uses.any { !it.value.tracesSource(origin, source) }
            )
                return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.ORIGIN_MISMATCH)
            if (uses.distinct().size != uses.size)
                return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.DUPLICATE_USE)
            if (!scan.admitsImmutable(obligations))
                return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.INVALID_SCAN)
            for (use in uses) when (val admitted = use.admit(source)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(
                ImmutableCallbackInvocationFlow(
                    origin,
                    source,
                    Collections.unmodifiableList(uses.toList()),
                    Collections.unmodifiableSet(obligations.toSet()),
                    scan,
                )
            )
        }
    }
}

private fun RelationEndpoint.matchesImmutableOwner(owner: RelationCallableBody.Named): Boolean =
    file == owner.file &&
        range == owner.range &&
        compilerIdentity == owner.compilerIdentity &&
        signature == owner.evidence.signature

private fun ImmutableCallbackInvocationUse.admit(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> =
    when (this) {
        is ImmutableCallbackInvocationUse.Unused -> Refinement.Refined(Unit)
        is ImmutableCallbackInvocationUse.Direct -> admitDirect(source)
        is ImmutableCallbackInvocationUse.Supplied -> admitSupplied(source)
    }

internal fun ImmutableCallbackInvocationUse.Supplied.admitSupplied(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> {
    if (supplier.formal != summary.formal)
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.FORMAL_MISMATCH)
    if (
        summary.scan != CallbackInvocationScan.EXHAUSTIVE ||
            summary.obligations.isNotEmpty() ||
            summary.ownerBindings.isNotEmpty()
    )
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.INCOMPLETE_SUMMARY)
    val binding = supplier.binding
    val owner =
        binding.invocationOwner as? RelationCallableBody.Named
            ?: return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
    if (!binding.invocation.enclosing.matchesImmutableOwner(owner))
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
    if (binding.invocation.basis != source.basis)
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.BASIS_MISMATCH)
    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackInvocationUse.Direct.admitDirect(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> {
    if (binding.basis != source.basis) return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.BASIS_MISMATCH)
    val owner =
        binding.owner as? RelationCallableBody.Named
            ?: return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
    val destination = value.destination
    if (!destination.enclosing.matchesImmutableOwner(owner))
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
    if (destination.role != ValueRole.ExpressionResult && destination.role != ValueRole.LocalRead)
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.DESTINATION_MISMATCH)
    if (
        binding.occurrence.file != destination.enclosing.file ||
            !binding.occurrence.range.containsValueRange(destination.range)
    )
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.DESTINATION_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun CallbackInvocationScan.admitsImmutable(obligations: Set<CallbackInvocationFlowCause>): Boolean =
    when (this) {
        CallbackInvocationScan.NOT_APPLICABLE -> false
        CallbackInvocationScan.EXHAUSTIVE -> obligations.isEmpty()
        CallbackInvocationScan.INCOMPLETE -> obligations.isNotEmpty()
    }
