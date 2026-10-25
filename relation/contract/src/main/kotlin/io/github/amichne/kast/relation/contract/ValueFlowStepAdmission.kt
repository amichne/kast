package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun ValueFlowTerminal.validateObligations(
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

internal fun ValueFlowTerminal.validateStepEvidence(
    source: ValueSite,
    domain: RelationRequest,
    transfers: List<ValueTransfer>,
    reviewedTransfers: List<ReviewedValueTransfer>,
    obligations: List<ValueFlowObligation>,
): Refinement<Unit, ValueFlowStepFailure> {
    if (
        domain.subject.lease.identity != source.basis ||
            ValueDeclarationIdentity(domain.subject.compilerIdentity, domain.subject.file, domain.subject.range) !=
                source.identity.owner
    )
        return Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH)
    if (transfers.any { it.source != source }) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
    if (reviewedTransfers.any { it.source != source }) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
    if (obligations.any { it.site.basis != source.basis })
        return Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH)
    if (obligations.any { it.site != source }) return Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH)
    return validateObligations(obligations)
}
