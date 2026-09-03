package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProviderCursor
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import java.nio.charset.StandardCharsets

/** Closed native provider termination; only [Terminal] can prove exact enumeration. */
sealed interface IntellijRelationTermination {
    data object Terminal : IntellijRelationTermination

    data class TerminalIncomplete(
        val limitations: Set<RelationLimitation>,
    ) : IntellijRelationTermination

    data class Resumable(
        val limitations: Set<RelationLimitation>,
    ) : IntellijRelationTermination
}

internal enum class IntellijRelationProviderItemAdmission {
    READY,
    SKIPPED_VERIFIED_PREFIX,
    HALTED,
    CURSOR_MOVED,
}

private enum class IntellijRelationCollectionState {
    COLLECTING,
    HALTED,
    CURSOR_MOVED,
    CONTRACT_REJECTED,
}

/** Request-local bounded collector for already K2-confirmed detached relation facts. */
internal class IntellijRelationCollector(
    private val request: RelationRequest,
    private val clockNanoseconds: () -> Long = System::nanoTime,
) {
    private val startedAt = clockNanoseconds()
    private val facts = mutableListOf<RelationFact>()
    private val limitations = linkedSetOf<RelationLimitation>()
    private val requestedCursor = request.providerCursor
    private var observedPrefix = RelationProviderCursor.start(requestedCursor.provider)
    private var nextProviderCursor = requestedCursor
    private var prefixVerified = requestedCursor.nextPosition.value == 0L
    private var pendingProviderItem: RelationProviderItemDescriptor? = null
    private var examined = 0L
    private var retainedBytes = 0L
    private var state = IntellijRelationCollectionState.COLLECTING

    /**
     * Observes one native item before semantic filtering. Resume pages re-enumerate and verify the
     * entire consumed prefix; new items remain pending until accepted, qualified, or dismissed.
     */
    fun beginProviderItem(
        item: RelationProviderItemDescriptor,
    ): IntellijRelationProviderItemAdmission {
        when (state) {
            IntellijRelationCollectionState.CURSOR_MOVED ->
                return IntellijRelationProviderItemAdmission.CURSOR_MOVED
            IntellijRelationCollectionState.HALTED,
            IntellijRelationCollectionState.CONTRACT_REJECTED,
                -> return IntellijRelationProviderItemAdmission.HALTED
            IntellijRelationCollectionState.COLLECTING -> Unit
        }
        if (pendingProviderItem != null) {
            state = IntellijRelationCollectionState.CONTRACT_REJECTED
            return IntellijRelationProviderItemAdmission.HALTED
        }
        if (!prefixVerified) {
            observedPrefix = observedPrefix.advance(item)
            if (observedPrefix.nextPosition == requestedCursor.nextPosition) {
                if (observedPrefix.consumedPrefixDigest != requestedCursor.consumedPrefixDigest) {
                    state = IntellijRelationCollectionState.CURSOR_MOVED
                    return IntellijRelationProviderItemAdmission.CURSOR_MOVED
                }
                prefixVerified = true
            }
            return IntellijRelationProviderItemAdmission.SKIPPED_VERIFIED_PREFIX
        }
        if (elapsedLimitReached()) {
            halt(RelationLimitation.TIME_LIMIT_REACHED)
            return IntellijRelationProviderItemAdmission.HALTED
        }
        pendingProviderItem = item
        return IntellijRelationProviderItemAdmission.READY
    }

    /** Commits a provider item that the semantic plan deliberately filtered. */
    fun dismissProviderItem(): Boolean = when (val pending = pendingProviderItem) {
        null -> contractHalt()
        else -> {
            nextProviderCursor = nextProviderCursor.advance(pending)
            pendingProviderItem = null
            true
        }
    }

    /**
     * Consumes one already compiler-confirmed edge while enforcing page budgets. A budget halt
     * leaves the pending item unconsumed so a resumed request cannot omit it.
     */
    fun accept(fact: RelationFact): Boolean {
        val pending = pendingProviderItem ?: return contractHalt()
        if (state != IntellijRelationCollectionState.COLLECTING) return false
        if (elapsedLimitReached()) return halt(RelationLimitation.TIME_LIMIT_REACHED)
        if (examined >= request.budget.resources.workUnitLimit.value) {
            return halt(RelationLimitation.WORK_LIMIT_REACHED)
        }
        if (facts.size >= request.budget.resources.resultLimit.value) {
            return halt(RelationLimitation.RESULT_LIMIT_REACHED)
        }

        val factBytes = fact.canonicalProjection()
            .toByteArray(StandardCharsets.UTF_8)
            .size
            .toLong()
        if (retainedBytes + factBytes > request.budget.returnedBytes.value) {
            return halt(RelationLimitation.BYTE_LIMIT_REACHED)
        }

        nextProviderCursor = nextProviderCursor.advance(pending)
        pendingProviderItem = null
        examined += 1L
        retainedBytes += factBytes
        facts += fact
        return true
    }

    /** Records one explicit compiler/provider coverage loss without manufacturing a fact. */
    fun qualify(limitation: RelationLimitation) {
        limitations += limitation
    }

    /** Records semantic work that could not produce an exact detached fact. */
    fun examineIncomplete(limitation: RelationLimitation): Boolean {
        val pending = pendingProviderItem ?: return contractHalt()
        if (state != IntellijRelationCollectionState.COLLECTING) return false
        if (elapsedLimitReached()) return halt(RelationLimitation.TIME_LIMIT_REACHED)
        if (examined >= request.budget.resources.workUnitLimit.value) {
            return halt(RelationLimitation.WORK_LIMIT_REACHED)
        }
        nextProviderCursor = nextProviderCursor.advance(pending)
        pendingProviderItem = null
        examined += 1L
        limitations += limitation
        return true
    }

    /** Produces exact, resumable, terminal-incomplete, or typed moved-cursor output. */
    fun finish(termination: IntellijRelationTermination): RelationCompilation {
        if (!prefixVerified || state == IntellijRelationCollectionState.CURSOR_MOVED) {
            return RelationCompilation.Rejected(
                RelationCompilerRejection.CONTINUATION_CURSOR_MOVED,
            )
        }
        if (
            state == IntellijRelationCollectionState.CONTRACT_REJECTED ||
            pendingProviderItem != null && termination !is IntellijRelationTermination.Resumable
        ) {
            return contractRejected()
        }
        when (termination) {
            IntellijRelationTermination.Terminal -> Unit
            is IntellijRelationTermination.TerminalIncomplete ->
                limitations += termination.limitations.ifEmpty {
                    setOf(RelationLimitation.PROVIDER_INCOMPLETE)
                }
            is IntellijRelationTermination.Resumable ->
                limitations += termination.limitations.ifEmpty {
                    setOf(RelationLimitation.PROVIDER_INCOMPLETE)
                }
        }

        val orderedFacts = facts.distinct().sorted()
        val bytes = RelationByteCount.parse(
            orderedFacts.sumOf {
                it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            },
        ).refinedOrReject() ?: return contractRejected()
        val work = RelationWorkCount.parse(examined).refinedOrReject() ?: return contractRejected()
        val results = RelationResultCount.parse(orderedFacts.size).refinedOrReject()
            ?: return contractRejected()
        val batch = RelationBatch.create(request, orderedFacts, bytes, work, results)
            .refinedOrReject() ?: return contractRejected()

        val resumable = termination is IntellijRelationTermination.Resumable ||
            state == IntellijRelationCollectionState.HALTED
        if (!resumable && limitations.isEmpty()) {
            return RelationCompilation.complete(batch)
        }
        val qualified = if (resumable) {
            RelationCompilation.qualifiedResumable(batch, limitations, nextProviderCursor)
        } else {
            RelationCompilation.qualifiedTerminal(batch, limitations)
        }
        return qualified.refinedOrReject() ?: contractRejected()
    }

    private fun elapsedLimitReached(): Boolean {
        val elapsed = (clockNanoseconds() - startedAt).coerceAtLeast(0L)
        val limit = request.budget.resources.elapsedTimeLimit.value * NANOS_PER_MILLISECOND
        return elapsed >= limit
    }

    private fun halt(limitation: RelationLimitation): Boolean {
        limitations += limitation
        state = IntellijRelationCollectionState.HALTED
        return false
    }

    private fun contractHalt(): Boolean {
        state = IntellijRelationCollectionState.CONTRACT_REJECTED
        return false
    }

    private fun contractRejected(): RelationCompilation.Rejected = RelationCompilation.Rejected(
        RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
    )

    private fun <Value, Failure> Refinement<Value, Failure>.refinedOrReject(): Value? = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
