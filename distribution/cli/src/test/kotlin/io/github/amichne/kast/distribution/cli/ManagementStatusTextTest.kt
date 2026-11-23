package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagementStatusTextTest {
    @Test
    fun `running coordinator and Host read as plain lines`() {
        val host =
            LoadedApplicationHost(
                ApplicationRuntimeIdentity("/ide", "/profile", 42, 100, "host", ApplicationHostBuild("1.2.3", "", "")),
                ApplicationHostCompatibility("", "", "1.2.3", ApplicationHostedContract("", "", "", "", emptyList())),
            )
        assertEquals(
            """
            Command: /bin/kast
            Installation: /installation
            Control installed: 1.2.3
            Coordinator: running, version 1.2.3
            Installed build: developer-v1.2.3, commit $REVISION on main
            Update policy: follows the preview channel
            Unresolved transaction: none
            One-shot requests in flight: 0
            Workspace: /workspace
            Workspace runtime: runtime
            Workspace Host: running, version 1.2.3 (PID 42)
            Workspace dependencies: SHARED; Gradle home: /gradle
            Saved dependency configuration: matches creation
            Workspace seed: no eligible donor
            """
                .trimIndent(),
            status(Observation.verified("1.2.3"), RuntimeObservationFailure.BROKER_NOT_OBSERVED)
                .copy(workspaces = Observation.verified(listOf(workspace(Observation.verified(host)))))
                .asText(),
        )
    }

    @Test
    fun `stopped coordinator and Host read as not running`() {
        val stopped = RuntimeObservationFailure.COORDINATOR_NOT_RUNNING
        val absent = Observation.unavailable<LoadedApplicationHost>(ApplicationHostFailure.DESCRIPTOR_ABSENT.name)
        val status = status(Observation.unavailable(stopped.name), stopped)
        assertEquals(
            """
            Command: /bin/kast
            Installation: /installation
            Control installed: 1.2.3
            Coordinator: not running; the next query starts it
            Installed build: developer-v1.2.3, commit $REVISION on main
            Update policy: follows the preview channel
            Unresolved transaction: none
            One-shot requests in flight: 0
            Workspace: none prepared in this scope
            """
                .trimIndent(),
            status.asText(),
        )
        val recorded = status.copy(workspaces = Observation.verified(listOf(workspace(absent)))).asText()
        assertTrue("Workspace Host: not running" in recorded.lines(), recorded)
    }

    @Test
    fun `unknown coordinator state names its reason`() {
        val expected =
            mapOf(
                RuntimeObservationFailure.COORDINATOR_STARTING to "starting",
                RuntimeObservationFailure.COORDINATOR_STARTUP_REJECTED to "not running; its last startup failed",
                RuntimeObservationFailure.EXCHANGE_UNAVAILABLE to "could not tell (no answer on its socket)",
                RuntimeObservationFailure.RESPONSE_REJECTED to "could not tell (response rejected)",
            )
        for ((failure, text) in expected) {
            val lines = status(Observation.unavailable(failure.name), failure).asText().lines()
            assertTrue("Coordinator: $text" in lines, lines.toString())
        }
    }

    @Test
    fun `broker observation keeps its workspaces and connections`() {
        val lines =
            status(Observation.verified("1.2.3"), RuntimeObservationFailure.BROKER_NOT_OBSERVED)
                .copy(
                    activeWorkspaces = Observation.verified(listOf("/one", "/two")),
                    liveConnections = Observation.verified(2),
                )
                .asText()
                .lines()
        assertTrue("Active workspaces: /one, /two" in lines, lines.toString())
        assertTrue("Live connections: 2" in lines, lines.toString())
    }

    private fun status(loaded: Observation<String>, failure: RuntimeObservationFailure) =
        InstallationStatus(
            commandPath = "/bin/kast",
            resolvedInstallationPath = Observation.verified("/installation"),
            installedVersion = Observation.verified("1.2.3"),
            loadedVersion = loaded,
            activeWorkspaces = Observation.unavailable(failure.name),
            liveConnections = Observation.unavailable(failure.name),
            oneShotRequestsInFlight = Observation.verified(0),
            hostedServices = Observation.unavailable<List<HostedServiceStatus>>(failure.name),
            installedBuild =
                Observation.verified(
                    InstalledBuildIdentity("developer-v1.2.3", "1.2.3", REVISION, "b".repeat(40), REVISION)
                ),
            updatePolicy = Observation.verified(UpdatePolicy.TrackMain),
            unresolvedTransaction = Observation.verified(PendingInstallationRecords(emptyList())),
            workspaces = Observation.unavailable(ContainerFailure.NOT_FOUND.name),
        )

    private fun workspace(host: Observation<LoadedApplicationHost>) =
        ManagedWorkspaceStatus.Recorded(
            id = "id",
            workspace = "/workspace",
            runtimeArchiveSha256 = Observation.verified("runtime"),
            host = host,
            dependencySharing = WorkspaceDependencySharing.SHARED,
            gradleHome = "/gradle",
            savedDependencyConfiguration = Observation.verified(WorkspaceDependencyMatch.MATCHES_CREATION),
            seed = Observation.verified(WorkspaceSeedRecord(WorkspaceSeedResult.NoEligibleDonor)),
            hostStages = PassiveHostStages.at(PassiveHostStep.COMPLETE),
        )
}

private val REVISION = "a".repeat(40)
