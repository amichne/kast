package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerFailure
import io.github.amichne.kast.appserver.BrokerServerRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KastDaemonBootstrapTest {
    @Test
    fun `private ingress rejects commands and incomplete managed environment before coordinator admission`() {
        val managed = mapOf("BROKER_SERVICE_IDENTITY" to "identity", "BROKER_READINESS_FILE" to "/owned/readiness")
        assertEquals(
            BrokerServerRun.Rejected(BrokerServerFailure.ARGUMENTS_REJECTED),
            runDaemonBootstrap(listOf("serve"), managed) { error("Must not admit coordinator") },
        )
        for (environment in
            listOf(emptyMap(), managed - "BROKER_SERVICE_IDENTITY", managed - "BROKER_READINESS_FILE")) {
            assertEquals(
                BrokerServerRun.Rejected(BrokerServerFailure.READINESS_REJECTED),
                runDaemonBootstrap(emptyList(), environment) { error("Must not admit coordinator") },
            )
        }
        assertEquals(
            BrokerServerRun.Rejected(BrokerServerFailure.CONFIGURATION_REJECTED),
            runDaemonBootstrap(emptyList(), managed + ("KAST_SAVED_CONFIGURATION_FAILURE" to "rejected")) {
                error("Must not admit coordinator")
            },
        )
    }

    @Test
    fun `managed ingress delegates once and preserves coordinator rejection`() {
        val expected = BrokerServerRun.Rejected(BrokerServerFailure.READINESS_REJECTED)
        var calls = 0
        assertSame(
            expected,
            runDaemonBootstrap(
                emptyList(),
                mapOf("BROKER_SERVICE_IDENTITY" to "unproven", "BROKER_READINESS_FILE" to "/owned/readiness"),
            ) {
                calls++
                expected
            },
        )
        assertEquals(1, calls)
    }
}
