package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins every path the layout produces for a fixed root. The expected strings are written independently of the layout;
 * the digests were computed with `shasum -a 256` over the workspace, Codex home, and management root paths.
 */
class InstallationLayoutTest {
    private val management = Path.of("/fixed/home/.local/share/kast")
    private val layout = InstallationLayout.beneath(management)
    private val installation = "/fixed/home/.local/share/kast/installation"
    private val profile = CoordinatorHostProfile.of("/fixed/home/.codex")

    @Test
    fun `the installation and configuration paths are byte-identical to the current layout`() {
        assertPaths(
            "version" to ("$installation" to layout.version.path),
            "managementRoot" to ("/fixed/home/.local/share/kast" to layout.managementRoot.path),
            "configuration.directory" to ("$installation/config" to layout.configuration.directory),
            "configuration.environment" to ("$installation/config/environment" to layout.configuration.environment),
            "configuration.workspaceRegistry" to
                ("$installation/config/workspaces.json" to layout.configuration.workspaceRegistry),
            "configuration.selectedIde" to
                ("$installation/config/selected-ide.json" to layout.configuration.selectedIde),
            "configuration.workspaceEnvironment" to
                ("$installation/config/workspaces/" +
                    "4cb12ade88e2dd25945c62efb00e532e06dc3c52629ba2ee2719daab2727bc15/environment" to
                    layout.configuration.workspaceEnvironment(Path.of("/fixed/workspace"))),
        )
    }

    @Test
    fun `the state paths are byte-identical to the current layout`() {
        assertPaths(
            "state.directory" to ("$installation/state" to layout.state.directory),
            "state.epoch" to ("$installation/state/epoch.json" to layout.state.epoch),
            "state.brokers" to ("$installation/state/broker" to layout.state.brokers),
            "state.broker" to ("$installation/state/broker/f9cc2b730341a2cf" to layout.state.broker(profile)),
            "state.readiness" to
                ("$installation/state/broker/f9cc2b730341a2cf/service-readiness.json" to
                    layout.state.readiness(profile)),
            "state.legacyWorkers" to ("$installation/state/workers" to layout.state.legacyWorkers),
            "state.run.directory" to ("$installation/state/run" to layout.state.run.directory),
            "state.run.coordinatorSocket" to ("$installation/state/run/c.sock" to layout.state.run.coordinatorSocket),
            "state.run.upstreamSocket" to ("$installation/state/run/u.sock" to layout.state.run.upstreamSocket),
            "state.run.oneShotInvocations" to
                ("$installation/state/run/one-shot" to layout.state.run.oneShotInvocations),
            "state.run.toolSessions" to ("$installation/state/run/tool-sessions" to layout.state.run.toolSessions),
        )
    }

    @Test
    fun `the payload paths are byte-identical to the current layout`() {
        val payload = layout.payload
        val share = "$installation/share/kast"
        assertPaths(
            "payload.manifest" to ("$installation/installation.json" to payload.manifest),
            "payload.kast" to ("$installation/bin/kast" to payload.kast),
            "payload.libraries" to ("$installation/lib" to payload.libraries),
            "payload.share" to (share to payload.share),
            "payload.productSelection" to ("$share/product-selection.json" to payload.productSelection),
            "payload.buildRecord" to ("$share/build-record.json" to payload.buildRecord),
            "payload.providerCatalog" to ("$share/provider-catalog.json" to payload.providerCatalog),
            "payload.managementExecutable" to ("$share/libexec/kast-management" to payload.managementExecutable),
            "payload.serviceExecutable" to ("$share/libexec/kast-service" to payload.serviceExecutable),
            "payload.daemonExecutable" to ("$share/libexec/kast-daemon" to payload.daemonExecutable),
            "payload.oneShotObservationCapability" to
                ("$share/one-shot-observation-v1" to payload.oneShotObservationCapability),
            "payload.shutdownCapability" to ("$share/lifecycle-fence-v1" to payload.shutdownCapability),
            "payload.shareResource" to ("$share/frontend.json" to payload.shareResource("frontend.json")),
        )
    }

