package io.github.amichne.kast.change.contract

@JvmInline
value class ChangePlanIdentity private constructor(val value: String) {
    companion object {
        fun parse(value: String): ChangePlanIdentity? =
            value.takeIf { PLAN_IDENTITY.matches(it) }?.let(::ChangePlanIdentity)
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

/** Durable plan storage grants no source, verification, or workspace lifecycle capability. */
fun interface ChangePlanIssuanceOperations {
    fun issuePlan(plan: ChangePlan): ChangePlanIssuance
}

private val PLAN_IDENTITY = Regex("plan:[0-9a-f]{64}")
