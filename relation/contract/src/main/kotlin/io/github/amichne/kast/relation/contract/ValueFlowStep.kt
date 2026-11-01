package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** A single exact input must account for every discovered supported output and every unsupported arrival. */
class ValueFlowStep
private constructor(
    val source: ValueSite,
    val domain: RelationRequest,
    val transfers: List<ValueTransfer>,
    val obligations: List<ValueFlowObligation>,
    val terminal: ValueFlowTerminal,
    val examinedWorkUnits: RelationWorkCount,
    val receipts: List<ValueFlowWorkReceipt>,
    val reviewedTransfers: List<ReviewedValueTransfer>,
    val stringConstructions: List<ValueStringConstruction> = emptyList(),
) {
    val outputCount: Int
        get() = transfers.size + reviewedTransfers.size

    val retainedBytes: Long =
        detachedByteCount(source, domain, transfers, obligations, reviewedTransfers)
            .value
            .addBytes((receipts.size - 1L).multiplyBytes(4096L))
            .addBytes(stringConstructions.sumOf { it.retainedBytes })

    /** Add only compiler-proven successful absence, with the original grant and exact source. */
    fun withStringConstructions(
        records: List<ValueStringConstruction>
    ): Refinement<ValueFlowStep, ValueFlowStepFailure> {
        if (records.isEmpty() || records.all { it in stringConstructions }) return Refinement.Refined(this)
        if (receipts.size != 1) return Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
        if (records.any { it.source != source }) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
        val all = (stringConstructions + records).distinct()
        val addedBytes = all.sumOf { it.retainedBytes }
        if (
            !domain.budget.permitsReturnedBytes(
                detachedByteCount(source, domain, transfers, obligations, reviewedTransfers).value.addBytes(addedBytes)
            )
        )
            return Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED)
        val receipt =
            ValueFlowWorkReceipt(
                domain,
                examinedWorkUnits,
                (RelationResultCount.parse(outputCount) as Refinement.Refined).value,
                (RelationByteCount.parse(
                        detachedByteCount(source, domain, transfers, obligations, reviewedTransfers)
                            .value
                            .addBytes(addedBytes)
                    ) as Refinement.Refined)
                    .value,
            )
        return Refinement.Refined(
            ValueFlowStep(
                source,
                domain,
                transfers,
                obligations,
                terminal,
                examinedWorkUnits,
                listOf(receipt),
                reviewedTransfers,
                Collections.unmodifiableList(all),
            )
        )
    }

    /**
     * Shares full callable and execution-owner proofs across retained reads. Each invocation, role, range, branch, and
     * read stays distinct. The temporary lookup is local to this pure transformation.
     */
    fun shareCallableEvidence(previous: Collection<ValueFlowStep>): ValueFlowStep =
        shareCallableEvidence(ValueFlowProofSharing(previous))

    internal fun shareCallableEvidence(proofs: ValueFlowProofSharing): ValueFlowStep {
        val sites = ValueFlowSiteSharing(proofs)
        val sharedSource = sites.site(source)
        val sharedTransfers = transfers.map { it.shareCallableEvidence(sites) }
        val sharedReviewed = reviewedTransfers.map { it.shareCallableEvidence(sites) }
        val sharedObligations = obligations.map { obligation ->
            val sharedSite = sites.site(obligation.site)
            if (sharedSite === obligation.site) obligation else obligation.shareCallableEvidence { sharedSite }
        }
        if (sharedSource === source && evidenceReferencesUnchanged(sharedTransfers, sharedReviewed, sharedObligations))
            return this
        return ValueFlowStep(
            sharedSource,
            domain,
            java.util.Collections.unmodifiableList(sharedTransfers),
            java.util.Collections.unmodifiableList(sharedObligations),
            terminal,
            examinedWorkUnits,
            receipts,
            java.util.Collections.unmodifiableList(sharedReviewed),
            stringConstructions,
        )
    }

    private fun evidenceReferencesUnchanged(
        sharedTransfers: List<ValueTransfer>,
        sharedReviewed: List<ReviewedValueTransfer>,
        sharedObligations: List<ValueFlowObligation>,
    ): Boolean =
        transfers.zip(sharedTransfers).all { (a, b) -> a === b } &&
            reviewedTransfers.zip(sharedReviewed).all { (a, b) -> a === b } &&
            obligations.zip(sharedObligations).all { (a, b) -> a === b }

    /** Only a retained unfinished read can accumulate a freshly validated page; receipts stay page-local. */
    fun append(page: ValueFlowStep): Refinement<ValueFlowStep, ValueFlowStepFailure> {
        if (terminal != ValueFlowTerminal.ResourceSuspended || page.receipts.size != 1)
            return Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
        if (page.source != source) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
        if (page.domain.boundary != domain.boundary) return Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH)
        if (page.transfers.any { it in transfers }) return Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
        if (page.reviewedTransfers.any { it in reviewedTransfers })
            return Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
        val allReceipts = Collections.unmodifiableList(receipts + page.receipts)
        val work =
            allReceipts.fold(0L) { total, receipt ->
                if (receipt.examinedWorkUnits.value > Long.MAX_VALUE - total) Long.MAX_VALUE
                else total + receipt.examinedWorkUnits.value
            }
        val allObligations = (obligations + page.obligations).distinct()
        val end =
            if (page.terminal == ValueFlowTerminal.ResourceSuspended) page.terminal
            else if (allObligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted
            else ValueFlowTerminal.Unresolved
        return Refinement.Refined(
            ValueFlowStep(
                source,
                domain,
                Collections.unmodifiableList(transfers + page.transfers),
                Collections.unmodifiableList(allObligations),
                end,
                (RelationWorkCount.parse(work) as Refinement.Refined).value,
                allReceipts,
                Collections.unmodifiableList(reviewedTransfers + page.reviewedTransfers),
                Collections.unmodifiableList((stringConstructions + page.stringConstructions).distinct()),
            )
        )
    }

    companion object {
        /** Conservative detached capacity; final wire bytes remain the query presentation owner's budget. */
        fun detachedByteCount(
            source: ValueSite,
            domain: RelationRequest,
            transfers: List<ValueTransfer>,
            obligations: List<ValueFlowObligation>,
            reviewedTransfers: List<ReviewedValueTransfer> = emptyList(),
        ): RelationByteCount = valueFlowStorageBytes(source, domain, transfers, obligations, reviewedTransfers)

        fun fromCompiler(
            source: ValueSite,
            transfers: List<ValueTransfer>,
            obligations: List<ValueFlowObligation>,
            terminal: ValueFlowTerminal,
            domain: RelationRequest,
            examinedWorkUnits: RelationWorkCount,
            remainderBytes: RelationByteCount = (RelationByteCount.parse(0) as Refinement.Refined).value,
        ): Refinement<ValueFlowStep, ValueFlowStepFailure> =
            fromEvidence(
                source,
                transfers,
                obligations,
                terminal,
                domain,
                examinedWorkUnits,
                emptyList(),
                remainderBytes,
            )

        fun fromEvidence(
            source: ValueSite,
            transfers: List<ValueTransfer>,
            obligations: List<ValueFlowObligation>,
            terminal: ValueFlowTerminal,
            domain: RelationRequest,
            examinedWorkUnits: RelationWorkCount,
            reviewedTransfers: List<ReviewedValueTransfer>,
            remainderBytes: RelationByteCount = (RelationByteCount.parse(0) as Refinement.Refined).value,
        ): Refinement<ValueFlowStep, ValueFlowStepFailure> {
            if (!validStepProgress(source, transfers, terminal))
                return Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
            if (!validReviewedStepProgress(reviewedTransfers, terminal))
                return Refinement.Rejected(ValueFlowStepFailure.INVALID_PROGRESS)
            if (!domain.budget.permitsOutputCount(transfers.size + reviewedTransfers.size))
                return Refinement.Rejected(ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED)
            if (
                !domain.budget.permitsReturnedBytes(
                    detachedByteCount(source, domain, transfers, obligations, reviewedTransfers)
                        .value
                        .addBytes(remainderBytes.value)
                )
            )
                return Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED)
            if (!domain.budget.resources.permitsWork(examinedWorkUnits.value))
                return Refinement.Rejected(ValueFlowStepFailure.WORK_LIMIT_EXCEEDED)
            when (
                val admitted = terminal.validateStepEvidence(source, domain, transfers, reviewedTransfers, obligations)
            ) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            val receipt =
                receipt(source, domain, transfers, obligations, examinedWorkUnits, remainderBytes, reviewedTransfers)
            return Refinement.Refined(
                ValueFlowStep(
                    source,
                    domain,
                    Collections.unmodifiableList(transfers.toList()),
                    Collections.unmodifiableList(obligations.toList()),
                    terminal,
                    examinedWorkUnits,
                    listOf(receipt),
                    Collections.unmodifiableList(reviewedTransfers.toList()),
                )
            )
        }

        private fun receipt(
            source: ValueSite,
            domain: RelationRequest,
            transfers: List<ValueTransfer>,
            obligations: List<ValueFlowObligation>,
            work: RelationWorkCount,
            remainderBytes: RelationByteCount,
            reviewedTransfers: List<ReviewedValueTransfer>,
        ): ValueFlowWorkReceipt =
            ValueFlowWorkReceipt(
                domain,
                work,
                (RelationResultCount.parse(transfers.size + reviewedTransfers.size) as Refinement.Refined).value,
                (RelationByteCount.parse(
                        detachedByteCount(source, domain, transfers, obligations, reviewedTransfers).value +
                            remainderBytes.value
                    ) as Refinement.Refined)
                    .value,
            )
    }
}

private fun validStepProgress(source: ValueSite, transfers: List<ValueTransfer>, terminal: ValueFlowTerminal): Boolean =
    transfers.distinct().size == transfers.size &&
        (terminal != ValueFlowTerminal.ResourceSuspended || source.role.hasReferenceReads())

private fun validReviewedStepProgress(transfers: List<ReviewedValueTransfer>, terminal: ValueFlowTerminal): Boolean =
    transfers.distinct().size == transfers.size &&
        (terminal != ValueFlowTerminal.ResourceSuspended || transfers.isEmpty())