    @Test
    fun `the management-root paths are byte-identical to the current layout`() {
        val root = layout.management
        assertPaths(
            "management.root" to ("/fixed/home/.local/share/kast" to root.root),
            "management.receipt" to ("/fixed/home/.local/share/kast/management.json" to root.receipt),
            "management.shutdownFence" to ("/fixed/home/.local/share/kast/shutdown.json" to root.shutdownFence),
            "management.resetFence" to
                ("/fixed/home/.local/share/.kast-reset-62a441079c45af375afc90fb241d7793.json" to root.resetFence),
            "management.registrationLock" to ("/fixed/home/.local/share/kast/management.lock" to root.registrationLock),
            "management.activationLock" to ("/fixed/home/.local/share/kast/activation.lock" to root.activationLock),
            "management.recovery" to ("/fixed/home/.local/share/kast/recovery" to root.recovery),
            "management.replacementRecovery" to
                ("/fixed/home/.local/share/kast/recovery/replacement" to root.replacementRecovery),
            "management.installationRecovery" to
                ("/fixed/home/.local/share/kast/recovery/installation" to root.installationRecovery),
            "management.productRemoval" to
                ("/fixed/home/.local/share/kast/recovery/installation/product-removal" to root.productRemoval),
            "management.workspaceStore" to ("/fixed/home/.local/share/kast-workspaces" to root.workspaceStore),
            "management.runtimeBaseStore" to ("/fixed/home/.local/share/kast-runtime-bases" to root.runtimeBaseStore),
        )
    }

    @Test
    fun `every manifest-relative path keeps its recorded form`() {
        val relative = InstallationLayout.Relative
        val expected =
            linkedMapOf(
                "installation.json" to relative.MANIFEST,
                "bin/kast" to relative.KAST_EXECUTABLE,
                "bin/kast-query" to relative.QUERY_EXECUTABLE,
                "bin/kast-query-complete" to relative.QUERY_COMPLETE_EXECUTABLE,
                "lib" to relative.LIBRARIES,
                "lib/kast-cli.jar" to relative.library("kast-cli.jar"),
                "share/kast" to relative.SHARE,
                "share/kast/product-selection.json" to relative.PRODUCT_SELECTION,
                "share/kast/build-record.json" to relative.BUILD_RECORD,
                "share/kast/provider-catalog.json" to relative.PROVIDER_CATALOG,
                "share/kast/agent-tools" to relative.AGENT_TOOLS,
                "share/kast/agent-tools/plugins/kast/skills/kast" to relative.CLI_SKILL,
                "share/kast/libexec/kast-management" to relative.MANAGEMENT_EXECUTABLE,
                "share/kast/libexec/kast-service" to relative.SERVICE_EXECUTABLE,
                "share/kast/libexec/kast-daemon" to relative.DAEMON_EXECUTABLE,
                "share/kast/one-shot-observation-v1" to relative.ONE_SHOT_OBSERVATION_CAPABILITY,
                "share/kast/lifecycle-fence-v1" to relative.SHUTDOWN_CAPABILITY,
                "share/kast/reset-fence-v1" to relative.RESET_CAPABILITY,
                "share/kast/managed-install-v1" to relative.MANAGED_INSTALL_CAPABILITY,
                "share/kast/install-candidate.sh" to relative.INSTALL_CANDIDATE,
                "share/kast/private-python/bin/python3.12" to relative.PRIVATE_PYTHON_INTERPRETER,
                "state/run/c.sock" to relative.COORDINATOR_SOCKET,
                "state/run/one-shot" to relative.ONE_SHOT_INVOCATIONS,
                "state/run/tool-sessions" to relative.TOOL_SESSIONS,
            )
        assertEquals(expected.keys.toList(), expected.values.map(InstallationRelativePath::value))
    }

    @Test
    fun `relative paths resolve to the same absolute payload paths and keep the capability constants`() {
        assertEquals(
            Path.of("share/kast/libexec/kast-management"),
            InstallationLayout.Relative.MANAGEMENT_EXECUTABLE.asPath(),
        )
        assertEquals(
            "$installation/share/kast/libexec/kast-service",
            InstallationLayout.Relative.SERVICE_EXECUTABLE.under(layout.version.path).toString(),
        )
        assertEquals("share/kast/lifecycle-fence-v1", INSTALLATION_SHUTDOWN_CAPABILITY)
        assertEquals("share/kast/reset-fence-v1", INSTALLATION_RESET_CAPABILITY)
    }

    @Test
    fun `every admission form selects the same installation directory`() {
        val root = Path.of(installation)
        assertEquals(layout, InstallationLayout.of(root))
        assertEquals(layout, InstallationLayout.ofKastExecutable(root.resolve("bin/kast")))
        assertEquals(layout, InstallationLayout.ofConfigurationFile(root.resolve("config/environment")))
        assertNull(InstallationLayout.ofConfigurationFile(Path.of("environment")))
    }

    private fun assertPaths(vararg cases: Pair<String, Pair<String, Path>>) {
        assertEquals(
            cases.associate { (name, case) -> name to case.first },
            cases.associate { (name, case) -> name to case.second.toString() },
        )
    }
}
