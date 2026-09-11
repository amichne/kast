package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.contract.LiveChangeApplicationHistory
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.change.verify.LiveChangeReceiptLookup
import io.github.amichne.kast.change.verify.LiveChangeReceiptStoreFailure
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedApplicationHistoryTest {
    private val plan = HostedApprovalFixture().plan

    @Test
    fun `a newly durable attempt replaces earlier unattempted admission with recovery state`() {
        var persisted: LiveChangeApplicationHistory = LiveChangeApplicationHistory.NeverAttempted
        fun observe() = observeHostedApplication(plan, LiveChangeReceiptLookup.Missing) { persisted }
        assertEquals(HostedApplicationHistory.Unattempted, observe())
        persisted = LiveChangeApplicationHistory.Attempted
        assertInterrupted(observe())
    }

    @Test
    fun `unreadable receipt stops before attempt lookup can authorize a repeated write`() {
        val state =
            observeHostedApplication(
                plan,
                LiveChangeReceiptLookup.Rejected(LiveChangeReceiptStoreFailure.CORRUPT_RECORD),
            ) {
                error("Unreadable receipt cannot be weakened to an absent attempt")
            }
        assertInterrupted(state)
    }

    @Test
    fun `unreadable application history preserves recovery required`() {
        val state =
            observeHostedApplication(plan, LiveChangeReceiptLookup.Missing) {
                LiveChangeApplicationHistory.Rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
            }
        assertInterrupted(state)
    }

    private fun assertInterrupted(state: HostedApplicationHistory) {
        val terminal = assertInstanceOf<HostedApplicationHistory.Terminal>(state)
        val qualified = assertInstanceOf<OperationOutcome.Qualified<*, *>>(terminal.outcome)
        val result = assertInstanceOf<ChangeApplyResult.RecoveryRequired>(qualified.evidence.payload)
        assertEquals(ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED, result.reason)
    }
}
