package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

/** Each child route or terminal path consumes work before its construction. */
internal fun impactExpansionWork(
    observation: ValueFlowStep,
    models: QueryImpactModelSelection,
    rejectedPrefix: Boolean,
): QueryWorkCount {
    val modeled = models.size != 0L
    val arrivals = observation.transfers.size.toLong() + observation.applications.size + models.size
    val obligations =
        observation.obligations.count {
            !modeled || it.cause != ValueFlowUnsupportedCause.UNMODELED_CALL
        }
    val supportedEnd =
        !modeled &&
            observation.transfers.isEmpty() &&
            observation.applications.isEmpty() &&
            observation.obligations.isEmpty() &&
            observation.terminal == ValueFlowTerminal.SupportedDomainExhausted
    val count = arrivals + obligations + (if (supportedEnd) 1 else 0) + (if (rejectedPrefix) 1 else 0)
    return when (val work = QueryWorkCount.parse(count)) {
        is Refinement.Refined -> work.value
        is Refinement.Rejected -> error("Finite impact branch counts cannot be negative")
    }
}

internal fun QueryImpactReadAcquisition.Ready.unfinishedTransition(): QueryImpactTaskTransition =
    when (this) {
        is QueryImpactReadAcquisition.Ready.Cached -> QueryImpactTaskTransition.NotStarted
        is QueryImpactReadAcquisition.Ready.Acquired -> QueryImpactTaskTransition.Suspended
    }
