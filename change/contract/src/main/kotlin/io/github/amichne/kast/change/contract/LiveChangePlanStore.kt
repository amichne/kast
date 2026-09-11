package io.github.amichne.kast.change.contract

enum class LiveChangePlanStoreFailure {
    STORAGE_UNAVAILABLE,
    IDENTITY_COLLISION,
    CORRUPT_RECORD,
    VERSION_UNSUPPORTED,
}

sealed interface LiveChangePlanIssuance {
    data class Issued(val identity: ChangePlanIdentity) : LiveChangePlanIssuance

    data class Rejected(val failure: LiveChangePlanStoreFailure) : LiveChangePlanIssuance
}

sealed interface LiveChangePlanLookup {
    data class Found(val plan: LiveAddDeclarationChangePlan) : LiveChangePlanLookup

    data object Missing : LiveChangePlanLookup

    data class Rejected(val failure: LiveChangePlanStoreFailure) : LiveChangePlanLookup
}

/** Historical plan persistence only. An identity or digest grants neither approval nor current write authority. */
interface LiveChangePlanStore {
    fun issuePlan(plan: LiveAddDeclarationChangePlan): LiveChangePlanIssuance

    fun loadPlan(identity: ChangePlanIdentity): LiveChangePlanLookup
}

sealed interface LiveChangeApplicationClaim {
    data class Claimed(val identity: ChangePlanIdentity) : LiveChangeApplicationClaim

    data object AlreadyAttempted : LiveChangeApplicationClaim

    data object Missing : LiveChangeApplicationClaim

    data class Rejected(val failure: LiveChangePlanStoreFailure) : LiveChangeApplicationClaim
}

/** An application attempt is durable and permanent, including attempts interrupted before source mutation. */
interface LiveChangeApplicationStore {
    fun claimApplication(identity: ChangePlanIdentity): LiveChangeApplicationClaim

    fun applicationHistory(identity: ChangePlanIdentity): LiveChangeApplicationHistory
}

sealed interface LiveChangeApplicationHistory {
    data object NeverAttempted : LiveChangeApplicationHistory

    data object Attempted : LiveChangeApplicationHistory

    data object Missing : LiveChangeApplicationHistory

    data class Rejected(val failure: LiveChangePlanStoreFailure) : LiveChangeApplicationHistory
}
