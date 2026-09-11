package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.ChangePlanIdentity

enum class LiveChangeReceiptStoreFailure {
    STORAGE_UNAVAILABLE,
    IDENTITY_COLLISION,
    CORRUPT_RECORD,
    VERSION_UNSUPPORTED,
}

sealed interface LiveChangeReceiptIssuance {
    data class Issued(val identity: ChangeReceiptIdentity, val receipt: HistoricalLiveAddDeclarationReceipt) :
        LiveChangeReceiptIssuance

    data class Rejected(val failure: LiveChangeReceiptStoreFailure) : LiveChangeReceiptIssuance
}

sealed interface LiveChangeReceiptLookup {
    data class Found(val identity: ChangeReceiptIdentity, val receipt: HistoricalLiveAddDeclarationReceipt) :
        LiveChangeReceiptLookup

    data object Missing : LiveChangeReceiptLookup

    data class Rejected(val failure: LiveChangeReceiptStoreFailure) : LiveChangeReceiptLookup
}

/** Only complete applied and verified proof can create a receipt; reads return historical data only. */
interface LiveChangeReceiptStore {
    fun issueReceipt(receipt: VerifiedLiveAddDeclarationReceipt): LiveChangeReceiptIssuance

    fun loadReceipt(planIdentity: ChangePlanIdentity): LiveChangeReceiptLookup
}
