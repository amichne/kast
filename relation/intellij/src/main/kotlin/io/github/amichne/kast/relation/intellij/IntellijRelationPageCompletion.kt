package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProviderCursor
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.charset.StandardCharsets

internal data class IntellijRelationPageCompletion(
    private val request: RelationRequest,
    private val facts: List<RelationFact>,
    private val occurrences: List<RelationReferenceOccurrence>,
    private val scopeExclusions: List<io.github.amichne.kast.relation.contract.RelationScopeExclusion>,
    private val callbackObservations: List<io.github.amichne.kast.relation.contract.RelationCallbackObservation>,
    private val callableObservations: List<io.github.amichne.kast.relation.contract.RelationCallableObservation>,
    private val examined: Long,
    private val state: IntellijRelationCollectionState,
    private val pending: Boolean,
    private val providerState: RelationProviderState?,
    private val requestedCursor: RelationProviderCursor,
    private val nextCursor: RelationProviderCursor,
    private val limitations: MutableSet<RelationLimitation>,
    private val omissions: IntellijRelationOmissionObservation,
    private val observation: IntellijReadObservation,
) {
    fun finish(termination: IntellijRelationTermination): RelationCompilation {
        if (
            state == IntellijRelationCollectionState.CONTRACT_REJECTED ||
                pending && termination !is IntellijRelationTermination.Resumable
        )
            return contractRejected()
        retainLimitations(termination)
        val batch = batch().refinedOrReject() ?: return contractRejected()
        return qualify(batch, termination)
    }

    private fun retainLimitations(termination: IntellijRelationTermination) {
        val additional =
            when (termination) {
                IntellijRelationTermination.Terminal -> return
                is IntellijRelationTermination.TerminalIncomplete -> termination.limitations
                is IntellijRelationTermination.Resumable -> termination.limitations
            }
        limitations += additional.ifEmpty {
            if (limitations.isEmpty()) setOf(RelationLimitation.PROVIDER_INCOMPLETE) else emptySet()
        }
    }

    private fun batch(): Refinement<RelationBatch, RelationCompilerRejection> {
        val orderedFacts = facts.distinct().sorted()
        val orderedOccurrences = occurrences.distinct().sorted()
        val semanticCount =
            orderedOccurrences.size +
                orderedFacts.count { fact ->
                    orderedOccurrences.none { it.occurrence == fact.occurrence && it.target == fact.target }
                }
        val bytes =
            when (val parsed = RelationByteCount.parse(canonicalEncodedBytes(orderedFacts, orderedOccurrences))) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return compilerContractRejected()
            }
        val work =
            when (val parsed = RelationWorkCount.parse(examined)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return compilerContractRejected()
            }
        val results =
            when (val parsed = RelationResultCount.parse(semanticCount)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return compilerContractRejected()
            }
        return RelationBatch.create(
                request = request,
                facts = orderedFacts,
                encodedBytes = bytes,
                examinedWorkUnits = work,
                resultCount = results,
                referenceOccurrences = orderedOccurrences,
                scopeExclusions = scopeExclusions.distinct().sorted(),
                callbackObservations = callbackObservations.distinct().sorted(),
                callableObservations = callableObservations.distinct().sorted(),
            )
            .withObservedOmissions()
    }

    private fun canonicalEncodedBytes(
        orderedFacts: List<RelationFact>,
        orderedOccurrences: List<RelationReferenceOccurrence>,
    ): Long {
        val byteCount =
            orderedFacts.sumOf { it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong() } +
                orderedOccurrences.sumOf {
                    it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                } +
                scopeExclusions.distinct().sumOf {
                    it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                }
        val callbackBytes =
            callbackObservations.distinct().sumOf {
                it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
        return byteCount +
            callbackBytes +
            callableObservations.distinct().sumOf {
                it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
    }

    private fun Refinement<RelationBatch, io.github.amichne.kast.relation.contract.RelationBatchFailure>
        .withObservedOmissions(): Refinement<RelationBatch, RelationCompilerRejection> {
        val batch =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> return compilerContractRejected()
            }
        val evidence =
            when (val summarized = omissions.summarize(limitations)) {
                is Refinement.Refined -> summarized.value
                is Refinement.Rejected -> return summarized
            }
        return when (val refined = batch.withOmissions(evidence)) {
            is Refinement.Refined -> refined
            is Refinement.Rejected -> compilerContractRejected()
        }
    }

    private fun compilerContractRejected() = Refinement.Rejected(RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION)

    private fun qualify(batch: RelationBatch, termination: IntellijRelationTermination): RelationCompilation {
        // Canonical order is unproven after overflow; a continuation cannot promise progress.
        val retainedWork = providerState?.hasUnfinishedWork == true
        val boundedStop =
            termination is IntellijRelationTermination.Resumable || state == IntellijRelationCollectionState.HALTED
        val resumable = state != IntellijRelationCollectionState.ENUMERATION_LIMIT && retainedWork && boundedStop
        if (!resumable && limitations.isEmpty()) {
            observation.terminated(IntellijReadTermination.COMPLETE)
            return RelationCompilation.complete(batch)
        }
        val advanced = nextCursor.nextPosition.value > requestedCursor.nextPosition.value
        if (resumable && !advanced) {
            limitations += RelationLimitation.PROVIDER_STALLED
            observation.terminated(IntellijReadTermination.RELATION_PROVIDER_STALLED)
        }
        val qualified =
            if (resumable && advanced)
                RelationCompilation.qualifiedResumable(batch, limitations, nextCursor, providerState)
            else RelationCompilation.qualifiedTerminal(batch, limitations)
        return qualified.refinedOrReject() ?: contractRejected()
    }

    private fun contractRejected() = RelationCompilation.Rejected(RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION)

    private fun <Value, Failure> Refinement<Value, Failure>.refinedOrReject(): Value? =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> null
        }
}
