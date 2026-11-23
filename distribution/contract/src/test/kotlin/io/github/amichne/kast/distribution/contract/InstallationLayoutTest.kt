package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pins every path the layout produces for a fixed root; the expected strings are written independently. */
class InstallationLayoutTest {
    private val management = Path.of("/fixed/home/.local/share/kast")
    private val layout = InstallationLayout.beneath(management)

    @Test
    fun `every installation path is byte-identical to the current layout`() {
        val expected =
            mapOf(
                "root" to "/fixed/home/.local/share/kast/installation",
                "managementRoot" to "/fixed/home/.local/share/kast",
                "configuration.directory" to "/fixed/home/.local/share/kast/installation/config",
                "configuration.environment" to "/fixed/home/.local/share/kast/installation/config/environment",
                "configuration.workspaceRegistry" to
                    "/fixed/home/.local/share/kast/installation/config/workspaces.json",
                "configuration.selectedIde" to "/fixed/home/.local/share/kast/installation/config/selected-ide.json",
                "configuration.workspaceEnvironment" to
                    "/fixed/home/.local/share/kast/installation/config/workspaces/" +
                    "4cb12ade88e2dd25945c62efb00e532e06dc3c52629ba2ee2719daab2727bc15/environment",
                "state.directory" to "/fixed/home/.local/share/kast/installation/state",
                "state.epoch" to "/fixed/home/.local/share/kast/installation/state/epoch.json",
                "state.brokers" to "/fixed/home/.local/share/kast/installation/state/broker",
                "state.broker" to "/fixed/home/.local/share/kast/installation/state/broker/f9cc2b730341a2cf",
                "state.readiness" to
                    "/fixed/home/.local/share/kast/installation/state/broker/f9cc2b730341a2cf/service-readiness.json",
                "state.legacyWorkers" to "/fixed/home/.local/share/kast/installation/state/workers",
                "state.run.directory" to "/fixed/home/.local/share/kast/installation/state/run",
                "state.run.coordinatorSocket" to "/fixed/home/.local/share/kast/installation/state/run/c.sock",
                "state.run.upstreamSocket" to "/fixed/home/.local/share/kast/installation/state/run/u.sock",
                "state.run.oneShotInvocations" to "/fixed/home/.local/share/kast/installation/state/run/one-shot",
                "state.run.toolSessions" to "/fixed/home/.local/share/kast/installation/state/run/tool-sessions",
                "payload.manifest" to "/fixed/home/.local/share/kast/installation/installation.json",
                "payload.kast" to "/fixed/home/.local/share/kast/installation/bin/kast",
                "payload.libraries" to "/fixed/home/.local/share/kast/installation/lib",
                "payload.share" to "/fixed/home/.local/share/kast/installation/share/kast",
                "payload.productSelection" to
                    "/fixed/home/.local/share/kast/installation/share/kast/product-selection.json",
                "payload.buildRecord" to "/fixed/home/.local/share/kast/installation/share/kast/build-record.json",
                "payload.providerCatalog" to
                    "/fixed/home/.local/share/kast/installation/share/kast/provider-catalog.json",
                "payload.managementExecutable" to
                    "/fixed/home/.local/share/kast/installation/share/kast/libexec/kast-management",
                "payload.serviceExecutable" to
                    "/fixed/home/.local/share/kast/installation/share/kast/libexec/kast-service",
                "payload.daemonExecutable" to
                    "/fixed/home/.local/share/kast/installation/share/kast/libexec/kast-daemon",
                "payload.oneShotObservationCapability" to
                    "/fixed/home/.local/share/kast/installation/share/kast/one-shot-observation-v1",
                "payload.shutdownCapability" to
                    "/fixed/home/.local/share/kast/installation/share/kast/lifecycle-fence-v1",
                "payload.shareResource" to "/fixed/home/.local/share/kast/installation/share/kast/frontend.json",
                "management.root" to "/fixed/home/.local/share/kast",
                "management.receipt" to "/fixed/home/.local/share/kast/management.json",
                "management.shutdownFence" to "/fixed/home/.local/share/kast/shutdown.json",
                "management.replacementRecovery" to "/fixed/home/.local/share/kast/recovery/replacement",
                "management.workspaceStore" to "/fixed/home/.local/share/kast-workspaces",
                "management.runtimeBaseStore" to "/fixed/home/.local/share/kast-runtime-bases",
            )
        val profile = CoordinatorHostProfile.of("/fixed/home/.codex")
        val observed =
            mapOf(
                "root" to layout.root,
                "managementRoot" to layout.managementRoot,
                "configuration.directory" to layout.configuration.directory,
                "configuration.environment" to layout.configuration.environment,
                "configuration.workspaceRegistry" to layout.configuration.workspaceRegistry,
                "configuration.selectedIde" to layout.configuration.selectedIde,
                "configuration.workspaceEnvironment" to
                    layout.configuration.workspaceEnvironment(Path.of("/fixed/workspace")),
                "state.directory" to layout.state.directory,
                "state.epoch" to layout.state.epoch,
                "state.brokers" to layout.state.brokers,
                "state.broker" to layout.state.broker(profile),
                "state.readiness" to layout.state.readiness(profile),
                "state.legacyWorkers" to layout.state.legacyWorkers,
                "state.run.directory" to layout.state.run.directory,
                "state.run.coordinatorSocket" to layout.state.run.coordinatorSocket,
                "state.run.upstreamSocket" to layout.state.run.upstreamSocket,
                "state.run.oneShotInvocations" to layout.state.run.oneShotInvocations,
                "state.run.toolSessions" to layout.state.run.toolSessions,
                "payload.manifest" to layout.payload.manifest,
                "payload.kast" to layout.payload.kast,
                "payload.libraries" to layout.payload.libraries,
                "payload.share" to layout.payload.share,
                "payload.productSelection" to layout.payload.productSelection,
                "payload.buildRecord" to layout.payload.buildRecord,
                "payload.providerCatalog" to layout.payload.providerCatalog,
                "payload.managementExecutable" to layout.payload.managementExecutable,
                "payload.serviceExecutable" to layout.payload.serviceExecutable,
                "payload.daemonExecutable" to layout.payload.daemonExecutable,
                "payload.oneShotObservationCapability" to layout.payload.oneShotObservationCapability,
                "payload.shutdownCapability" to layout.payload.shutdownCapability,
                "payload.shareResource" to layout.payload.shareResource("frontend.json"),
                "management.root" to layout.management.root,
                "management.receipt" to layout.management.receipt,
                "management.shutdownFence" to layout.management.shutdownFence,
                "management.replacementRecovery" to layout.management.replacementRecovery,
                "management.workspaceStore" to layout.management.workspaceStore,
                "management.runtimeBaseStore" to layout.management.runtimeBaseStore,
            )
        assertEquals(expected, observed.mapValues { (_, path) -> path.toString() })
    }

