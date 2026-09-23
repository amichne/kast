package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerManagementFailure
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.AppServerManager
import io.github.amichne.kast.appserver.DaemonManagementFailure
import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.appserver.UnavailableAppServerManager
import io.github.amichne.kast.cli.ide.BrokerTrustFailure
import io.github.amichne.kast.cli.ide.BrokerTrustResult
import io.github.amichne.kast.cli.ide.BrokerTrustStatus
import java.nio.file.Path
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastServiceMainTest {
    @Test
    fun `only exact private service actions are admitted`() {
        assertEquals(
            ServiceControlSelection.Selected(ServiceControlAction.ENABLE),
            selectServiceControl(listOf("enable")),
        )
        assertEquals(
            ServiceControlSelection.Selected(ServiceControlAction.DISABLE),
            selectServiceControl(listOf("disable")),
        )
        assertEquals(
            ServiceControlSelection.Selected(ServiceControlAction.REPAIR),
            selectServiceControl(listOf("repair", "--destructive")),
        )
        assertEquals(
            ServiceControlSelection.Selected(ServiceControlAction.STOP),
            selectServiceControl(listOf("stop")),
        )
        assertEquals(ServiceControlSelection.Trust, selectServiceControl(listOf("enroll-trust")))
        for (arguments in
            listOf(
                emptyList(),
                listOf("repair"),
                listOf("enable", "workspace"),
                listOf("app-server", "enable"),
                listOf("enroll-trust", "--force"),
            )) {
            assertEquals(ServiceControlSelection.Rejected, selectServiceControl(arguments))
        }
        assertEquals(
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(AppServerManagementFailure.SERVICE_UNAVAILABLE, null)
            ),
            executeServiceControl(ServiceControlAction.ENABLE, UnavailableAppServerManager, Path.of("/unobserved")),
        )
        val kast = Path.of("/owned/versions/v1/bin/kast")
        assertEquals(
            "/owned/versions/v1/config/environment",
            serviceControlEnvironment(kast, emptyMap()).getValue("KAST_CONFIGURATION_FILE"),
        )
        assertEquals(
            "/selected/configuration",
            serviceControlEnvironment(kast, mapOf("KAST_CONFIGURATION_FILE" to "/selected/configuration"))
                .getValue("KAST_CONFIGURATION_FILE"),
        )
    }

    @Test
    fun `private trust enrollment preserves finite success and rejection`() {
        assertEquals(
            ServiceControlOutcome.TrustCompleted(BrokerTrustStatus.PRESERVED),
            executePrivateTrust { BrokerTrustResult.Complete(BrokerTrustStatus.PRESERVED) },
        )
        val completion =
            serviceControlJson
                .encodeToJsonElement(ServiceTrustCompletionDocument(BrokerTrustStatus.PRESERVED))
                .jsonObject
        assertEquals(setOf("status", "outcome", "operation"), completion.keys)
        assertEquals("PRESERVED", completion.getValue("status").jsonPrimitive.content)
        assertEquals("complete", completion.getValue("outcome").jsonPrimitive.content)
        assertEquals("private-trust-enrollment", completion.getValue("operation").jsonPrimitive.content)
        assertEquals(
            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Trust(BrokerTrustFailure.INCOMPLETE_KEYS)),
            executePrivateTrust { BrokerTrustResult.Rejected(BrokerTrustFailure.INCOMPLETE_KEYS) },
        )
    }

    @Test
    fun `private repair and stop dispatch typed manager actions`() {
        val observedActions = mutableListOf<AppServerAction>()
        val manager = AppServerManager { action, _ ->
            observedActions += action
            AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_UNAVAILABLE)
        }
        val rejected =
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(AppServerManagementFailure.SERVICE_UNAVAILABLE, null)
            )
        assertEquals(rejected, executeServiceControl(ServiceControlAction.REPAIR, manager, Path.of("/unobserved")))
        assertEquals(rejected, executeServiceControl(ServiceControlAction.STOP, manager, Path.of("/unobserved")))
        assertEquals(listOf(AppServerAction.Repair, AppServerAction.Stop), observedActions)
    }

    @Test
    fun `private rejection shapes retain finite causes and required discriminator`() {
        val cases =
            listOf(
                ServiceControlFailureDocument.Arguments to "arguments",
                ServiceControlFailureDocument.Product(InstalledKastControlProductFailure.CODE_SOURCE_INVALID) to
                    "product",
                ServiceControlFailureDocument.Management(
                    AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN,
                    PersistentBrokerServiceFailure.READINESS_REJECTED,
                ) to "management",
                ServiceControlFailureDocument.Daemon(
                    DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED)
                ) to "daemon",
                ServiceControlFailureDocument.Trust(BrokerTrustFailure.INCOMPLETE_KEYS) to "trust",
            )
        for ((document, discriminator) in cases) {
            val encoded = serviceControlJson.encodeToJsonElement<ServiceControlFailureDocument>(document).jsonObject
            assertEquals(discriminator, encoded.getValue("type").jsonPrimitive.content)
            when (document) {
                ServiceControlFailureDocument.Arguments -> assertEquals(setOf("type"), encoded.keys)
                is ServiceControlFailureDocument.Product -> {
                    assertEquals(setOf("type", "failure"), encoded.keys)
                    assertEquals("CODE_SOURCE_INVALID", encoded.getValue("failure").jsonPrimitive.content)
                }
                is ServiceControlFailureDocument.Management -> {
                    assertEquals(setOf("type", "failure", "serviceFailure"), encoded.keys)
                    assertEquals("SERVICE_OWNERSHIP_UNPROVEN", encoded.getValue("failure").jsonPrimitive.content)
                    assertEquals("READINESS_REJECTED", encoded.getValue("serviceFailure").jsonPrimitive.content)
                }
                is ServiceControlFailureDocument.Daemon -> {
                    assertEquals(setOf("type", "reason"), encoded.keys)
                    val reason = encoded.getValue("reason").jsonObject
                    assertEquals("protocol", reason.getValue("type").jsonPrimitive.content)
                    assertEquals("RESPONSE_REJECTED", reason.getValue("failure").jsonPrimitive.content)
                }
                is ServiceControlFailureDocument.Trust -> {
                    assertEquals(setOf("type", "failure"), encoded.keys)
                    assertEquals("INCOMPLETE_KEYS", encoded.getValue("failure").jsonPrimitive.content)
                }
            }
        }
        val withoutServiceFailure =
            serviceControlJson
                .encodeToJsonElement<ServiceControlFailureDocument>(
                    ServiceControlFailureDocument.Management(AppServerManagementFailure.SERVICE_UNAVAILABLE, null)
                )
                .jsonObject
        assertEquals(JsonNull, withoutServiceFailure.getValue("serviceFailure"))
    }
}
