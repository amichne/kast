package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AppServerManagementFailure
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.BrokerServerFailure
import io.github.amichne.kast.appserver.BrokerServerRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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

    @Test
    fun `login admission precedes daemon execution and retains rejection`() {
        val managed = mapOf("BROKER_SERVICE_IDENTITY" to "identity", "BROKER_READINESS_FILE" to "/owned/readiness")
        val rejected = AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
        assertEquals(
            DaemonEntryOutcome.LoginRejected(rejected),
            runManagedDaemonBootstrap(listOf("--login"), managed, { error("Must not start daemon") }) { rejected },
        )
        assertEquals(
            DaemonEntryOutcome.Ran(BrokerServerRun.Rejected(BrokerServerFailure.READINESS_REJECTED)),
            runManagedDaemonBootstrap(listOf("--login"), emptyMap(), { error("Must not start daemon") }) {
                error("Must not clear stop")
            },
        )
        var calls = 0
        assertEquals(
            DaemonEntryOutcome.Ran(BrokerServerRun.Stopped),
            runManagedDaemonBootstrap(
                listOf("--login"),
                managed,
                {
                    calls++
                    BrokerServerRun.Stopped
                },
            ) {
                AppServerManagementResult.Completed(
                    Json { encodeDefaults = true }.encodeToJsonElement(LoginCompletion()).jsonObject
                )
            },
        )
        assertEquals(1, calls)
        assertEquals(
            DaemonEntryOutcome.Ran(BrokerServerRun.Rejected(BrokerServerFailure.ARGUMENTS_REJECTED)),
            runManagedDaemonBootstrap(listOf("serve"), managed, { error("Must not start daemon") }) {
                error("Must not admit login")
            },
        )
    }
}

@Serializable private data class LoginCompletion(val status: String = "ready")
