package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.charset.StandardCharsets

/** Closed native provider termination; only [Terminal] can prove exact enumeration. */
sealed interface IntellijRelationTermination {
    data object Terminal : IntellijRelationTermination

    data class TerminalIncomplete(val limitations: Set<RelationLimitation>) : IntellijRelationTermination

    data class Resumable(val limitations: Set<RelationLimitation>) : IntellijRelationTermination
}

internal enum class IntellijRelationProviderItemAdmission {
    READY,
    SKIPPED_VERIFIED_PREFIX,
    HALTED,
    CURSOR_MOVED,
}

internal enum class IntellijRelationProviderEnumerationAdmission {
    READY,
    HALTED,
}

private enum class IntellijRelationCollectionState {
    COLLECTING,
    HALTED,
    ENUMERATION_LIMIT,
    CURSOR_MOVED,
    CONTRACT_REJECTED,
}

/** Request-local bounded collector for already K2-confirmed detached relation facts. */
internal class IntellijRelationCollector(
    private val request: RelationRequest,
    private val clockNanoseconds: () -> Long = System::nanoTime,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
) {
    private val startedAt = clockNanoseconds()
    private val facts = mutableListOf<RelationFact>()
    private val limitations = linkedSetOf<RelationLimitation>()
    private val requestedCursor = request.providerCursor
    private var observedPrefix = RelationProviderCursor.start(requestedCursor.provider)
    private var nextProviderCursor = requestedCursor
    private var prefixVerified = requestedCursor.nextPosition.value == 0L
    private var pendingProviderItem: RelationProviderItemDescriptor? = null
    private var nativeCandidates = 0
    private var examined = 0L
    private var retainedBytes = 0L
    private var state = IntellijRelationCollectionState.COLLECTING

    /** Bounds native materialization before a canonical provider order can be established. */
    fun admitProviderEnumeration(): IntellijRelationProviderEnumerationAdmission =
        when (state) {
            IntellijRelationCollectionState.COLLECTING ->
                if (elapsedLimitReached()) {
                    halt(RelationLimitation.TIME_LIMIT_REACHED)
                    IntellijRelationProviderEnumerationAdmission.HALTED
                } else {
                    IntellijRelationProviderEnumerationAdmission.READY
                }
            IntellijRelationCollectionState.HALTED,
            IntellijRelationCollectionState.ENUMERATION_LIMIT,
            IntellijRelationCollectionState.CURSOR_MOVED,
            IntellijRelationCollectionState.CONTRACT_REJECTED -> IntellijRelationProviderEnumerationAdmission.HALTED
        }

    /** Bounds the native buffers independently of semantic per-page work and result budgets. */
    fun admitProviderCandidate(): IntellijRelationProviderEnumerationAdmission {
        if (admitProviderEnumeration() != IntellijRelationProviderEnumerationAdmission.READY) {
            return IntellijRelationProviderEnumerationAdmission.HALTED
        }
        if (nativeCandidates >= limits[ReadLimitParameter.RELATION_CANDIDATES].value) {
            observation.terminated(IntellijReadTermination.CANDIDATE_CAP)
            limitations += RelationLimitation.WORK_LIMIT_REACHED
            state = IntellijRelationCollectionState.ENUMERATION_LIMIT
            return IntellijRelationProviderEnumerationAdmission.HALTED
        }
        nativeCandidates += 1
        observation.count(IntellijReadCounter.RELATION_CANDIDATES)
        return IntellijRelationProviderEnumerationAdmission.READY
    }

    /**
     * Observes one native item before semantic filtering. Resume pages re-enumerate and verify the entire consumed
     * prefix; new items remain pending until accepted, qualified, or dismissed.
     */
    fun beginProviderItem(item: RelationProviderItemDescriptor): IntellijRelationProviderItemAdmission {
        when (state) {
            IntellijRelationCollectionState.CURSOR_MOVED -> return IntellijRelationProviderItemAdmission.CURSOR_MOVED
            IntellijRelationCollectionState.HALTED,
            IntellijRelationCollectionState.ENUMERATION_LIMIT,
            IntellijRelationCollectionState.CONTRACT_REJECTED -> return IntellijRelationProviderItemAdmission.HALTED
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
    fun dismissProviderItem(): Boolean =
        when (val pending = pendingProviderItem) {
            null -> contractHalt()
            else -> {
                nextProviderCursor = nextProviderCursor.advance(pending)
                pendingProviderItem = null
                true
            }
        }

    /**
     * Consumes one already compiler-confirmed edge while enforcing page budgets. A budget halt leaves the pending item
     * unconsumed so a resumed request cannot omit it.
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

        val factBytes = fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (retainedBytes + factBytes > request.budget.returnedBytes.value) {
            return halt(RelationLimitation.BYTE_LIMIT_REACHED)
        }

        nextProviderCursor = nextProviderCursor.advance(pending)
        pendingProviderItem = null
        examined += 1L
        retainedBytes += factBytes
        facts += fact
        observation.count(IntellijReadCounter.RELATION_FACTS)
        return true
    }

    /** Records one explicit compiler/provider coverage loss without manufacturing a fact. */
    fun qualify(limitation: RelationLimitation) {
        limitations += limitation
        observation.terminated(limitation.observedTermination())
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
        observation.terminated(limitation.observedTermination())
        return true
    }

    /** Produces exact, resumable, terminal-incomplete, or typed moved-cursor output. */
    fun finish(termination: IntellijRelationTermination): RelationCompilation {
        if (
            !prefixVerified && state != IntellijRelationCollectionState.ENUMERATION_LIMIT ||
                state == IntellijRelationCollectionState.CURSOR_MOVED
        ) {
            return RelationCompilation.Rejected(RelationCompilerRejection.CONTINUATION_CURSOR_MOVED)
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
                limitations +=
                    termination.limitations.ifEmpty {
                        setOf(RelationLimitation.PROVIDER_INCOMPLETE)
                    }
            is IntellijRelationTermination.Resumable ->
                limitations +=
                    termination.limitations.ifEmpty {
                        setOf(RelationLimitation.PROVIDER_INCOMPLETE)
                    }
        }

        val orderedFacts = facts.distinct().sorted()
        val bytes =
            RelationByteCount.parse(
                    orderedFacts.sumOf {
                        it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                    }
                )
                .refinedOrReject() ?: return contractRejected()
        val work = RelationWorkCount.parse(examined).refinedOrReject() ?: return contractRejected()
        val results = RelationResultCount.parse(orderedFacts.size).refinedOrReject() ?: return contractRejected()
        val batch =
            RelationBatch.create(request, orderedFacts, bytes, work, results).refinedOrReject()
                ?: return contractRejected()

        // Canonical order is unproven after overflow; a continuation cannot promise progress.
        val resumable =
            state != IntellijRelationCollectionState.ENUMERATION_LIMIT &&
                (termination is IntellijRelationTermination.Resumable ||
                    state == IntellijRelationCollectionState.HALTED)
        if (!resumable && limitations.isEmpty()) {
            observation.terminated(IntellijReadTermination.COMPLETE)
            return RelationCompilation.complete(batch)
        }
        val qualified =
            if (resumable) {
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
        observation.terminated(limitation.observedTermination())
        state = IntellijRelationCollectionState.HALTED
        return false
    }

    private fun contractHalt(): Boolean {
        state = IntellijRelationCollectionState.CONTRACT_REJECTED
        return false
    }

    private fun contractRejected(): RelationCompilation.Rejected =
        RelationCompilation.Rejected(RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION)

    private fun <Value, Failure> Refinement<Value, Failure>.refinedOrReject(): Value? =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> null
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal const val MAX_NATIVE_RELATION_CANDIDATES = 10_000

private fun RelationLimitation.observedTermination(): IntellijReadTermination =
    when (this) {
        RelationLimitation.RESULT_LIMIT_REACHED -> IntellijReadTermination.RESULT_LIMIT
        RelationLimitation.BYTE_LIMIT_REACHED -> IntellijReadTermination.BYTE_LIMIT
        RelationLimitation.WORK_LIMIT_REACHED -> IntellijReadTermination.WORK_LIMIT
        RelationLimitation.TIME_LIMIT_REACHED -> IntellijReadTermination.TIME_LIMIT
        RelationLimitation.DUMB_MODE_TRANSITION -> IntellijReadTermination.INDEXING
        RelationLimitation.UNRESOLVED_TARGET -> IntellijReadTermination.RELATION_UNRESOLVED_TARGET
        RelationLimitation.UNSUPPORTED_ITEM -> IntellijReadTermination.RELATION_UNSUPPORTED_ITEM
        RelationLimitation.PROVIDER_FAILURE -> IntellijReadTermination.PROVIDER_FAILURE
        RelationLimitation.PROVIDER_INCOMPLETE -> IntellijReadTermination.RELATION_PROVIDER_INCOMPLETE
    }
