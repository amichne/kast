package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** A first-page exact-symbol read may recover its handle; source snapshots and later pages remain strict. */
internal suspend fun SourceReadRequest.admitFreshRead(
    authority: QueryReferenceAuthority,
    current: SemanticReadAuthority,
    budget: SourceProtocolBudget,
): SourceRequestAdmission {
    val request = this
    val admissionAuthority =
        if (
            request.page == io.github.amichne.kast.protocol.contract.SourceReadPageDocument.First &&
                request.anchor is io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument.Symbol
        )
            authority.admitReadReferences(
                listOf(
                    (request.anchor as io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument.Symbol)
                        .selector
                ),
                current,
            )
        else authority
    val resources =
        when (val remaining = authority.remainingReadBudget(budget.resources)) {
            is Refinement.Refined -> remaining.value
            is Refinement.Rejected ->
                return SourceRequestAdmission.Rejected(
                    SourceReadFailureDetail.ReferenceRejected(
                        io.github.amichne.kast.protocol.contract.SourceReferenceRole.SYMBOL,
                        remaining.failure.decodingFailure().sourceFailure(),
                    )
                )
        }
    return admit(admissionAuthority, current, budget.copy(resources = resources))
}
