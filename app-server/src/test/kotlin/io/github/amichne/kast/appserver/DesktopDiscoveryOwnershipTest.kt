package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopDiscoveryOwnershipTest {
    @Test
    fun `owner admission retains the refined identity and original setting`() {
        val identity = identity('a')
        for (previous in PriorDesktopDaemonSetting.entries) {
            val record =
                DesktopDiscoveryRecord(identity.value, previous, DesktopDiscoveryRecordType.DESKTOP_DAEMON_DISCOVERY)
            val ownership = (DesktopDiscoveryOwnership.admit(record, identity) as Refinement.Refined).value
            assertEquals(identity, ownership.identity)
            assertEquals(previous, ownership.previous)
        }
    }

    @Test
    fun `valid foreign identity remains distinct from malformed identity`() {
        val expected = identity('a')
        val foreign =
            DesktopDiscoveryRecord(
                identity('b').value,
                PriorDesktopDaemonSetting.ABSENT,
                DesktopDiscoveryRecordType.DESKTOP_DAEMON_DISCOVERY,
            )
        assertEquals(
            Refinement.Rejected(DesktopDiscoveryFailure.OWNER_MISMATCH),
            DesktopDiscoveryOwnership.admit(foreign, expected),
        )
        for (invalid in listOf("", "sha256:a", "not-identity")) {
            val record = foreign.copy(serviceIdentity = invalid)
            assertEquals(
                Refinement.Rejected(DesktopDiscoveryFailure.RECORD_MALFORMED),
                DesktopDiscoveryOwnership.admit(record, expected),
            )
        }
    }

    private fun identity(digit: Char): BrokerServiceIdentity =
        checkNotNull(BrokerServiceIdentity.admit("sha256:" + digit.toString().repeat(64)))
}
