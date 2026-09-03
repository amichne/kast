package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class InstalledSidecarRuntimeDemanderTest {
    @Test
    fun `default demand discovers local IDEA and prepares only private fresh cache`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(temporary)

        val admission = fixture.demander.demand(
            fixture.root,
            HostedRuntimeDemand.Lifecycle,
            RuntimeStartupRequest.Default,
        )

        assertTrue(admission is RuntimeAdmission.Ready)
        assertTrue(fixture.observedSelection.single() is IdeHomeSelection.Standard)
        assertEquals(listOf(StartupCacheIntent.Reuse), fixture.observedIntents)
        assertEquals(fixture.ideaRuntime, fixture.observedLaunch.single().runtime)
        assertTrue(fixture.observedLaunch.single().systemDirectory.startsWith(fixture.cacheRoot))
    }

    @Test
    fun `default demand binds its endpoint to the exact IDEA JBR cache identity`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(temporary)

        fixture.demander.demand(
            fixture.root,
            HostedRuntimeDemand.Lifecycle,
            RuntimeStartupRequest.Default,
        )

        val launch = fixture.observedLaunch.single()
        val endpoint = fixture.observedEndpoint.single()
        val expectedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(
                "${launch.cache.identity.key}\n${fixture.endpoint.runtimeId.value}\n${launch.cache.root}"
                    .toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val expectedName = "kast-$expectedToken.sock"
        assertEquals(fixture.root, endpoint.root)
        assertEquals(fixture.endpoint.runtimeId, endpoint.runtimeId)
        assertEquals(expectedName, endpoint.socketPath.fileName.toString())
        assertNotEquals(fixture.endpoint.socketPath, endpoint.socketPath)
    }

    @Test
    fun `exact cache endpoint retains semantic runtime identity`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(temporary)
        val otherRuntimeId = when (
            val parsed = SemanticRuntimeId.parse("sha256:${"f".repeat(64)}")
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error(parsed.failure)
        }
        val otherBaseEndpoint = when (
            val resolution = RuntimeEndpoint.at(
                fixture.root,
                otherRuntimeId,
                fixture.endpoint.socketPath,
            )
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error(resolution.failure)
        }
        val cacheIdentity = "sha256:${"c".repeat(64)}"
        val physicalCacheRoot = temporary.resolve("cache/$cacheIdentity")
        val exact = fixture.endpoint.forSidecarCache(
            cacheIdentity,
            fixture.endpoint.runtimeId,
            physicalCacheRoot,
        )
        val otherExact = otherBaseEndpoint.forSidecarCache(
            cacheIdentity,
            otherRuntimeId,
            physicalCacheRoot,
        )

        assertTrue(exact is RuntimeEndpointResolution.Resolved)
        assertTrue(otherExact is RuntimeEndpointResolution.Resolved)
        assertNotEquals(
            (exact as RuntimeEndpointResolution.Resolved).endpoint.socketPath,
            (otherExact as RuntimeEndpointResolution.Resolved).endpoint.socketPath,
        )
    }

    @Test
    fun `exact cache endpoint includes the physical cache root authority`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(temporary)
        val cacheIdentity = "sha256:${"c".repeat(64)}"
        val first = fixture.endpoint.forSidecarCache(
            cacheIdentity,
            fixture.endpoint.runtimeId,
            temporary.resolve("cache-a/$cacheIdentity"),
        ) as RuntimeEndpointResolution.Resolved
        val second = fixture.endpoint.forSidecarCache(
            cacheIdentity,
            fixture.endpoint.runtimeId,
            temporary.resolve("cache-b/$cacheIdentity"),
        ) as RuntimeEndpointResolution.Resolved

        assertNotEquals(first.endpoint.socketPath, second.endpoint.socketPath)
    }

    @Test
    fun `reachable legacy endpoint blocks launch after exact cache preparation`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(
            temporary,
            RuntimeEndpointProbe { RuntimeEndpointReachability.Reachable },
        )

        val admission = fixture.demander.demand(
            fixture.root,
            HostedRuntimeDemand.Lifecycle,
            RuntimeStartupRequest.Default,
        )

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.LegacySidecarActive),
            admission,
        )
        assertEquals(listOf(StartupCacheIntent.Reuse), fixture.observedIntents)
        assertTrue(fixture.observedLaunch.isEmpty())
    }

    @Test
    fun `explicit seed intent reaches cache authority without weakening paths`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(temporary)
        val source = Files.createDirectory(temporary.resolve("source-system")).toRealPath()
        val request = RuntimeStartupRequest.Requested(
            StartupIdeHome.Explicit(fixture.ideaRuntime.home),
            StartupCacheIntent.Seed(
                StartupIdeaSystem.Explicit(source),
                IndexSeedConsentRequest.PREGRANTED,
            ),
        )

        val admission = fixture.demander.demand(
            fixture.root,
            HostedRuntimeDemand.Lifecycle,
            request,
        )

        assertTrue(admission is RuntimeAdmission.Ready)
        assertEquals(
            listOf(IdeHomeSelection.Explicit(fixture.ideaRuntime.home)),
            fixture.observedSelection,
        )
        assertEquals(listOf(request.cacheIntent), fixture.observedIntents)
        assertEquals(KastCacheState.SEEDED, fixture.observedLaunch.single().cacheState)
    }

    @Test
    fun `already reachable sidecar delegates correlated readiness and preserves smart cache state`(
        @TempDir temporary: Path,
    ) {
        val fixture = demanderFixture(temporary)
        fixture.demander.demand(
            fixture.root,
            HostedRuntimeDemand.Lifecycle,
            RuntimeStartupRequest.Default,
        )
        val launch = fixture.observedLaunch.single()
        assertEquals(
            CacheStateTransition.Recorded,
            SidecarCacheStateFile.record(launch.cache.root, KastCacheState.SMART),
        )
        var delegated = false
        val demander = ExactSidecarProcessDemander(
            runtimeDemanderFactory = { _, _ ->
                RuntimeDemander { _, endpoint ->
                    delegated = true
                    RuntimeAdmission.Ready(endpoint)
                }
            },
        )

        val admission = demander.demand(
            fixture.executable,
            launch,
            fixture.root,
            fixture.endpoint,
        )

        assertEquals(RuntimeAdmission.Ready(fixture.endpoint), admission)
        assertTrue(delegated)
        assertEquals(
            CacheStateObservation.Observed(KastCacheState.SMART),
            SidecarCacheStateFile.observe(launch.cache.root),
        )
    }

    private fun demanderFixture(
        temporary: Path,
        legacyEndpointProbe: RuntimeEndpointProbe = RuntimeEndpointProbe {
            RuntimeEndpointReachability.Unreachable
        },
    ): DemanderFixture {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = FilesystemCanonicalRootDiscovery.discover(project).let {
            (it as CanonicalRootDiscovery.Discovered).root
        }
        val runtimeId = SemanticRuntimeId.parse("sha256:${"b".repeat(64)}").let {
            (it as Refinement.Refined).value
        }
        val runtimeDirectory = InstalledRuntimeDirectory.admit(
            configured = temporary.toString(),
            temporaryDirectory = null,
        ).let { (it as InstalledRuntimeDirectoryAdmission.Admitted).directory }
        val endpointLocator = Sha256RuntimeEndpointLocator(
            RuntimeSocketDirectory.from(runtimeDirectory),
            runtimeId,
        )
        val endpoint = (endpointLocator.locate(root) as RuntimeEndpointResolution.Resolved).endpoint
        val executable = Files.writeString(temporary.resolve("kast-indexer"), "#!/bin/sh\n")
        executable.toFile().setExecutable(true)
        val privatePlugins = Files.createDirectory(temporary.resolve("private-plugins")).toRealPath()
        val payload = SidecarPayload.admit(
            runtimeId,
            executable,
            privatePlugins,
            "sha256:${"a".repeat(64)}",
        ).let { (it as SidecarPayloadAdmission.Admitted).payload }

        val ideaHome = Files.createDirectory(temporary.resolve("idea-home")).toRealPath()
        val java = Files.createFile(ideaHome.resolve("java")).toRealPath()
        java.toFile().setExecutable(true)
        val identity = runtimeIdentity()
        val ideaRuntime = InstalledIdeRuntime(ideaHome, java, identity)
        val cacheRoot = Files.createDirectory(temporary.resolve("cache-root")).toRealPath()
        val selections = mutableListOf<IdeHomeSelection>()
        val intents = mutableListOf<StartupCacheIntent>()
        val launches = mutableListOf<PreparedSidecarLaunch>()
        val endpoints = mutableListOf<RuntimeEndpoint>()

        val demander = InstalledSidecarRootRuntimeDemander(
            endpointLocator = endpointLocator,
            support = identity.supportedPair,
            userHome = temporary,
            payloadResolver = SidecarPayloadResolver {
                SidecarPayloadResolution.Resolved(payload)
            },
            ideRuntimeResolver = SidecarIdeRuntimeResolver { _, _, selection ->
                selections += selection
                InstalledIdeRuntimeDiscoveryResult.Discovered(ideaRuntime)
            },
            cachePreparer = SidecarCachePreparer { _, cacheIdentity, intent ->
                intents += intent
                val cache = cacheRoot.resolve(cacheIdentity.key)
                val system = Files.createDirectories(cache.resolve("system")).toRealPath()
                val config = Files.createDirectories(cache.resolve("config")).toRealPath()
                val log = Files.createDirectories(cache.resolve("log")).toRealPath()
                PreparedSidecarCache.admit(
                    cacheIdentity,
                    cache,
                    system,
                    config,
                    log,
                    if (intent is StartupCacheIntent.Seed) {
                        KastCacheState.SEEDED
                    } else {
                        KastCacheState.FRESH
                    },
                )
            },
            processDemander = SidecarProcessDemander { admittedExecutable, context, exactRoot, endpoint ->
                assertEquals(payload.executable, admittedExecutable)
                assertEquals(root, exactRoot)
                launches += context
                endpoints += endpoint
                RuntimeAdmission.Ready(endpoint)
            },
            legacyEndpointProbe = legacyEndpointProbe,
        )
        return DemanderFixture(
            demander,
            root,
            ideaRuntime,
            cacheRoot,
            payload.executable,
            endpoint,
            selections,
            intents,
            launches,
            endpoints,
        )
    }

    private fun runtimeIdentity(): IdeRuntimeIdentity {
        val pair = SupportedIdeRuntimePair.admit(
            "262.9437.185",
            "262.9437.185-IJ",
        ).let { (it as SupportedIdeRuntimePairAdmission.Admitted).pair }
        return IdeRuntimeIdentity.admit(
            pair,
            IdeRuntimeIdentityCandidate(
                pair.ideaBuild,
                pair.kotlinPluginBuild,
                "jbr-25.0.3+9-b508.16-aarch64",
                "sha256:${"a".repeat(64)}",
            ),
        ).let { (it as IdeRuntimeIdentityAdmission.Admitted).identity }
    }
}

private data class DemanderFixture(
    val demander: InstalledSidecarRootRuntimeDemander,
    val root: CanonicalRoot,
    val ideaRuntime: InstalledIdeRuntime,
    val cacheRoot: Path,
    val executable: IndexerExecutable,
    val endpoint: RuntimeEndpoint,
    val observedSelection: List<IdeHomeSelection>,
    val observedIntents: List<StartupCacheIntent>,
    val observedLaunch: List<PreparedSidecarLaunch>,
    val observedEndpoint: List<RuntimeEndpoint>,
)
