package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangeApplicationHistory
import io.github.amichne.kast.change.protocol.liveChangeEvidence
import io.github.amichne.kast.change.protocol.protocolPreview
import io.github.amichne.kast.change.verify.LiveChangeReceiptLookup
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult

internal sealed interface HostedApplicationHistory {
    data object Unattempted : HostedApplicationHistory

    data class Terminal(val outcome: HostedApplyOutcome) : HostedApplicationHistory
}

internal fun observeHostedApplication(
    resources: HostedChangeResources,
    plan: LiveAddDeclarationChangePlan,
): HostedApplicationHistory {
    val identity = checkNotNull(ChangePlanIdentity.parse("plan:${plan.planId.value}"))
    return observeHostedApplication(plan, resources.receipts.loadReceipt(identity)) {
        resources.plans.applicationHistory(identity)
    }
}

/** Receipt wins over attempt state; unreadable history never authorizes another write. */
internal fun observeHostedApplication(
    plan: LiveAddDeclarationChangePlan,
    receipt: LiveChangeReceiptLookup,
    history: () -> LiveChangeApplicationHistory,
): HostedApplicationHistory =
    when (receipt) {
        is LiveChangeReceiptLookup.Found ->
            HostedApplicationHistory.Terminal(
                OperationOutcome.Complete(
                    liveChangeEvidence(
                        CanonicalOperation.CHANGE_APPLY,
                        receipt.receipt.after.reference,
                        ChangeApplyResult.Verified(
                            hostedProtocolText(receipt.identity.value),
                            receipt.receipt.plan.protocolPreview(),
                        ),
                    )
                )
            )
        is LiveChangeReceiptLookup.Rejected -> interruptedApplication(plan)
        LiveChangeReceiptLookup.Missing ->
            when (history()) {
                LiveChangeApplicationHistory.NeverAttempted -> HostedApplicationHistory.Unattempted
                LiveChangeApplicationHistory.Attempted,
                is LiveChangeApplicationHistory.Rejected -> interruptedApplication(plan)
                LiveChangeApplicationHistory.Missing ->
                    HostedApplicationHistory.Terminal(OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND))
            }
    }

private fun interruptedApplication(plan: LiveAddDeclarationChangePlan) =
    HostedApplicationHistory.Terminal(recoveryRequired(plan, ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED))

/** Admission can race a durable attempt; preserve newly recorded effects before projecting failure. */
internal fun rejectionAfterHistory(
    resources: HostedChangeResources,
    plan: LiveAddDeclarationChangePlan,
    failure: ChangeApplyRejection,
): HostedApplyOutcome =
    when (val history = observeHostedApplication(resources, plan)) {
        HostedApplicationHistory.Unattempted -> OperationOutcome.Rejected(failure)
        is HostedApplicationHistory.Terminal -> history.outcome
    }
