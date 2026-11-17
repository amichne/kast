package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoordinatorRuntimeSettingsTest {
    @Test
    fun `a conflicting heap is rejected before the coordinator calls its executor`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                Files.createFile(root.resolve("settings.gradle.kts"))
                val workspace =
                    (FilesystemCanonicalRootDiscovery.discover(root) as CanonicalRootDiscovery.Discovered).root
                val launch = mapOf(ConfigurationParameter.HOST_MAX_HEAP_MIB.key to "2048")
                val options = (InstalledCoordinatorConfiguration.admit(kast, root, launch) as Refinement.Refined).value
                val requests = AtomicInteger()
                val accepted = JsonPrimitive("executed")
                val started =
                    InstalledCoordinator.start(
                        options,
                        semantic = { _, _ ->
                            requests.incrementAndGet()
                            DaemonOperationResult.Complete(
                                CanonicalJsonDocument.generated(JsonElement.serializer()).create(accepted)
                            )
                        },
                    ) as InstalledCoordinatorStart.Started
                try {
                    val status = (readCoordinatorStatus(options.socket.path) as Refinement.Refined).value
                    val client =
                        InstalledDaemonOperationClient(options.socket.path, DaemonManagementTarget.from(status))
                    val call = admittedRead()
                    val explicit =
                        RequestedRuntimeSettings.fromEnvironment(
                            mapOf(ConfigurationParameter.HOST_MAX_HEAP_MIB.key to "1024")
                        )
                    assertEquals(heapConflict(), client.exchange(workspace, call, explicit))
                    assertEquals(0, requests.get())
                    val result = client.exchange(workspace, call, RequestedRuntimeSettings.fromEnvironment(launch))
                    assertEquals(accepted.toString(), (result as DaemonOperationResult.Complete).document.value)
                    assertEquals(1, requests.get())
                } finally {
                    started.coordinator.close()
                }
            }
        }

    @Test
    fun `runtime settings projection excludes request presentation and secrets`() {
        val environment =
            mapOf(
                ConfigurationParameter.OUTPUT_TOON.key to "1",
                ConfigurationParameter.JVM_OPTIONS.key to "-Dsecret=value",
                ConfigurationParameter.GRADLE_USER_HOME.key to "/owned/gradle",
            )
        val selected = RequestedRuntimeSettings.fromEnvironment(environment)
        assertEquals(
            listOf(DaemonRuntimeAssignment(ConfigurationParameter.GRADLE_USER_HOME.key, "/owned/gradle")),
            selected.wire.assignments,
        )
        val admitted = AdmittedRuntimeSettings.admit(selected.wire) as Refinement.Refined
        assertEquals(setOf(ConfigurationParameter.GRADLE_USER_HOME), admitted.value.parameters)
    }

    @Test
    fun `delegated values survive an admitted request to a service started without them`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                Files.createFile(root.resolve("settings.gradle.kts"))
                val workspace =
                    (FilesystemCanonicalRootDiscovery.discover(root) as CanonicalRootDiscovery.Discovered).root
                val options =
                    (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) as Refinement.Refined).value
                val names = ConfigurationParameter.GRADLE_IMPORT_VARIABLES.key
                val marker = "selected-gradle-value"
                val expected = mapOf(names to "REPO_TOKEN", "REPO_TOKEN" to marker)
                val settings = RequestedRuntimeSettings.fromEnvironment(expected + ("UNSELECTED" to "omit"))
                val accepted = JsonPrimitive("executed")
                val started =
                    InstalledCoordinator.start(
                        options,
                        requests = { request ->
                            assertEquals(expected, request.settings.environmentOver(emptyMap()))
                            assertFalse(request.settings.toString().contains(marker))
                            DaemonOperationResult.Complete(
                                CanonicalJsonDocument.generated(JsonElement.serializer()).create(accepted)
                            )
                        },
                    ) as InstalledCoordinatorStart.Started
                try {
                    val status = (readCoordinatorStatus(options.socket.path) as Refinement.Refined).value
                    val client =
                        InstalledDaemonOperationClient(options.socket.path, DaemonManagementTarget.from(status))
                    assertFalse(settings.toString().contains(marker))
                    val result = client.exchange(workspace, admittedRead(), settings)
                    assertEquals(accepted.toString(), (result as DaemonOperationResult.Complete).document.value)
                } finally {
                    started.coordinator.close()
                }
            }
        }

    @Test
    fun `the request selector replaces the service selector and an omitted selector uses the default`(
        @TempDir directory: Path
    ) {
        val key = ConfigurationParameter.CONFIGURATION_FILE.key
        val service = mapOf(key to directory.resolve("service").toString())
        val caller = mapOf(key to directory.resolve("caller").toString())
        val requested = RequestedRuntimeSettings.fromEnvironment(caller)
        val admitted = AdmittedRuntimeSettings.admit(requested.wire) as Refinement.Refined
        assertEquals(caller, admitted.value.environmentOver(service))
        val default = AdmittedRuntimeSettings.admit(RequestedRuntimeSettings.Unstated.wire) as Refinement.Refined
        assertEquals(emptyMap<String, String>(), default.value.environmentOver(service))
    }

    @Test
    fun `saved workspace selection delegates only its inputs and preserves precedence`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        val installation = Files.createDirectory(root.resolve("installation"))
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val names = ConfigurationParameter.GRADLE_IMPORT_VARIABLES.key
        val selector = ConfigurationParameter.CONFIGURATION_FILE.key
        val file = Files.writeString(root.resolve("selected"), "$names=INSTALLATION_TOKEN\n")
        val workspaceId =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray()))
        val overlay = Files.createDirectories(installation.resolve("config/workspaces/$workspaceId"))
        Files.writeString(overlay.resolve("environment"), "$names=WORKSPACE_TOKEN\n")
        val caller =
            mapOf(
                selector to file.toString(),
                "INSTALLATION_TOKEN" to "installation-value",
                "WORKSPACE_TOKEN" to "workspace-value",
                "ENV_TOKEN" to "explicit-value",
            )

        val saved = RequestedRuntimeSettings.fromWorkspace(installation, workspace, caller)
        assertEquals(listOf(DaemonDelegatedVariable("WORKSPACE_TOKEN", "workspace-value")), saved.wire.delegated)
        assertFalse(saved.wire.assignments.any { it.key == names })
        val admitted = AdmittedRuntimeSettings.admit(saved.wire) as Refinement.Refined
        assertEquals(
            mapOf(selector to file.toString(), "WORKSPACE_TOKEN" to "workspace-value"),
            admitted.value.environmentOver(emptyMap()),
        )
        val explicit = RequestedRuntimeSettings.fromWorkspace(installation, workspace, caller + (names to "ENV_TOKEN"))
        assertEquals(listOf(DaemonDelegatedVariable("ENV_TOKEN", "explicit-value")), explicit.wire.delegated)
    }

    private fun admittedRead(): DaemonOperationCall {
        val tool = PublicToolIdentity.QUERY_SYMBOLS
        val example = PublicToolContract.examples(tool).examples.getValue("runByName").value
        return DaemonOperationCall.PublicTool((PublicToolContract.admit(tool, example) as Refinement.Refined).value)
    }

    private fun heapConflict(): DaemonOperationResult =
        DaemonOperationResult.Rejected(
            DaemonOperationClientRejection.Server(
                DaemonOperationFailure.Input(
                    DaemonOperationInputFailure.RuntimeSettings(
                        DaemonRuntimeSettingsFailure.Conflict(
                            listOf(
                                DaemonRuntimeSettingConflict(
                                    ConfigurationParameter.HOST_MAX_HEAP_MIB,
                                    RuntimeSettingStatement.STATED,
                                    RuntimeSettingStatement.STATED,
                                )
                            )
                        )
                    )
                )
            )
        )
}
