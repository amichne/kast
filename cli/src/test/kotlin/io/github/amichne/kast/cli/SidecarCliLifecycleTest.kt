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
    fun `passive command fails closed before lifecycle effects when cache identity is rejected`(
        @TempDir temporary: Path,
    ) {
        val fixture = fixture(temporary)
        var lifecycleCalls = 0
        val cli = fixture.cli(
            runtimeDemander = object : RootRuntimeDemander {
                override fun demand(
                    root: CanonicalRoot,
                    demand: HostedRuntimeDemand,
                    startup: RuntimeStartupRequest,
                ): RuntimeAdmission = error("status must not demand a runtime")
            },
            lifecycle = object : RuntimeLifecycleController {
                override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult {
                    lifecycleCalls += 1
                    return RuntimeStatusResult.Observed(RuntimeLifecycleState.STOPPED)
                }

                override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult =
                    error("stop was not requested")
            },
            cacheLifecycle = object : RootSidecarCacheLifecycle {
                override fun observe(root: Path): RootSidecarCacheObservation =
                    RootSidecarCacheObservation.Rejected(
                        SidecarCacheLifecycleFailure.INVALID_IDENTITY,
                    )

                override fun quarantine(root: Path): RootSidecarCacheQuarantine =
                    error("status must not quarantine a cache")
            },
        )

        val exit = cli.execute(listOf("status"), fixture.root.path)

        assertEquals(0, lifecycleCalls)
        assertTrue(exit is CliExit.BoundaryRejected)
        assertTrue(exit.document.value.contains("status-cache-invalid-identity"))
    }

    @Test
    fun `status targets exact stale cache endpoint without demanding a runtime`(
        @TempDir temporary: Path,
    ) {
        val fixture = fixture(temporary)
        var runtimeDemands = 0
        val cacheIdentity = "sha256:${"c".repeat(64)}"
        val staleRuntimeId = semanticRuntimeId('7')
        var observedEndpoint: RuntimeEndpoint? = null
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
            lifecycle = object : RuntimeLifecycleController {
                override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult {
                    observedEndpoint = endpoint
                    return RuntimeStatusResult.Observed(RuntimeLifecycleState.STOPPED)
                }

                override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult =
                    error("stop was not requested")
            },
            cacheLifecycle = object : RootSidecarCacheLifecycle {
                override fun observe(root: Path): RootSidecarCacheObservation =
                    RootSidecarCacheObservation.Stale(
                        RootSidecarCacheStatus(
                            cacheIdentity = cacheIdentity,
                            semanticRuntimeId = staleRuntimeId,
                            cacheRoot = temporary.resolve("cache/$cacheIdentity"),
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
        assertEquals(
            (
                fixture.endpoint.forSidecarCache(
                    cacheIdentity,
                    staleRuntimeId,
                    temporary.resolve("cache/$cacheIdentity"),
                ) as
                    RuntimeEndpointResolution.Resolved
                )
                .endpoint,
            observedEndpoint,
        )
        assertTrue(exit is CliExit.Complete)
        assertEquals(
            "{\"command\":\"status\",\"status\":\"complete\",\"runtime\":\"stopped\"," +
                "\"root\":\"${fixture.root.path}\",\"runtimeId\":\"${staleRuntimeId.value}\"," +
                "\"removed\":[],\"cache\":{\"state\":\"smart\"," +
                "\"identity\":\"$cacheIdentity\",\"ideaHome\":\"${temporary.resolve("IntelliJ IDEA.app")}\"," +
                "\"ideaBuild\":\"262.9437.185\",\"kotlinPluginBuild\":\"262.9437.185-IJ\"," +
                "\"jbrIdentity\":\"25.0.3+9-b508.16-aarch64\"," +
                "\"kastPayloadDigest\":\"sha256:${"a".repeat(64)}\"},\"bootstrap\":{\"state\":\"unavailable\"}}",
            exit.document.value,
        )
    }

    @Test
    fun `passive status preserves recorded indexing phase without runtime demand or repair`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val attempt = (io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId.admit(
            "123e4567-e89b-42d3-a456-426614174000",
        ) as Refinement.Refined).value
        val recorded = (observedCache(temporary, "sha256:${"c".repeat(64)}") as RootSidecarCacheObservation.Observed).status.copy(
            bootstrap = RuntimeBootstrapObservation.Observed(
                io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState.Starting(
                    attempt, io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase.INDEXING,
                ),
            ),
        )
        val cli = fixture.cli(
            runtimeDemander = RootRuntimeDemander { _, _, _ -> error("status must not demand a runtime") },
            lifecycle = observedLifecycle(RuntimeLifecycleState.STALE),
            cacheLifecycle = object : RootSidecarCacheLifecycle {
                override fun observe(root: Path) = RootSidecarCacheObservation.Observed(recorded)
                override fun quarantine(root: Path): RootSidecarCacheQuarantine = error("status must not repair")
            },
        )
        val exit = cli.execute(listOf("status"), fixture.root.path)
        assertTrue(exit is CliExit.Complete)
        val document = kotlinx.serialization.json.Json.parseToJsonElement(exit.document.value)
            .let { it as kotlinx.serialization.json.JsonObject }
        val bootstrap = document.getValue("bootstrap") as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive("indexing"), bootstrap["phase"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(attempt.value), bootstrap["attemptId"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(3), bootstrap["completedPhases"])
    }

    private fun observedLifecycle(state: RuntimeLifecycleState): RuntimeLifecycleController =
        object : RuntimeLifecycleController {
            override fun status(endpoint: RuntimeEndpoint): RuntimeStatusResult =
                RuntimeStatusResult.Observed(state)

            override fun stop(endpoint: RuntimeEndpoint): RuntimeStopResult =
                error("stop was not requested")
        }

    private fun observedCache(
        temporary: Path,
        cacheIdentity: String,
    ): RootSidecarCacheObservation = RootSidecarCacheObservation.Observed(
        RootSidecarCacheStatus(
            cacheIdentity = cacheIdentity,
            semanticRuntimeId = semanticRuntimeId(),
            cacheRoot = temporary.resolve("cache/$cacheIdentity"),
            state = KastCacheState.SMART,
            ideaHome = temporary.resolve("IntelliJ IDEA.app"),
            ideaBuild = "262.9437.185",
            kotlinPluginBuild = "262.9437.185-IJ",
            jbrIdentity = "25.0.3+9-b508.16-aarch64",
            kastPayloadDigest = "sha256:${"a".repeat(64)}",
        ),
    )

    private fun semanticRuntimeId(character: Char = 'b'): SemanticRuntimeId = when (
        val refinement = SemanticRuntimeId.parse(
            "sha256:${character.toString().repeat(64)}",
        )
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> error(refinement.failure)
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
