package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryModelFailure
import io.github.amichne.kast.relation.contract.RepresentationPropagationFailure

enum class QueryImpactModelHistoryFailure {
    MISSING_APPLICATION
}

enum class QueryImpactRowIdentityFailure {
    CHANGED_RETAINED_ROWS
}

/** Exact rejected interpreter transitions preserve their finite owning-domain failure. */
sealed interface QueryImpactExecutionFailure {
    data object PresentationOnly : QueryImpactExecutionFailure

    data class Path(val cause: QueryImpactPathFailure) : QueryImpactExecutionFailure

    data class PeerBoundary(val cause: QueryImpactPeerProofFailure) : QueryImpactExecutionFailure

    data class Ledger(val cause: QueryImpactLedgerFailure) : QueryImpactExecutionFailure

    data class Accounting(val cause: QueryValuePathAccountingFailure) : QueryImpactExecutionFailure

    data class Representation(val cause: RepresentationPropagationFailure) : QueryImpactExecutionFailure

    data class Boundary(val cause: BoundaryModelFailure) : QueryImpactExecutionFailure

    data class ModelHistory(val cause: QueryImpactModelHistoryFailure) : QueryImpactExecutionFailure

    data class Selection(val cause: QueryRetainedResultFailure) : QueryImpactExecutionFailure

    data class RowIdentity(val cause: QueryImpactRowIdentityFailure) : QueryImpactExecutionFailure
}
