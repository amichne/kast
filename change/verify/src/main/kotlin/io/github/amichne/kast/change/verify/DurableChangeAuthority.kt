package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.ChangePlan

@JvmInline
value class ChangePlanIdentity private constructor(val value: String) {
    companion object {
        fun parse(value: String): ChangePlanIdentity? =
            value.takeIf { PLAN_IDENTITY.matches(it) }?.let(::ChangePlanIdentity)
    }
}

@JvmInline
value class ChangeApplicationIdentity private constructor(val value: String) {
    companion object {
        fun parse(value: String): ChangeApplicationIdentity? =
            value.takeIf { APPLICATION_IDENTITY.matches(it) }?.let(::ChangeApplicationIdentity)
    }
}

@JvmInline
value class ChangeReceiptIdentity private constructor(val value: String) {
    companion object {
        fun parse(value: String): ChangeReceiptIdentity? =
            value.takeIf { RECEIPT_IDENTITY.matches(it) }?.let(::ChangeReceiptIdentity)
    }
}

enum class DurableChangeAuthorityFailure {
    STORAGE_UNAVAILABLE,
    IDENTITY_COLLISION,
    CORRUPT_RECORD,
    UNSUPPORTED_PLAN,
    RECOVERY_EVIDENCE_UNAVAILABLE,
}

sealed interface ChangePlanIssuance {
    data class Issued(val identity: ChangePlanIdentity) : ChangePlanIssuance
    data class Rejected(val failure: DurableChangeAuthorityFailure) : ChangePlanIssuance
}

sealed interface ChangePlanLookup {
    data class Found(val plan: ChangePlan) : ChangePlanLookup
    data object Missing : ChangePlanLookup
    data class Rejected(val failure: DurableChangeAuthorityFailure) : ChangePlanLookup
}

data class PendingChangeVerification(
    val plan: ChangePlan,
    val application: AppliedUnverified,
)

sealed interface ChangeApplicationIssuance {
    data class Issued(val identity: ChangeApplicationIdentity) : ChangeApplicationIssuance
    data class Rejected(val failure: DurableChangeAuthorityFailure) : ChangeApplicationIssuance
}

sealed interface ChangeApplicationLookup {
    data class Found(val application: PendingChangeVerification) : ChangeApplicationLookup
    data object Missing : ChangeApplicationLookup
    data class Rejected(val failure: DurableChangeAuthorityFailure) : ChangeApplicationLookup
}

sealed interface ChangeReceiptIssuance {
    data class Issued(val identity: ChangeReceiptIdentity) : ChangeReceiptIssuance
    data class Rejected(val failure: DurableChangeAuthorityFailure) : ChangeReceiptIssuance
}

interface DurableChangeAuthority {
    fun issuePlan(plan: ChangePlan): ChangePlanIssuance

    fun loadPlan(identity: ChangePlanIdentity): ChangePlanLookup

    fun issueApplication(
        plan: ChangePlan,
        application: AppliedUnverified,
    ): ChangeApplicationIssuance

    fun loadApplication(identity: ChangeApplicationIdentity): ChangeApplicationLookup

    fun issueReceipt(receipt: VerifiedReceipt): ChangeReceiptIssuance
}

private val PLAN_IDENTITY = Regex("plan:[0-9a-f]{64}")
private val APPLICATION_IDENTITY = Regex("application:[0-9a-f]{64}")
private val RECEIPT_IDENTITY = Regex("receipt:[0-9a-f]{64}")
