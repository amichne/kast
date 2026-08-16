package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RelationWorkOffset
import java.nio.charset.StandardCharsets

/** Closed native provider termination; only [Terminal] may prove exact enumeration. */
sealed interface IntellijRelationTermination {
    data object Terminal : IntellijRelationTermination

    data class Incomplete(
        val limitations: Set<RelationLimitation>,
    ) : IntellijRelationTermination
}

private enum class IntellijRelationCollectionState {
    COLLECTING,
    HALTED,
}

/** Request-local bounded collector for already K2-confirmed detached relation facts. */
internal class IntellijRelationCollector(
    private val request: RelationRequest,
    private val clockNanoseconds: () -> Long = System::nanoTime,
) {
    private val startedAt = clockNanoseconds()
    private val facts = mutableListOf<RelationFact>()
    private val limitations = linkedSetOf<RelationLimitation>()
    private var examined = 0L
    private var retainedBytes = 0L
    private var state = IntellijRelationCollectionState.COLLECTING

    /**
     * Consumes one already compiler-confirmed edge while enforcing page budgets. Returning false
     * is bounded-stream control, not a validation or expected-failure protocol; all reasons remain
     * in the closed [RelationCompilation] produced by [finish].
     */
    fun accept(fact: RelationFact): Boolean {
        if (state == IntellijRelationCollectionState.HALTED) return false
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

        examined += 1L
        retainedBytes += factBytes
        facts += fact
        return if (facts.size == request.budget.resources.resultLimit.value) {
            halt(RelationLimitation.RESULT_LIMIT_REACHED)
        } else {
            true
        }
    }

    /** Records one explicit compiler/provider coverage loss without manufacturing a fact. */
    fun qualify(limitation: RelationLimitation) {
        limitations += limitation
    }

    /** Records examined compiler work that could not produce an exact detached fact. */
    fun examineIncomplete(limitation: RelationLimitation): Boolean {
        if (state == IntellijRelationCollectionState.HALTED) return false
        if (elapsedLimitReached()) return halt(RelationLimitation.TIME_LIMIT_REACHED)
        if (examined >= request.budget.resources.workUnitLimit.value) {
            return halt(RelationLimitation.WORK_LIMIT_REACHED)
        }
        examined += 1L
        limitations += limitation
        return true
    }

    /**
     * Proof transition: `(bounded collector state, IntellijRelationTermination) ->
     * RelationCompilation`.
     *
     * Establishes a deterministic request-owned batch. Limitation-free terminal state becomes
     * exact coverage; every other state becomes qualified coverage with a bound continuation.
     * Batch or coverage invariant failures close as
     * [RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION]. Raw counts and time remain inside
     * this native collection boundary.
     */
    fun finish(termination: IntellijRelationTermination): RelationCompilation {
        when (termination) {
            IntellijRelationTermination.Terminal -> Unit
            is IntellijRelationTermination.Incomplete -> {
                if (termination.limitations.isEmpty()) {
                    limitations += RelationLimitation.PROVIDER_INCOMPLETE
                } else {
                    limitations += termination.limitations
                }
            }
        }
        if (elapsedLimitReached()) limitations += RelationLimitation.TIME_LIMIT_REACHED

        val orderedFacts = facts.distinct().sorted()
        val bytes = when (
            val parsed = RelationByteCount.parse(
                orderedFacts.sumOf {
                    it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                },
            )
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return contractRejected()
        }
        val work = when (val parsed = RelationWorkCount.parse(examined)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return contractRejected()
        }
        val batch = when (val refined = RelationBatch.create(request, orderedFacts, bytes, work)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return contractRejected()
        }

        if (
            termination == IntellijRelationTermination.Terminal &&
            limitations.isEmpty() &&
            state == IntellijRelationCollectionState.COLLECTING
        ) {
            return RelationCompilation.complete(batch)
        }
        val next = when (
            val parsed = RelationWorkOffset.parse(request.position.workOffset.value + examined)
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return contractRejected()
        }
        return when (val qualified = RelationCompilation.qualified(batch, limitations, next)) {
            is Refinement.Refined -> qualified.value
            is Refinement.Rejected -> contractRejected()
        }
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

    private fun contractRejected(): RelationCompilation.Rejected = RelationCompilation.Rejected(
        RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
