package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerManagementFailure
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.AppServerManager
import io.github.amichne.kast.appserver.DaemonManagementFailure
import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.appserver.UnavailableAppServerManager
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
        for (arguments in
            listOf(emptyList(), listOf("repair"), listOf("enable", "workspace"), listOf("app-server", "enable"))) {
            assertEquals(ServiceControlSelection.Rejected, selectServiceControl(arguments))
        }
        assertEquals(
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(AppServerManagementFailure.SERVICE_UNAVAILABLE, null)
            ),
            executeServiceControl(ServiceControlAction.ENABLE, UnavailableAppServerManager, Path.of("/unobserved")),
        )
        val observedActions = mutableListOf<AppServerAction>()
        val manager = AppServerManager { action, _ ->
            observedActions += action
            AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_UNAVAILABLE)
        }
        assertEquals(
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(AppServerManagementFailure.SERVICE_UNAVAILABLE, null)
            ),
            executeServiceControl(ServiceControlAction.REPAIR, manager, Path.of("/unobserved")),
        )
        assertEquals(listOf(AppServerAction.Repair), observedActions)
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
