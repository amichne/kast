package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.contract.LiveChangePlan

/** Persisted proof of a completed live variant. Restoration grants no live mutation authority. */
sealed interface HistoricalLiveChangeReceipt {
    val plan: LiveChangePlan
    val after: LiveChangeBasis
    val identity: ChangeReceiptIdentity
}
