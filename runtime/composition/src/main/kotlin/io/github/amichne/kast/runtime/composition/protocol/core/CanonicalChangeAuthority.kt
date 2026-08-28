package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.verify.ChangeApplicationIdentity
import io.github.amichne.kast.change.verify.ChangeApplicationIssuance
import io.github.amichne.kast.change.verify.ChangeApplicationLookup
import io.github.amichne.kast.change.verify.ChangePlanIdentity
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangePlanLookup
import io.github.amichne.kast.change.verify.ChangeReceiptIdentity
import io.github.amichne.kast.change.verify.ChangeReceiptIssuance
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.change.verify.DurableChangeAuthorityFailure
import io.github.amichne.kast.change.verify.PendingChangeVerification
import io.github.amichne.kast.change.verify.VerifiedReceipt
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Process-local authority retained for the isolated runtime; hosted composition injects SQLite. */
internal class CanonicalChangeAuthority : DurableChangeAuthority {
    private val plans = ConcurrentHashMap<ChangePlanIdentity, ChangePlan>()
    private val applications =
        ConcurrentHashMap<ChangeApplicationIdentity, PendingChangeVerification>()
    private val receipts = ConcurrentHashMap<ChangeReceiptIdentity, VerifiedReceipt>()

    override fun issuePlan(plan: ChangePlan): ChangePlanIssuance {
        val handle = requireNotNull(
            ChangePlanIdentity.parse(changeHandle("plan", listOf(plan.planId.value))),
        )
        val prior = plans.putIfAbsent(handle, plan)
        return if (prior == null || prior.planId == plan.planId) {
            ChangePlanIssuance.Issued(handle)
        } else {
            ChangePlanIssuance.Rejected(DurableChangeAuthorityFailure.IDENTITY_COLLISION)
        }
    }

    override fun loadPlan(identity: ChangePlanIdentity): ChangePlanLookup =
        plans[identity]?.let(ChangePlanLookup::Found) ?: ChangePlanLookup.Missing

    override fun issueApplication(
        plan: ChangePlan,
        application: AppliedUnverified,
    ): ChangeApplicationIssuance {
        val handle = requireNotNull(
            ChangeApplicationIdentity.parse(
                changeHandle(
                    "application",
                    listOf(plan.planId.value, application.postimage.value),
                ),
            ),
        )
        val pending = PendingChangeVerification(plan, application)
        val prior = applications.putIfAbsent(handle, pending)
        return if (
            prior == null ||
            prior.plan.planId == plan.planId &&
            prior.application.postimage == application.postimage
        ) {
            ChangeApplicationIssuance.Issued(handle)
        } else {
            ChangeApplicationIssuance.Rejected(DurableChangeAuthorityFailure.IDENTITY_COLLISION)
        }
    }

    override fun loadApplication(identity: ChangeApplicationIdentity): ChangeApplicationLookup =
        applications[identity]
            ?.let(ChangeApplicationLookup::Found)
        ?: ChangeApplicationLookup.Missing

    override fun issueReceipt(receipt: VerifiedReceipt): ChangeReceiptIssuance {
        val handle = requireNotNull(
            ChangeReceiptIdentity.parse(
                changeHandle(
                    "receipt",
                    listOf(
                        receipt.planId.value,
                        receipt.resultingWorkspace.generation.value.toString(),
                    ),
                ),
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
            ChangeReceiptIssuance.Rejected(DurableChangeAuthorityFailure.IDENTITY_COLLISION)
        }
    }
}

private fun changeHandle(
    prefix: String,
    fields: List<String>,
): String {
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
    return "$prefix:$digest"
}
