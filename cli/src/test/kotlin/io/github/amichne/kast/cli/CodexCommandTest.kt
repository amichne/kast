package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.host.CodexClientLaunch
import io.github.amichne.kast.appserver.host.CodexClientLaunchRun
import io.github.amichne.kast.appserver.host.CodexClientLauncher
import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.AppServerManager
import kotlinx.serialization.json.put
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CodexCommandTest {
    @Test
    fun `codex commands select only the installed client launcher`() {
        val launched = mutableListOf<CodexClientLaunch>()
        val cli = testCli(
            CodexClientLauncher { client ->
                launched += client
                CodexClientLaunchRun.Completed(
                    if (client == CodexClientLaunch.Cli) 17 else 0,
                )
            },
        )

        val cliExit = cli.execute(listOf("codex"), Path.of("/missing"))
        assertEquals(CliExit.Delegated(17), cliExit, cliExit.document.value)
        assertEquals(
            CliExit.Delegated(0),
            cli.execute(listOf("codex", "desktop"), Path.of("/missing")),
        )
        assertEquals(listOf(CodexClientLaunch.Cli, CodexClientLaunch.Desktop), launched)
    }

    @Test
    fun `root help presents both Codex host projections`() {
        val help = testCli(CodexClientLauncher { error("help must be passive") })
            .execute(listOf("--help"), Path.of("/missing")) as CliExit.Complete

        assertTrue(help.document.value.contains("codex"))
        val nested = testCli(CodexClientLauncher { error("help must be passive") })
            .execute(listOf("codex", "--help"), Path.of("/missing")) as CliExit.Complete
        assertTrue(nested.document.value.contains("desktop"))
        assertTrue(nested.document.value.contains("state/broker/<installation-id>/service.log"))
        assertTrue(nested.document.value.contains("state/broker/<installation-id>/launch-environment"))
        assertTrue(nested.document.value.contains("KAST_DEBUG=1"))

        val appServer = testCli(CodexClientLauncher { error("help must be passive") })
            .execute(listOf("app-server", "--help"), Path.of("/missing")) as CliExit.Complete
        assertTrue(appServer.document.value.contains("state/broker/<installation-id>/service.log"))
        assertTrue(appServer.document.value.contains("state/broker/<installation-id>/launch-environment"))
        assertTrue(appServer.document.value.contains("KAST_DEBUG=1"))
    }

    @Test
    fun `destructive App Server recovery requires explicit confirmation`() {
        val actions = mutableListOf<AppServerAction>()
        val manager = AppServerManager { action, _ ->
            actions.add(action)
            AppServerManagementResult.Completed(kotlinx.serialization.json.buildJsonObject {
                put("status", "ready")
            })
        }
        val cli = testCli(
            CodexClientLauncher { error("repair must not launch Codex") },
            manager,
        )

        assertTrue(cli.execute(listOf("app-server", "repair"), Path.of("/missing")) is CliExit.BoundaryRejected)
        assertEquals(emptyList<AppServerAction>(), actions)
        assertTrue(cli.execute(listOf("app-server", "repair", "--destructive"), Path.of("/missing")) is CliExit.Complete)
        assertEquals(listOf(AppServerAction.Repair), actions)
    }

    private fun testCli(
        launcher: CodexClientLauncher,
        appServerManager: AppServerManager = io.github.amichne.kast.appserver.UnavailableAppServerManager,
    ): KastCli = KastCli(
        commandGraphFactory = commandGraphFactory(),
        rootDiscovery = CanonicalRootDiscoverer { error("Codex launch must not discover a root") },
        endpointLocator = RuntimeEndpointLocator { error("Codex launch must not locate a sidecar") },
        runtimeDemander = object : RootRuntimeDemander {
            override fun demand(
                root: CanonicalRoot,
                demand: HostedRuntimeDemand,
                startup: RuntimeStartupRequest,
            ): RuntimeAdmission = error("Codex launch must not demand a sidecar")
        },
        wireClient = WireClient { _, _ -> error("Codex launch must not use semantic wire") },
        localMetadata = when (
            val admitted = CliLocalMetadata.admit("1.2.3", "{\"schemaVersion\":1}")
        ) {
            is CliLocalMetadataAdmission.Admitted -> admitted.metadata
            is CliLocalMetadataAdmission.Rejected -> error(admitted.failure)
        },
        lifecycle = ExactRootRuntimeLifecycle(),
        productInspector = ProductInspector { error("Codex launch must not inspect product") },
        appServerManager = appServerManager,
        codexClientLauncher = launcher,
    )

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(construction.failures)
    }
}
