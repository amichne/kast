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
    MISSING_OBLIGATION,
    OWNER_BINDING_MISMATCH,
    DUPLICATE_OWNER_BINDING,
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
    val ownerBindings: List<CallbackBodyBinding>,
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

    fun withOwnerBindings(
        additional: List<CallbackBodyBinding>
    ): Refinement<ImmutableCallbackInvocationFlow, ImmutableCallbackInvocationFlowFailure> {
        val combined = ownerBindings + additional
        val causes = obligations + combined.flatMap { it.obligations }
        if (
            scan == CallbackInvocationScan.EXHAUSTIVE &&
                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION !in obligations &&
                !admitsExhaustiveCallbackScan(causes)
        )
            return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.INVALID_SCAN)
        val refined =
            copy(
                ownerBindings = Collections.unmodifiableList(combined.toList()),
                obligations = Collections.unmodifiableSet(causes),
                scan =
                    if (causes.all { it == CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }) scan
                    else CallbackInvocationScan.INCOMPLETE,
            )
        when (val admitted = refined.admitOwners()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        return when (val composition = CompleteCallbackFlowGraph.admitImmutableOwners(refined)) {
            is Refinement.Refined ->
                Refinement.Refined(
                    refined.copy(
                        obligations =
                            Collections.unmodifiableSet(causes - CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
                    )
                )
            is Refinement.Rejected -> refined.withCompositionFailure(composition.failure)
        }
    }

    private fun withCompositionFailure(
        failure: StaticCallbackGraphFailure
    ): Refinement<ImmutableCallbackInvocationFlow, ImmutableCallbackInvocationFlowFailure> =
        when (failure) {
            is StaticCallbackGraphFailure.InvalidFlow -> Refinement.Rejected(failure.cause.immutableOwnerFailure())
            is StaticCallbackGraphFailure.Unavailable -> withCompositionCause(failure.cause)
            StaticCallbackGraphFailure.CyclicRoute -> withCompositionCause(CallbackInvocationFlowCause.CALLBACK_CYCLE)
            is StaticCallbackGraphFailure.ImmutableUnresolved,
            is StaticCallbackGraphFailure.Unresolved,
            is StaticCallbackGraphFailure.UnprovenPolicy,
            StaticCallbackGraphFailure.IncompleteScan,
            StaticCallbackGraphFailure.UnsupportedDefaultSupply,
            StaticCallbackGraphFailure.MissingNamedOwner,
            StaticCallbackGraphFailure.SupplierIdentityMismatch,
            StaticCallbackGraphFailure.OutsideWorkspace,
            StaticCallbackGraphFailure.NonCallableTarget -> Refinement.Refined(this)
        }

    private fun withCompositionCause(cause: CallbackInvocationFlowCause) =
        Refinement.Refined(
            copy(
                obligations = Collections.unmodifiableSet(obligations + cause),
                scan = CallbackInvocationScan.INCOMPLETE,
            )
        )

    private fun admitOwners(): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> =
        when (
            val admitted =
                admitCallbackOwnerBindings(basis, scan, uses.mapNotNull { it.executionOwner() }, ownerBindings)
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(admitted.failure.immutableOwnerFailure())
        }

    val retainedBytes: Long =
        uses
            .fold(source.retainedBytes.addBytes(4096L)) { bytes, use ->
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
            .let { bytes -> ownerBindings.fold(bytes) { total, owner -> total.addBytes(owner.retainedBytes) } }

    companion object {
        fun fromCompiler(
            origin: ImmutableCallbackValueOrigin,
            source: ValueSite,
            uses: List<ImmutableCallbackInvocationUse>,
            obligations: Set<CallbackInvocationFlowCause>,
            scan: CallbackInvocationScan,
            ownerBindings: List<CallbackBodyBinding> = emptyList(),
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
            if (
                uses.any { it.executionOwner() is RelationCallableBody.Anonymous } &&
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION !in obligations
            )
                return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.MISSING_OBLIGATION)
            if (
                !obligations.containsAll(ownerBindings.flatMap { it.obligations }) ||
                    !obligations.containsAll(
                        uses.filterIsInstance<ImmutableCallbackInvocationUse.Supplied>().flatMap {
                            it.summary.obligations
                        }
                    )
            )
                return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.MISSING_OBLIGATION)
            val flow =
                ImmutableCallbackInvocationFlow(
                    origin,
                    source,
                    Collections.unmodifiableList(uses.toList()),
                    Collections.unmodifiableSet(obligations.toSet()),
                    scan,
                    Collections.unmodifiableList(ownerBindings.toList()),
                )
            return when (val admitted = flow.admitOwners()) {
                is Refinement.Refined -> Refinement.Refined(flow)
                is Refinement.Rejected -> admitted
            }
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
        is ImmutableCallbackInvocationUse.Direct -> admitDirectSource(source)
        is ImmutableCallbackInvocationUse.Supplied -> admitSuppliedSource(source)
    }

internal fun ImmutableCallbackInvocationUse.Supplied.admitSupplied(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> {
    when (val admitted = admitSuppliedSource(source)) {
        is Refinement.Rejected -> return admitted
        is Refinement.Refined -> Unit
    }
    if (summary.obligations.isNotEmpty() || summary.ownerBindings.isNotEmpty())
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.INCOMPLETE_SUMMARY)
    return if (supplier.binding.invocationOwner is RelationCallableBody.Named) Refinement.Refined(Unit)
    else Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
}

private fun ImmutableCallbackInvocationUse.Supplied.admitSuppliedSource(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> {
    if (supplier.formal != summary.formal)
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.FORMAL_MISMATCH)
    if (
        summary.scan != CallbackInvocationScan.EXHAUSTIVE ||
            summary.obligations.any { it != CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }
    )
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.INCOMPLETE_SUMMARY)
    val binding = supplier.binding
    val owner = binding.invocationOwner
    if (owner is RelationCallableBody.Named && !binding.invocation.enclosing.matchesImmutableOwner(owner))
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
    if (binding.invocation.basis != source.basis)
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.BASIS_MISMATCH)
    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackInvocationUse.Direct.admitDirect(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> {
    if (binding.owner !is RelationCallableBody.Named)
        return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.UNPROVEN_SUPPLY_OWNER)
    return admitDirectSource(source)
}

private fun ImmutableCallbackInvocationUse.Direct.admitDirectSource(
    source: ValueSite
): Refinement<Unit, ImmutableCallbackInvocationFlowFailure> {
    if (binding.basis != source.basis) return Refinement.Rejected(ImmutableCallbackInvocationFlowFailure.BASIS_MISMATCH)
    val owner = binding.owner
    val destination = value.destination
    if (owner is RelationCallableBody.Named && !destination.enclosing.matchesImmutableOwner(owner))
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
        CallbackInvocationScan.EXHAUSTIVE ->
            obligations.all { it == CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }
        CallbackInvocationScan.INCOMPLETE -> obligations.isNotEmpty()
    }

internal fun ImmutableCallbackInvocationUse.executionOwner(): RelationCallableBody? =
    when (this) {
        is ImmutableCallbackInvocationUse.Direct -> binding.owner
        is ImmutableCallbackInvocationUse.Supplied -> supplier.binding.invocationOwner
        is ImmutableCallbackInvocationUse.Unused -> null
    }
