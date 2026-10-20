package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Native candidates live only during this call; enumeration, addresses and confirmation are effect boundaries. */
sealed interface LocalReferenceResolution {
    data class Transfer(val target: ValueSite) : LocalReferenceResolution

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : LocalReferenceResolution

    data object OtherBinding : LocalReferenceResolution
}

/**
 * Work units count authority/role admission and new candidate confirmations. Replay visits are time/cancellation
 * bounded.
 */
class LocalBindingReferenceScan<C>(
    private val request: ValueFlowRequest,
    private val domain: RelationRequest,
    private val enumerate: (LocalReferenceTraversal<C>) -> Unit,
    private val key: (C) -> LocalReferenceKey,
    private val confirm: (C) -> LocalReferenceResolution,
    private val checkCanceled: () -> Unit,
    private val elapsedMillis: () -> Long,
    private val onWork: (RelationWorkCount) -> Unit = {},
    private val admitRole: () -> Refinement<Unit, ValueFlowUnsupportedCause> = { Refinement.Refined(Unit) },
) {
    private val edges = mutableListOf<ValueTransfer>()
    private val obligations = mutableListOf<ValueFlowObligation>()
    private val consumed = request.remainder?.consumed?.toMutableSet() ?: linkedSetOf()
    private val emitted = request.remainder?.emitted?.toMutableSet() ?: linkedSetOf()
    private var work = 1L
    private var suspension: ValueFlowSuspensionCause? = null

    fun read(): ValueFlowRead {
        if (!request.knownDeclarations.belongsTo(request.source.enclosing.lease))
            return ValueFlowRead.ContractRejected(ValueFlowStepFailure.BASIS_MISMATCH, count())
        if (!request.source.role.hasReferenceReads() || hasForeignRemainder())
            return ValueFlowRead.ContractRejected(ValueFlowStepFailure.SOURCE_MISMATCH, count())
        // Reserve a transfer, every possible semantic obligation and a detached address before confirmation.
        if (!fitsCandidate() || request.budget.resources.workUnitLimit.value < MINIMUM_PROGRESS_WORK)
            return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, count())
        spend() // role admission, after the adapter's authority/owner unit
        if (admitTraversal()) {
            when (val role = admitRole()) {
                is Refinement.Refined ->
                    if (admitTraversal()) enumerate(LocalReferenceTraversal(::admitTraversal, ::visit))
                is Refinement.Rejected -> obligation(role.failure)
            }
        }
        admitTraversal()
        // Replay can consume the time grant before reaching new input; a later grant may still advance it.
        if (hasUnadvancedSuspension()) return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, count())
        return finish()
    }

    private fun hasUnadvancedSuspension(): Boolean =
        suspension != null &&
            suspension != ValueFlowSuspensionCause.TIME_LIMIT_REACHED &&
            consumed.size == (request.remainder?.consumed?.size ?: 0)

    private fun hasForeignRemainder(): Boolean =
        request.remainder?.let { it.source != request.source || it.boundary != request.boundary } ?: false

    private fun admitTraversal(): Boolean {
        checkCanceled()
        if (suspension != null) return false
        if (elapsedMillis() >= request.budget.resources.elapsedTimeLimit.value) {
            suspension = ValueFlowSuspensionCause.TIME_LIMIT_REACHED
            return false
        }
        return true
    }

    private fun visit(candidate: C): Boolean {
        if (!admitTraversal()) return false
        val address = key(candidate)
        if (address in consumed) return true
        if (!permitted()) return false
        spend()
        return publish(address, admitResolution(confirm(candidate)))
    }

    private fun admitResolution(result: LocalReferenceResolution): LocalReferenceDecision =
        when (result) {
            LocalReferenceResolution.OtherBinding -> LocalReferenceDecision.OtherBinding
            is LocalReferenceResolution.Unsupported -> LocalReferenceDecision.Unsupported(result.cause)
            is LocalReferenceResolution.Transfer -> {
                if (result.target.identity.owner != request.source.identity.owner)
                    LocalReferenceDecision.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                else
                    when (
                        val transfer =
                            ValueTransfer.fromCompiler(request.source, result.target, ValueTransferKind.LOCAL_READ)
                    ) {
                        is Refinement.Rejected ->
                            LocalReferenceDecision.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                        is Refinement.Refined -> LocalReferenceDecision.Transfer(transfer.value)
                    }
            }
        }

    private fun publish(address: LocalReferenceKey, decision: LocalReferenceDecision): Boolean {
        val nextEdges =
            edges +
                when (decision) {
                    is LocalReferenceDecision.Transfer ->
                        if (decision.value.target.identity in emitted) emptyList() else listOf(decision.value)
                    is LocalReferenceDecision.Unsupported,
                    LocalReferenceDecision.OtherBinding -> emptyList()
                }
        val nextObligations =
            obligations +
                when (decision) {
                    is LocalReferenceDecision.Unsupported ->
                        listOf(ValueFlowObligation(request.source, decision.cause)).filter { it !in obligations }
                    is LocalReferenceDecision.Transfer,
                    LocalReferenceDecision.OtherBinding -> emptyList()
                }
        val nextConsumed = consumed + address
        val nextEmitted = emitted + nextEdges.map { it.target.identity }
        val nextRemainder =
            LocalBindingReadRemainder.admit(request.source, request.boundary, nextConsumed, nextEmitted).value()
        val bytes =
            ValueFlowStep.detachedByteCount(
                    request.source,
                    domain,
                    nextEdges,
                    nextObligations,
                    nextRemainder,
                    knownDeclarations = request.knownDeclarations,
                )
                .value
        if (!fits(bytes)) {
            suspension = ValueFlowSuspensionCause.BYTE_LIMIT_REACHED
            return false
        }
        edges.clear()
        edges += nextEdges
        obligations.clear()
        obligations += nextObligations
        consumed += address
        emitted += nextEmitted
        return true
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
                        remainder,
                        knownDeclarations = request.knownDeclarations,
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
                work >= request.budget.resources.workUnitLimit.value -> ValueFlowSuspensionCause.WORK_LIMIT_REACHED
                edges.size >= request.budget.resources.resultLimit.value ->
                    ValueFlowSuspensionCause.RESULT_LIMIT_REACHED
                !fitsCandidate() -> ValueFlowSuspensionCause.BYTE_LIMIT_REACHED
                else -> null
            }
        return suspension == null
    }

    private fun fitsCandidate(): Boolean {
        val predicted =
            localReferenceCandidateStorageBytes(
                request.source,
                domain,
                edges,
                obligations,
                consumed,
                emitted,
                request.knownDeclarations,
            )
        return fits(predicted.value)
    }

    private fun fits(bytes: Long): Boolean {
        val reserve = 2048L * ValueFlowUnsupportedCause.entries.size
        return bytes.addBytes(reserve) <= request.budget.returnedBytes.value
    }

    private fun remainder(): LocalBindingReadRemainder =
        LocalBindingReadRemainder.admit(request.source, request.boundary, consumed, emitted).value()

    private fun count(): RelationWorkCount = RelationWorkCount.parse(work).value()

    private fun spend() {
        work++
        onWork(count())
    }

    private fun obligation(cause: ValueFlowUnsupportedCause) {
        val value = ValueFlowObligation(request.source, cause)
        if (value !in obligations) obligations += value
    }
}

private sealed interface LocalReferenceDecision {
    data class Transfer(val value: ValueTransfer) : LocalReferenceDecision

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : LocalReferenceDecision

    data object OtherBinding : LocalReferenceDecision
}

private fun <V> Refinement<V, *>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Derived scan invariant rejected: $failure")
    }

private const val MINIMUM_PROGRESS_WORK = 3L