    @Test
    fun `every manifest-relative path keeps its recorded form`() {
        val relative = InstallationLayout.Relative
        assertEquals(
            listOf(
                "installation.json",
                "bin/kast",
                "lib",
                "share/kast",
                "share/kast/product-selection.json",
                "share/kast/build-record.json",
                "share/kast/provider-catalog.json",
                "share/kast/agent-tools",
                "share/kast/libexec/kast-management",
                "share/kast/libexec/kast-service",
                "share/kast/libexec/kast-daemon",
                "share/kast/one-shot-observation-v1",
                "share/kast/lifecycle-fence-v1",
                "share/kast/reset-fence-v1",
                "share/kast/managed-install-v1",
                "share/kast/install-candidate.sh",
                "state/run/c.sock",
                "state/run/one-shot",
                "state/run/tool-sessions",
            ),
            listOf(
                    relative.MANIFEST,
                    relative.KAST_EXECUTABLE,
                    relative.LIBRARIES,
                    relative.SHARE,
                    relative.PRODUCT_SELECTION,
                    relative.BUILD_RECORD,
                    relative.PROVIDER_CATALOG,
                    relative.AGENT_TOOLS,
                    relative.MANAGEMENT_EXECUTABLE,
                    relative.SERVICE_EXECUTABLE,
                    relative.DAEMON_EXECUTABLE,
                    relative.ONE_SHOT_OBSERVATION_CAPABILITY,
                    relative.SHUTDOWN_CAPABILITY,
                    relative.RESET_CAPABILITY,
                    relative.MANAGED_INSTALL_CAPABILITY,
                    relative.INSTALL_CANDIDATE,
                    relative.COORDINATOR_SOCKET,
                    relative.ONE_SHOT_INVOCATIONS,
                    relative.TOOL_SESSIONS,
                )
                .map(InstallationRelativePath::value),
        )
        assertEquals(Path.of("share/kast/libexec/kast-management"), relative.MANAGEMENT_EXECUTABLE.asPath())
        assertEquals(INSTALLATION_SHUTDOWN_CAPABILITY, relative.SHUTDOWN_CAPABILITY.value)
        assertEquals(INSTALLATION_RESET_CAPABILITY, relative.RESET_CAPABILITY.value)
    }

    @Test
    fun `every admission form selects the same installation directory`() {
        val installation = Path.of("/fixed/home/.local/share/kast/installation")
        assertEquals(layout, InstallationLayout.of(installation))
        assertEquals(layout, InstallationLayout.ofKastExecutable(installation.resolve("bin/kast")))
        assertEquals(layout, InstallationLayout.ofConfigurationFile(installation.resolve("config/environment")))
        assertNull(InstallationLayout.ofConfigurationFile(Path.of("environment")))
    }
}
