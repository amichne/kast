package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationProviderConsumption
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.retainedLimitations
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.charset.StandardCharsets

/** Request-local bounded collector for already K2-confirmed detached relation facts. */
internal class IntellijRelationCollector(
    private val request: RelationRequest,
    clockNanoseconds: () -> Long = System::nanoTime,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
    private val allowance: IntellijRelationAllowance = IntellijRelationAllowance(clockNanoseconds),
) {
    var providerConsumption: RelationProviderConsumption = RelationProviderConsumption.Unconfirmed
        private set

    private val facts = mutableListOf<RelationFact>()
    private val referenceOccurrences = mutableListOf<RelationReferenceOccurrence>()
    private val evidence = IntellijRelationEvidenceBuffer(request)
    private var providerState: RelationProviderState? =
        (request.position as? RelationReadPosition.Resume)?.continuation?.providerState
    private val limitations = request.retainedLimitations.toMutableSet()
    private val omissions = IntellijRelationOmissionObservation(request.providerCursor.provider)
    private val requestedCursor = request.providerCursor
    private var nextProviderCursor = requestedCursor
    private var pendingProviderItem: RelationProviderItemDescriptor? = null
    private val examined: Long
        get() = allowance.examined

    private var retainedBytes = 0L
    private var state = IntellijRelationCollectionState.COLLECTING

    fun observeInventoryAllowance() {
        observeInventoryGauge(
            IntellijReadGauge.RELATION_ELAPSED_LIMIT_MILLIS,
            request.budget.resources.elapsedTimeLimit.value,
        )
        observeInventoryGauge(
            IntellijReadGauge.RELATION_ELAPSED_BEFORE_PREPARATION_NANOS,
            allowance.elapsedNanoseconds(),
        )
    }

    private fun observeInventoryGauge(gauge: IntellijReadGauge, value: Long) =
        when (val admitted = IntellijReadGaugeValue.parse(value)) {
            is Refinement.Refined -> observation.measure(gauge, admitted.value)
            is Refinement.Rejected -> error("Admitted time and monotone elapsed measurement cannot be negative")
        }

    /** Every native callback checks elapsed admission before even an excluded site's PSI is inspected. */
    fun admitProviderCallback(cancellationCheck: () -> Unit): IntellijRelationProviderEnumerationAdmission {
        cancellationCheck()
        return admitProviderEnumeration()
    }

    /** Bounds native materialization before a canonical provider order can be established. */
    fun admitProviderEnumeration(): IntellijRelationProviderEnumerationAdmission =
        when (allowance.admitEnumeration(state, request.budget.resources)) {
            IntellijRelationEnumerationGrant.READY -> IntellijRelationProviderEnumerationAdmission.READY
            IntellijRelationEnumerationGrant.UNAVAILABLE -> IntellijRelationProviderEnumerationAdmission.HALTED
            IntellijRelationEnumerationGrant.TIME_LIMIT_REACHED -> {
                halt(RelationLimitation.TIME_LIMIT_REACHED)
                IntellijRelationProviderEnumerationAdmission.HALTED
            }
        }

    /** Bounds the native buffers independently of semantic per-page work and result budgets. */
    fun admitProviderCandidate(): IntellijRelationProviderEnumerationAdmission {
        if (admitProviderEnumeration() != IntellijRelationProviderEnumerationAdmission.READY) {
            return IntellijRelationProviderEnumerationAdmission.HALTED
        }
        if (allowance.admitCandidate(limits) == IntellijRelationProviderEnumerationAdmission.HALTED) {
            observation.terminated(IntellijReadTermination.CANDIDATE_CAP)
            limitations += RelationLimitation.CANDIDATE_LIMIT_REACHED
            state = IntellijRelationCollectionState.ENUMERATION_LIMIT
            return IntellijRelationProviderEnumerationAdmission.HALTED
        }
        observation.count(IntellijReadCounter.RELATION_CANDIDATES)
        return IntellijRelationProviderEnumerationAdmission.READY
    }

    /**
     * Admits the next retained provider item before semantic filtering. No consumed native prefix is replayed. An item
     * remains pending until its semantic confirmation, exclusion or omission is committed.
     */
    fun beginProviderItem(item: RelationProviderItemDescriptor): IntellijRelationProviderItemAdmission {
        when (state) {
            IntellijRelationCollectionState.HALTED,
            IntellijRelationCollectionState.ENUMERATION_LIMIT,
            IntellijRelationCollectionState.CONTRACT_REJECTED -> return IntellijRelationProviderItemAdmission.HALTED
            IntellijRelationCollectionState.COLLECTING -> Unit
        }
        if (pendingProviderItem != null) {
            state = IntellijRelationCollectionState.CONTRACT_REJECTED
            return IntellijRelationProviderItemAdmission.HALTED
        }
        if (elapsedLimitReached()) {
            halt(RelationLimitation.TIME_LIMIT_REACHED)
            return IntellijRelationProviderItemAdmission.HALTED
        }
        return admitSemanticItem(item)
    }

    /** A completed unit consumes work; stop before another K2 refinement can begin. */
    private fun admitSemanticItem(item: RelationProviderItemDescriptor): IntellijRelationProviderItemAdmission =
        when {
            examined >= request.budget.resources.workUnitLimit.value ->
                haltAdmission(RelationLimitation.WORK_LIMIT_REACHED)
            evidence.resultCount(evidenceAllowance()) >= request.budget.resources.resultLimit.value ->
                haltAdmission(RelationLimitation.RESULT_LIMIT_REACHED)
            else -> {
                pendingProviderItem = item
                providerConsumption = RelationProviderConsumption.Unconfirmed
                allowance.examine()
                IntellijRelationProviderItemAdmission.READY
            }
        }

    private fun haltAdmission(limitation: RelationLimitation): IntellijRelationProviderItemAdmission {
        halt(limitation)
        return IntellijRelationProviderItemAdmission.HALTED
    }

    /** Commits a provider item that the semantic plan deliberately filtered. */
    fun dismissProviderItem(): Boolean =
        when (val pending = pendingProviderItem) {
            null -> contractHalt()
            else -> {
                nextProviderCursor = nextProviderCursor.advance(pending)
                pendingProviderItem = null
                if (elapsedLimitReached()) halt(RelationLimitation.TIME_LIMIT_REACHED) else true
            }
        }

    /**
     * Consumes one already compiler-confirmed edge while enforcing page budgets. A budget halt leaves the pending item
     * unconsumed so a resumed request cannot omit it.
     */
    fun accept(
        fact: RelationFact,
        callback: RelationCallbackObservation? = null,
    ): Boolean {
        if (state != IntellijRelationCollectionState.COLLECTING) return false
        val pending = pendingProviderItem ?: return contractHalt()
        if (evidence.resultCount(evidenceAllowance()) >= request.budget.resources.resultLimit.value) {
            return halt(RelationLimitation.RESULT_LIMIT_REACHED)
        }

        if (fact in facts) return dismissProviderItem()

        if (callback != null && (!callback.belongsTo(request) || !callback.supportsNamedFact(fact)))
            return contractHalt()
        val factBytes =
            fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong() +
                (callback?.canonicalProjection()?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong() ?: 0L)
        if (retainedBytes + factBytes > request.budget.returnedBytes.value) {
            return halt(RelationLimitation.BYTE_LIMIT_REACHED)
        }

        nextProviderCursor = nextProviderCursor.advance(pending)
        pendingProviderItem = null
        retainedBytes += factBytes
        facts += fact
        if (callback != null) evidence.retainNamedCallback(callback)
        providerConsumption = RelationProviderConsumption.GraphConfirmed(fact)
        observation.count(IntellijReadCounter.RELATION_FACTS)
        return if (elapsedLimitReached()) halt(RelationLimitation.TIME_LIMIT_REACHED) else true
    }

    /** Retains a proven domain exit before consuming its provider item; an overflow remains resumable. */
    fun acceptScopeExclusion(value: RelationScopeExclusion): Boolean =
        acceptEvidence({ evidence.accept(value, evidenceAllowance()) }) { bytes -> retainedBytes += bytes.value }

    /** Callback ownership evidence consumes the same bounded page allowance as other exact evidence. */
    fun acceptCallbackObservation(
        value: RelationCallbackObservation,
        unavailable: RelationLimitation? = null,
    ): Boolean =
        acceptEvidence({ evidence.accept(value, evidenceAllowance()) }) { bytes ->
            if (unavailable != null) {
                omissions.record(unavailable, RelationOmissionSample.Located(value.occurrence))
                qualify(unavailable)
            }
            retainedBytes += bytes.value
        }

    /** Exact symbolic targets share page bounds with named edges and remain detached evidence. */
    fun acceptCallableObservation(value: RelationCallableObservation): Boolean =
        acceptEvidence({ evidence.accept(value, evidenceAllowance()) }) { bytes ->
            retainedBytes += bytes.value
            val target = value.target
            if (
                target is RelationCallableTarget.SourceLess &&
                    target.disposition == SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE
            ) {
                omissions.record(RelationLimitation.UNSUPPORTED_ITEM, RelationOmissionSample.Located(value.occurrence))
                qualify(RelationLimitation.UNSUPPORTED_ITEM)
            }
        }

    private fun acceptEvidence(
        admission: () -> IntellijRelationEvidenceAdmission,
        recorded: (RelationByteCount) -> Unit,
    ): Boolean {
        if (state != IntellijRelationCollectionState.COLLECTING) return false
        if (pendingProviderItem == null) return contractHalt()
        return when (val outcome = admission()) {
            IntellijRelationEvidenceAdmission.ContractRejected -> contractHalt()
            IntellijRelationEvidenceAdmission.Duplicate -> dismissProviderItem()
            is IntellijRelationEvidenceAdmission.Limited -> halt(outcome.limitation)
            is IntellijRelationEvidenceAdmission.Recorded -> {
                recorded(outcome.encodedBytes)
                dismissProviderItem()
            }
        }
    }

    private fun evidenceAllowance() = IntellijRelationEvidenceAllowance(facts, referenceOccurrences, retainedBytes)

    /** Records one explicit compiler/provider coverage loss without manufacturing a fact. */
    fun qualify(limitation: RelationLimitation) {
        limitations += limitation
        observation.terminated(limitation.observedTermination())
    }

    /** Records semantic work that could not produce an exact detached fact. */
    fun examineIncomplete(
        limitation: RelationLimitation,
        sample: RelationOmissionSample = RelationOmissionSample.Unavailable,
    ): Boolean {
        if (state != IntellijRelationCollectionState.COLLECTING) return false
        val pending = pendingProviderItem ?: return contractHalt()
        nextProviderCursor = nextProviderCursor.advance(pending)
        pendingProviderItem = null
        omissions.record(limitation, sample)
        observation.count(IntellijReadCounter.RELATION_ITEMS_OMITTED)
        limitations += limitation
        observation.terminated(limitation.observedTermination())
        return if (elapsedLimitReached()) halt(RelationLimitation.TIME_LIMIT_REACHED) else true
    }

    /** Completion consumes a detached snapshot; this collector remains the only mutable native attempt owner. */
    fun finish(termination: IntellijRelationTermination): RelationCompilation =
        IntellijRelationPageCompletion(
                request = request,
                facts = facts,
                occurrences = referenceOccurrences,
                scopeExclusions = evidence.scopeExclusions,
                callbackObservations = evidence.callbackObservations,
                callableObservations = evidence.callableObservations,
                examined = examined,
                state = state,
                pending = pendingProviderItem != null,
                providerState = providerState,
                requestedCursor = requestedCursor,
                nextCursor = nextProviderCursor,
                limitations = limitations.toMutableSet(),
                omissions = omissions,
                observation = observation,
            )
            .finish(termination)

    fun blockPartition(limitation: RelationLimitation): Boolean {
        limitations += limitation
        state = IntellijRelationCollectionState.ENUMERATION_LIMIT
        pendingProviderItem = null
        observation.terminated(limitation.observedTermination())
        return false
    }

    val providerItemConsumed: Boolean
        get() = pendingProviderItem == null

    fun retainProviderState(value: RelationProviderState, preparedPartition: Boolean = false): Boolean {
        if (value.provider != requestedCursor.provider) return contractHalt()
        if (preparedPartition) {
            if (requestedCursor.nextPosition.value != 0L) return contractHalt()
            nextProviderCursor = value.providerCursor
        } else if (nextProviderCursor != value.providerCursor) return contractHalt()
        providerState = value
        if (value.retainedBytes > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value) {
            return blockPartition(RelationLimitation.RETENTION_LIMIT_REACHED)
        }
        return true
    }

    fun acceptReference(value: RelationReferenceOccurrence): Boolean {
        if (state != IntellijRelationCollectionState.COLLECTING) return false
        val pending = pendingProviderItem ?: return contractHalt()
        val fact =
            when (val projected = value.declarationFact(request)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> null
            }
        val bytes =
            value.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong() +
                (fact?.canonicalProjection()?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong() ?: 0L)
        if (retainedBytes + bytes > request.budget.returnedBytes.value)
            return halt(RelationLimitation.BYTE_LIMIT_REACHED)
        nextProviderCursor = nextProviderCursor.advance(pending)
        pendingProviderItem = null
        retainedBytes += bytes
        providerConsumption = RelationProviderConsumption.Confirmed(value)
        referenceOccurrences += value
        omissions.observeReferenceOwnership(value).forEach(::qualify)
        if (fact != null) facts += fact
        observation.count(IntellijReadCounter.RELATION_FACTS)
        return if (elapsedLimitReached()) halt(RelationLimitation.TIME_LIMIT_REACHED) else true
    }

    fun admitCallbackWork(): CallbackWorkAdmission = allowance.admitCallbackWork(request.budget.resources)

    private fun elapsedLimitReached(): Boolean = allowance.elapsedLimitReached(request.budget.resources)

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
}
