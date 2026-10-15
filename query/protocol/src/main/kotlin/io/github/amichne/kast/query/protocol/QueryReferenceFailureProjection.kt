package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryRunRejection

internal fun CanonicalSelectorDecoding.Rejected.queryReferenceRejection(
    position: Int,
    token: ProtocolText,
): QueryRunRejection.ReferenceRejected =
    when (val mismatch = authorityMismatch) {
        null -> QueryRunRejection.ReferenceRejected(queryPosition(position), failure.queryRejection(token))
        else -> QueryRunRejection.ReferenceRejected.staleAuthority(queryPosition(position), mismatch)
    }
