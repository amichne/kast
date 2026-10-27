package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Native candidates live only during this call; enumeration, addresses and confirmation are effect boundaries. */
sealed interface LocalReferenceResolution {
    data class Transfer(val target: ValueSite) : LocalReferenceResolution

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : LocalReferenceResolution

    data class Unresolved(val obligation: ValueFlowObligation) : LocalReferenceResolution

    data object OtherBinding : LocalReferenceResolution

    /** A confirmed write or a read supplied by a different admitted definition. */
    data object OtherVersion : LocalReferenceResolution

    /** The exact binding remains established, but its branch cannot carry the tracked value. */
    data object ExcludedBranch : LocalReferenceResolution
}

/**
 * Work units count authority/role admission and new candidate confirmations. Replay visits are time/cancellation
 * bounded.
 */
class LocalBindingReferenceScan<C>(
    private val request: ValueFlowRequest,
    private val domain: RelationRequest,
    private val enumerate: ((C) -> Boolean) -> Unit,
    private val key: (C) -> LocalReferenceKey,
    private val confirm: (C) -> LocalReferenceResolution,
    private val checkCanceled: () -> Unit,
    private val elapsedMillis: () -> Long,
    private val onWork: (RelationWorkCount) -> Unit = {},
    private val additionalWork: () -> Long = { 0L },
    private val enumerationSuspension: () -> ValueFlowSuspensionCause? = { null },
) {
    private val edges = mutableListOf<ValueTransfer>()
    private val obligations = mutableListOf<ValueFlowObligation>()
    private val consumed = request.remainder?.consumed?.toMutableSet() ?: linkedSetOf()
    private val emitted = request.remainder?.emitted?.toMutableSet() ?: linkedSetOf()
    private var work = 1L
    private var suspension: ValueFlowSuspensionCause? = null

    fun read(): ValueFlowRead {
        if (!request.source.role.hasReferenceReads() || hasForeignRemainder())
            return ValueFlowRead.ContractRejected(ValueFlowStepFailure.SOURCE_MISMATCH, count())
        // Reserve one transfer, one new obligation, and one detached address before confirmation.
        if (!fitsCandidate() || !request.budget.resources.permitsWork(MINIMUM_PROGRESS_WORK))
            return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, count())
        spend() // role admission, after the adapter's authority/owner unit
        enumerate(::visit)
        // Native enumeration can expire before it reaches a candidate. Preserve any earlier scan suspension.
        if (suspension == null) suspension = enumerationSuspension()
        // Replay can consume the time grant before reaching new input; a later grant may still advance it.
        if (
            suspension != null &&
                suspension != ValueFlowSuspensionCause.TIME_LIMIT_REACHED &&
                consumed.size == (request.remainder?.consumed?.size ?: 0)
        )
            return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, count())
        return finish()
    }

    private fun hasForeignRemainder(): Boolean =
        request.remainder?.let { it.source != request.source || it.boundary != request.boundary } ?: false

    private fun visit(candidate: C): Boolean {
        checkCanceled()
        if (!request.budget.resources.hasTimeRemaining(elapsedMillis())) {
            suspension = ValueFlowSuspensionCause.TIME_LIMIT_REACHED
            return false
        }
        val address = key(candidate)
        if (address in consumed) return true
        if (!permitted()) return false
        spend()
        when (val result = confirm(candidate)) {
            LocalReferenceResolution.OtherBinding,
            LocalReferenceResolution.OtherVersion,
            LocalReferenceResolution.ExcludedBranch -> Unit
            is LocalReferenceResolution.Unsupported -> obligation(result.cause)
            is LocalReferenceResolution.Unresolved ->
                if (result.obligation !in obligations) obligations += result.obligation
            is LocalReferenceResolution.Transfer -> transfer(result.target)
        }
        consumed += address
        return true
    }

    private fun transfer(target: ValueSite) {
        if (target.identity.owner != request.source.identity.owner) {
            obligation(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        when (val transfer = ValueTransfer.fromCompiler(request.source, target, ValueTransferKind.LOCAL_READ)) {
            is Refinement.Rejected -> obligation(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            is Refinement.Refined -> {
                val bytes =
                    ValueFlowStep.detachedByteCount(request.source, domain, edges + transfer.value, obligations).value
                val reserve = 2048L.addBytes(request.source.retainedBytes)
                val progress =
                    LocalBindingReadRemainder.storageBytes(request.source, consumed.size + 1, emitted + target.identity)
                if (!request.budget.permitsReturnedBytes(bytes.addBytes(progress).addBytes(reserve))) {
                    obligation(ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED)
                } else if (emitted.add(target.identity)) edges += transfer.value
            }
        }
    }

    private fun finish(): ValueFlowRead {
        val cause = suspension
        val terminal =
            if (cause != null) ValueFlowTerminal.ResourceSuspended
            else if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted else ValueFlowTerminal.Unresolved
        val remainder = if (cause != null) remainder() else null
        val step =
            when (
                val result =
                    ValueFlowStep.fromCompiler(
                        request.source,
                        edges,
                        obligations,
                        terminal,
                        domain,
                        count(),
                        RelationByteCount.parse(remainder?.retainedBytes ?: 0L).value(),
                    )
            ) {
                is Refinement.Rejected -> return ValueFlowRead.ContractRejected(result.failure, count())
                is Refinement.Refined -> result.value
            }
        return if (cause == null) ValueFlowRead.Observed(step)
        else ValueFlowRead.Suspended(step, requireNotNull(remainder), cause)
    }

    private fun permitted(): Boolean {
        suspension =
            when {
                !request.budget.resources.hasWorkCapacity(work.addBytes(additionalWork())) ->
                    ValueFlowSuspensionCause.WORK_LIMIT_REACHED
                edges.size >= request.budget.outputResults.value -> ValueFlowSuspensionCause.RESULT_LIMIT_REACHED
                !fitsCandidate() -> ValueFlowSuspensionCause.BYTE_LIMIT_REACHED
                else -> null
            }
        return suspension == null
    }

    private fun fitsCandidate(): Boolean {
        if (request.budget.resources == io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled) return true
        val base = ValueFlowStep.detachedByteCount(request.source, domain, edges, obligations).value
        val remaining = request.budget.returnedBytes.value - base
        val reserve = 2048L.addBytes(request.source.retainedBytes)
        val next =
            LocalBindingReadRemainder.storageBytes(request.source, consumed.size, emitted) +
                1024L +
                2048L +
                3L * request.source.retainedBytes
        return remaining >= 0L && reserve <= remaining && next <= remaining - reserve
    }

    private fun remainder(): LocalBindingReadRemainder =
        LocalBindingReadRemainder.admit(request.source, request.boundary, consumed, emitted).value()

    private fun count(): RelationWorkCount = RelationWorkCount.parse(work.addBytes(additionalWork())).value()

    private fun spend() {
        work++
        onWork(count())
    }

    private fun obligation(cause: ValueFlowUnsupportedCause) {
        val value = ValueFlowObligation(request.source, cause)
        if (value !in obligations) obligations += value
    }
}

private fun <V> Refinement<V, *>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Derived scan invariant rejected: $failure")
    }

private const val MINIMUM_PROGRESS_WORK = 3L
