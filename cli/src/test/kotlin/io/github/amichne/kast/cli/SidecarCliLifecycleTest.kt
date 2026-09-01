package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarCliLifecycleTest {
    @Test
    fun `status observes endpoint and cache without demanding a runtime`(
        @TempDir temporary: Path,
    ) {
        val fixture = fixture(temporary)
        var runtimeDemands = 0
        val cli = fixture.cli(
            runtimeDemander = object : RootRuntimeDemander {
                override fun demand(
                    root: CanonicalRoot,
                    demand: HostedRuntimeDemand,
                    startup: RuntimeStartupRequest,
                ): RuntimeAdmission {
                    runtimeDemands += 1
                    error("status must not demand a runtime")
                }
            },
            lifecycle = observedLifecycle(RuntimeLifecycleState.STOPPED),
            cacheLifecycle = object : RootSidecarCacheLifecycle {
                override fun observe(root: Path): RootSidecarCacheObservation =
                    RootSidecarCacheObservation.Observed(
                        RootSidecarCacheStatus(
                            cacheIdentity = "cache-key",
                            state = KastCacheState.SMART,
                            ideaHome = temporary.resolve("IntelliJ IDEA.app"),
                            ideaBuild = "262.9437.185",
                            kotlinPluginBuild = "262.9437.185-IJ",
                            jbrIdentity = "25.0.3+9-b508.16-aarch64",
                            kastPayloadDigest = "sha256:${"a".repeat(64)}",
                        ),
                    )

                override fun quarantine(root: Path): RootSidecarCacheQuarantine =
                    error("status must not quarantine a cache")
            },
        )

        val exit = cli.execute(listOf("status"), fixture.root.path)

        assertEquals(0, runtimeDemands)
        assertTrue(exit is CliExit.Complete)
        assertEquals(
            "{\"command\":\"status\",\"status\":\"complete\",\"runtime\":\"stopped\"," +
                "\"root\":\"${fixture.root.path}\",\"runtimeId\":\"${fixture.runtimeId.value}\"," +
                "\"removed\":[],\"cache\":{\"state\":\"smart\"," +
                "\"identity\":\"cache-key\",\"ideaHome\":\"${temporary.resolve("IntelliJ IDEA.app")}\"," +
                "\"ideaBuild\":\"262.9437.185\",\"kotlinPluginBuild\":\"262.9437.185-IJ\"," +
                "\"jbrIdentity\":\"25.0.3+9-b508.16-aarch64\"," +
                "\"kastPayloadDigest\":\"sha256:${"a".repeat(64)}\"}}",
            exit.document.value,
        )
    }

    @Test
    fun `reindex stops then quarantines private cache before fresh runtime demand`(
        @TempDir temporary: Path,
    ) {
        val fixture = fixture(temporary)
        val events = mutableListOf<String>()
        val restart = RuntimeStartupRequest.Requested(
            StartupIdeHome.Explicit(temporary.resolve("IntelliJ IDEA.app")),
            StartupCacheIntent.ReuseOrFresh,
        )
        val cli = fixture.cli(
            runtimeDemander = object : RootRuntimeDemander {
                override fun demand(
                    root: CanonicalRoot,
                    demand: HostedRuntimeDemand,
                    startup: RuntimeStartupRequest,
                ): RuntimeAdmission {
                    events += "demand"
                    assertEquals(restart, startup)
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
                }
            },
            lifecycle = object : RuntimeLifecycleController {
                override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult =
                    error("reindex must not query status")

                override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult {
                    events += "stop"
                    return RuntimeStopResult.Stopped()
                }

                override fun clean(endpoint: RuntimeEndpoint): RuntimeCleanResult {
                    events += "clean"
                    error("reindex must not delete endpoint or cache state")
                }
            },
            cacheLifecycle = object : RootSidecarCacheLifecycle {
                override fun observe(root: Path): RootSidecarCacheObservation =
                    error("reindex must not use status observation")

                override fun quarantine(root: Path): RootSidecarCacheQuarantine {
                    events += "quarantine"
                    return RootSidecarCacheQuarantine.Quarantined(
                        temporary.resolve("quarantine/cache-key"),
                        restart,
                    )
                }
            },
        )

        val exit = cli.execute(listOf("reindex"), fixture.root.path)

        assertEquals(listOf("stop", "quarantine", "demand"), events)
        assertTrue(exit is CliExit.BoundaryRejected)
        val rejected = exit as CliExit.BoundaryRejected
        assertEquals(CliBoundaryExitStatus.RUNTIME, rejected.status)
        assertTrue(rejected.document.value.contains("\"reason\":\"endpoint-unavailable\""))
    }

    private fun observedLifecycle(state: RuntimeLifecycleState): RuntimeLifecycleController =
        object : RuntimeLifecycleController {
            override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult =
                RuntimeStatusResult.Observed(state)

            override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult =
                error("stop was not requested")

            override fun clean(endpoint: RuntimeEndpoint): RuntimeCleanResult =
                error("clean was not requested")
        }

    private data class Fixture(
        val root: CanonicalRoot,
        val runtimeId: SemanticRuntimeId,
        val endpoint: RuntimeEndpoint,
        val commandGraphFactory: CliCommandGraphFactory,
    ) {
        fun cli(
            runtimeDemander: RootRuntimeDemander,
            lifecycle: RuntimeLifecycleController,
            cacheLifecycle: RootSidecarCacheLifecycle,
        ): KastCli = KastCli(
            commandGraphFactory = commandGraphFactory,
            rootDiscovery = FilesystemCanonicalRootDiscovery,
            endpointLocator = RuntimeEndpointLocator {
                RuntimeEndpointResolution.Resolved(endpoint)
            },
            runtimeDemander = runtimeDemander,
            wireClient = WireClient { _, _ -> error("wire exchange must not run") },
            localMetadata = localMetadata(),
            lifecycle = lifecycle,
            productInspector = ProductInspector { error("product inspection must not run") },
            cacheLifecycle = cacheLifecycle,
        )
    }

    private fun fixture(temporary: Path): Fixture {
        val rootPath = Files.createDirectory(temporary.resolve("repo"))
        Files.writeString(rootPath.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = when (val discovery = FilesystemCanonicalRootDiscovery.discover(rootPath)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> error(discovery.failure)
        }
        val runtimeId = when (
            val refinement = SemanticRuntimeId.parse("sha256:${"b".repeat(64)}")
        ) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error(refinement.failure)
        }
        val endpoint = when (
            val resolution = RuntimeEndpoint.at(root, runtimeId, temporary.resolve("runtime.sock"))
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error(resolution.failure)
        }
        val commandGraphFactory = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error(construction.failures)
        }
        return Fixture(root, runtimeId, endpoint, commandGraphFactory)
    }

    private companion object {
        fun localMetadata(): CliLocalMetadata = when (
            val admission = CliLocalMetadata.admit("1.2.3", "{\"schemaVersion\":1}")
        ) {
            is CliLocalMetadataAdmission.Admitted -> admission.metadata
            is CliLocalMetadataAdmission.Rejected -> error(admission.failure)
        }
    }
}
