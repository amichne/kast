package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryResultRetention

internal fun previewRetention(issuance: QueryResultIssuance?): QueryResultRetention =
    when (issuance) {
        null -> QueryResultRetention.NotRequested
        is QueryResultIssuance.Issued -> QueryResultRetention.Retained(issuance.reference)
        QueryResultIssuance.Unavailable,
        QueryResultIssuance.CapacityExceeded -> QueryResultRetention.CapacityExceeded
    }
