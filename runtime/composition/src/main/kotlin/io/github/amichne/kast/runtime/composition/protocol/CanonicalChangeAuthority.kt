package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.verify.VerifiedReceipt
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal enum class ChangeAuthorityIssuanceFailure {
    IDENTITY_COLLISION,
}

internal sealed interface ChangePlanIssuance {
    data class Issued(val identity: ProtocolText) : ChangePlanIssuance
    data class Rejected(val failure: ChangeAuthorityIssuanceFailure) : ChangePlanIssuance
}

internal sealed interface ChangePlanLookup {
    data class Found(val plan: ChangePlan) : ChangePlanLookup
    data object Missing : ChangePlanLookup
}

internal data class PendingChangeVerification(
    val plan: ChangePlan,
    val applied: AppliedUnverified,
)

internal sealed interface ChangeApplicationIssuance {
    data class Issued(val identity: ProtocolText) : ChangeApplicationIssuance
    data class Rejected(val failure: ChangeAuthorityIssuanceFailure) : ChangeApplicationIssuance
}

internal sealed interface ChangeApplicationLookup {
    data class Found(val application: PendingChangeVerification) : ChangeApplicationLookup
    data object Missing : ChangeApplicationLookup
}

internal sealed interface ChangeReceiptIssuance {
    data class Issued(val identity: ProtocolText) : ChangeReceiptIssuance
    data class Rejected(val failure: ChangeAuthorityIssuanceFailure) : ChangeReceiptIssuance
}

/** Operation-specific authority retained across the plan, apply, and verify transitions. */
internal class CanonicalChangeAuthority {
    private val plans = ConcurrentHashMap<ProtocolText, ChangePlan>()
    private val applications = ConcurrentHashMap<ProtocolText, PendingChangeVerification>()
    private val receipts = ConcurrentHashMap<ProtocolText, VerifiedReceipt>()

    /**
     * Proof transition: `ChangePlan -> ChangePlanIssuance`.
     *
     * Preserves the deterministic typed plan behind its opaque plan identity.
     * [ChangeAuthorityIssuanceFailure] closes a conflicting identity. Raw plan identity leaves
     * only at this public protocol boundary.
     */
    fun issuePlan(plan: ChangePlan): ChangePlanIssuance {
        val handle = changeHandle("plan", listOf(plan.planId.value))
        val prior = plans.putIfAbsent(handle, plan)
        return if (prior == null || prior.planId == plan.planId) {
            ChangePlanIssuance.Issued(handle)
        } else {
            ChangePlanIssuance.Rejected(ChangeAuthorityIssuanceFailure.IDENTITY_COLLISION)
        }
    }

    /**
     * Proof transition: `ProtocolText -> ChangePlanLookup`.
     *
     * Restores only a prior typed plan. Manufactured or missing identities remain
     * [ChangePlanLookup.Missing]. Raw text lookup is confined to this authority boundary.
     */
    fun plan(identity: ProtocolText): ChangePlanLookup =
        plans[identity]?.let(ChangePlanLookup::Found) ?: ChangePlanLookup.Missing

    /**
     * Proof transition: `(ChangePlan, AppliedUnverified) -> ChangeApplicationIssuance`.
     *
     * Preserves the exact plan plus physically applied, still-unverified state behind one opaque
     * application identity. [ChangeAuthorityIssuanceFailure] closes identity collision. Raw
     * content hashes leave only while framing the opaque protocol identity.
     */
    fun issueApplication(
        plan: ChangePlan,
        applied: AppliedUnverified,
    ): ChangeApplicationIssuance {
        val handle = changeHandle(
            "application",
            listOf(plan.planId.value, applied.postimage.value),
        )
        val application = PendingChangeVerification(plan, applied)
        val prior = applications.putIfAbsent(handle, application)
        return if (
            prior == null ||
            prior.plan.planId == plan.planId && prior.applied.postimage == applied.postimage
        ) {
            ChangeApplicationIssuance.Issued(handle)
        } else {
            ChangeApplicationIssuance.Rejected(ChangeAuthorityIssuanceFailure.IDENTITY_COLLISION)
        }
    }

    /**
     * Proof transition: `ProtocolText -> ChangeApplicationLookup`.
     *
     * Restores only a prior plan-bound [AppliedUnverified]. Missing identities remain
     * [ChangeApplicationLookup.Missing]. Raw text lookup is confined to this boundary.
     */
    fun application(identity: ProtocolText): ChangeApplicationLookup =
        applications[identity]
            ?.let(ChangeApplicationLookup::Found)
            ?: ChangeApplicationLookup.Missing

    /**
     * Proof transition: `VerifiedReceipt -> ChangeReceiptIssuance`.
     *
     * Preserves final verified success behind a plan/result-generation identity.
     * [ChangeAuthorityIssuanceFailure] closes collision. Raw generation extraction occurs only at
     * this final protocol projection boundary.
     */
    fun issueReceipt(receipt: VerifiedReceipt): ChangeReceiptIssuance {
        val handle = changeHandle(
            "receipt",
            listOf(
                receipt.planId.value,
                receipt.resultingWorkspace.generation.value.toString(),
            ),
        )
        val prior = receipts.putIfAbsent(handle, receipt)
        return if (
            prior == null ||
            prior.planId == receipt.planId &&
            prior.resultingWorkspace.generation == receipt.resultingWorkspace.generation
        ) {
            ChangeReceiptIssuance.Issued(handle)
        } else {
            ChangeReceiptIssuance.Rejected(ChangeAuthorityIssuanceFailure.IDENTITY_COLLISION)
        }
    }
}

/**
 * Proof transition: `(String, List<String>) -> ProtocolText`.
 *
 * Establishes a fixed-prefix SHA-256 opaque change identity. Strong plan, application, and receipt
 * values are the only field source; the fixed representation is always bounded and non-blank.
 */
private fun changeHandle(prefix: String, fields: List<String>): ProtocolText {
    val canonical = buildString {
        fields.forEach { field ->
            append(field.toByteArray(StandardCharsets.UTF_8).size)
            append(':')
            append(field)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return when (val parsed = ProtocolText.parse("$prefix:$digest")) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("canonical change handle is bounded and non-blank")
    }
}
