package io.github.amichne.kast.topology.build

import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.topology.contract.SemanticSnapshotReuseFailure
import io.github.amichne.kast.topology.contract.SemanticSnapshotReuseProof

sealed interface SemanticCallbackLookup {
    data object Missing : SemanticCallbackLookup

    data class Current(val summary: CallbackParameterSummary) : SemanticCallbackLookup

    /**
     * This previous summary remains stale until every retained endpoint is re-admitted under proof.current.authority.
     */
    data class Reusable(val previous: CallbackParameterSummary, val proof: SemanticSnapshotReuseProof) :
        SemanticCallbackLookup

    data class Invalidated(val cause: SemanticSnapshotReuseFailure) : SemanticCallbackLookup

    data class Rejected(val cause: SemanticCallbackStoreFailure) : SemanticCallbackLookup
}

sealed interface SemanticCallbackPublication {
    data object Published : SemanticCallbackPublication

    data object CapacityExceeded : SemanticCallbackPublication

    data class Rejected(val cause: SemanticCallbackStoreFailure) : SemanticCallbackPublication
}

sealed interface SemanticCallbackSupplierLookup {
    data object Missing : SemanticCallbackSupplierLookup

    data class Current(val inventory: CompleteCallbackSupplierInventory) : SemanticCallbackSupplierLookup

    data class Reusable(val previous: CompleteCallbackSupplierInventory, val proof: SemanticSnapshotReuseProof) :
        SemanticCallbackSupplierLookup

    data class Invalidated(val cause: SemanticSnapshotReuseFailure) : SemanticCallbackSupplierLookup

    data class Rejected(val cause: SemanticCallbackStoreFailure) : SemanticCallbackSupplierLookup
}

sealed interface SemanticNamedRelationLookup {
    data object Missing : SemanticNamedRelationLookup

    data class Reusable(
        val previous: io.github.amichne.kast.relation.contract.CompleteNamedRelationPartition,
        val proof: SemanticSnapshotReuseProof,
    ) : SemanticNamedRelationLookup

    data class Invalidated(val cause: SemanticSnapshotReuseFailure) : SemanticNamedRelationLookup

    data class Rejected(val cause: SemanticCallbackStoreFailure) : SemanticNamedRelationLookup
}
