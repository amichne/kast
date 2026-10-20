package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun LocalBindingReadRemainder?.admitValueFlow(
    source: ValueSite,
    domain: RelationRequest,
): Refinement<Unit, ValueFlowStepFailure> =
    when {
        this == null -> Refinement.Refined(Unit)
        this.source != source -> Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
        boundary != domain.boundary -> Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH)
        else -> Refinement.Refined(Unit)
    }

internal fun RelationRequest.admitValueFlowSource(source: ValueSite): Refinement<Unit, ValueFlowStepFailure> =
    if (subject.lease.identity != source.basis || subject.valueIdentity != source.identity.owner)
        Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH)
    else Refinement.Refined(Unit)

internal fun ValueFlowTerminal.admitValueFlowObligations(
    obligations: List<ValueFlowObligation>
): Refinement<Unit, ValueFlowStepFailure> =
    when (this) {
        ValueFlowTerminal.ResourceSuspended -> Refinement.Refined(Unit)
        ValueFlowTerminal.SupportedDomainExhausted ->
            if (obligations.isEmpty()) Refinement.Refined(Unit)
            else Refinement.Rejected(ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS)
        ValueFlowTerminal.Unresolved ->
            if (obligations.isNotEmpty()) Refinement.Refined(Unit)
            else Refinement.Rejected(ValueFlowStepFailure.MISSING_OBLIGATION)
    }

internal fun validValueFlowProgress(
    source: ValueSite,
    transfers: List<ValueTransfer>,
    applications: List<ValueModelApplication>,
    terminal: ValueFlowTerminal,
): Boolean =
    transfers.distinct().size == transfers.size &&
        applications.distinct().size == applications.size &&
        (terminal != ValueFlowTerminal.ResourceSuspended || source.role.hasReferenceReads())

internal fun admitValueFlowSources(
    source: ValueSite,
    transfers: List<ValueTransfer>,
    applications: List<ValueModelApplication>,
    obligations: List<ValueFlowObligation>,
): Refinement<Unit, ValueFlowStepFailure> =
    when {
        transfers.any { it.source != source } || applications.any { it.source != source } ->
            Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
        applications.any { it.target.basis != source.basis || it.binding.artifact.basis != source.basis } ||
            obligations.any { it.site.basis != source.basis } ->
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH)
        obligations.any { it.site != source } -> Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
        else -> Refinement.Refined(Unit)
    }
