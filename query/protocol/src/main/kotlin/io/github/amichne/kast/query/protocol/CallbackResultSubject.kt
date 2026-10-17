package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint

internal class CallbackResultSubject
private constructor(
    val native: RelationEndpointFingerprint,
    val reference: QueryReferenceDocument.ExactSymbol,
) {
    companion object {
        fun issue(
            endpoint: RelationEndpoint,
            authority: QueryReferenceAuthority,
        ): Refinement<CallbackResultSubject, RelationEndpointIssuanceFailure> =
            when (val issued = authority.issueEndpoint(endpoint)) {
                is RelationEndpointIssuance.Issued ->
                    Refinement.Refined(
                        CallbackResultSubject(endpoint.fingerprint, QueryReferenceDocument.ExactSymbol(issued.selector))
                    )
                is RelationEndpointIssuance.Rejected -> Refinement.Rejected(issued.failure)
            }
    }
}
