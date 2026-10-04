package io.github.amichne.kast.query.contract

/** Connectivity-only rows cannot stand in for a conserved investigation witness. */
sealed interface QueryValuePathAccounting {
    data object EvidenceOnly : QueryValuePathAccounting

    data class Investigated(val ledger: QueryImpactLedger) : QueryValuePathAccounting
}

enum class QueryValuePathAccountingFailure {
    DUPLICATE_PATH
}

/** Derived by the immutable row owner; service scheduling consumes this status without reconstructing proof. */
sealed interface QueryValuePathAccountingStatus {
    data object EvidenceOnly : QueryValuePathAccountingStatus

    class Unresolved internal constructor(val required: Set<QueryImpactRequiredObligation>) :
        QueryValuePathAccountingStatus

    class SelectedSubset internal constructor(val originalClosure: QueryImpactClosure) : QueryValuePathAccountingStatus

    data object Conserved : QueryValuePathAccountingStatus
}

val QueryRows.ValuePaths.accountingStatus: QueryValuePathAccountingStatus
    get() =
        when (val witness = accounting) {
            QueryValuePathAccounting.EvidenceOnly -> QueryValuePathAccountingStatus.EvidenceOnly
            is QueryValuePathAccounting.Investigated ->
                when {
                    values.size != witness.ledger.paths.size || values.toSet() != witness.ledger.paths.toSet() ->
                        QueryValuePathAccountingStatus.SelectedSubset(witness.ledger.closure)
                    else ->
                        when (val closure = witness.ledger.closure) {
                            QueryImpactClosure.Discharged -> QueryValuePathAccountingStatus.Conserved
                            is QueryImpactClosure.Unresolved ->
                                QueryValuePathAccountingStatus.Unresolved(closure.required)
                        }
                }
        }

internal fun QueryRows.ValuePaths.hasCompleteAccounting(): Boolean =
    accountingStatus == QueryValuePathAccountingStatus.Conserved
