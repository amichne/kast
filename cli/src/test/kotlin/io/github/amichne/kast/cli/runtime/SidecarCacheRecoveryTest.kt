package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarCacheRecoveryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `rebuild quarantines every root identity only after every exact owner stops`() {
        val fixture = fixture()
        val first = fixture.addCache('1')
        val second = fixture.addCache('2')
        val unrelated = fixture.cacheRoot.resolve("unrelated").also { Files.createDirectory(it) }
        Files.writeString(unrelated.resolve("sentinel"), "preserve")
        // Derived state is not authority for whether an exact cache belongs to the root.
        Files.writeString(first.resolve("cache-state.properties"), "corrupt state")
        assertEquals(
            RootSidecarCacheObservation.Rejected(SidecarCacheLifecycleFailure.AMBIGUOUS_IDENTITY),
            fixture.lifecycle.observe(fixture.project),
        )
        val inventory = fixture.inventory()
        assertEquals(2, inventory.caches.size)
        assertEquals(
            StartupIdeHome.Explicit(fixture.runtime.home),
            (inventory.retainedIdeHome() as Refinement.Refined).value,
        )
        val observed = mutableListOf<RuntimeEndpoint>()
        val stopped = (StoppedSidecarCaches.stopAll(inventory, fixture.endpoint, lifecycle { endpoint ->
            assertTrue(Files.isDirectory(first))
            assertTrue(Files.isDirectory(second))
            observed += endpoint
            RuntimeStopResult.Stopped()
        }) as Refinement.Refined).value

        val result = assertInstanceOf(
            RootSidecarCacheQuarantine.Quarantined::class.java,
            fixture.lifecycle.quarantine(stopped),
        )

        assertEquals(3, observed.size) // Legacy endpoint and the two exact cache endpoints.
        assertEquals(setOf(fixture.endpoint.runtimeId, runtimeId('1'), runtimeId('2')), observed.map { it.runtimeId }.toSet())
        assertEquals(2, result.roots.size)
        assertTrue(result.roots.all { Files.isDirectory(it) })
        assertFalse(Files.exists(first))
        assertFalse(Files.exists(second))
        assertEquals("preserve", Files.readString(unrelated.resolve("sentinel")))
        assertTrue(fixture.inventory().caches.isEmpty())
    }

    @Test
    fun `an ambiguous process cannot produce quarantine authority`() {
        val fixture = fixture()
        val cache = fixture.addCache('1')
        val result = StoppedSidecarCaches.stopAll(fixture.inventory(), fixture.endpoint, lifecycle {
            RuntimeStopResult.Rejected(RuntimeStopFailure.PROCESS_AMBIGUOUS)
        })

        assertEquals(Refinement.Rejected(RuntimeAdmissionFailure.StopRejected(RuntimeStopFailure.PROCESS_AMBIGUOUS)), result)
        assertTrue(Files.isDirectory(cache))
        assertFalse(Files.exists(fixture.cacheRoot.resolve("quarantine")))
    }

    @Test
    fun `new cache identity after stop invalidates quarantine authority without moving anything`() {
        val fixture = fixture()
        val first = fixture.addCache('1')
        val stopped = (StoppedSidecarCaches.stopAll(fixture.inventory(), fixture.endpoint, lifecycle {
            RuntimeStopResult.Stopped()
        }) as Refinement.Refined).value
        val second = fixture.addCache('2')

        assertEquals(
            RootSidecarCacheQuarantine.Rejected(SidecarCacheLifecycleFailure.INVENTORY_CHANGED),
            fixture.lifecycle.quarantine(stopped),
        )
        assertTrue(Files.isDirectory(first))
        assertTrue(Files.isDirectory(second))
    }

    @Test
    fun `different cached installations require an explicit installation choice`() {
        val fixture = fixture()
        val first = fixture.addCache('1')
        val reference = fixture.inventory().caches.single()
        val inventory = RootSidecarCacheInventory(fixture.project, listOf(
            reference,
            reference.copy(cacheRoot = first.resolveSibling("other"), ideaHome = temporary.resolve("other-idea")),
        ))
        assertEquals(Refinement.Rejected(SidecarCacheLifecycleFailure.AMBIGUOUS_IDENTITY), inventory.retainedIdeHome())
    }

    private fun lifecycle(stop: (RuntimeEndpoint) -> RuntimeStopResult) = object : RuntimeLifecycleController {
        override fun status(endpoint: RuntimeEndpoint) = RuntimeStatusResult.Observed(RuntimeLifecycleState.STOPPED)
        override fun stop(endpoint: RuntimeEndpoint) = stop.invoke(endpoint)
    }

    private fun fixture(): Fixture {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val cacheRoot = Files.createDirectory(temporary.resolve("caches")).toRealPath()
        val home = Files.createDirectory(temporary.resolve("idea")).toRealPath()
        val java = Files.createFile(home.resolve("java")).toRealPath()
        val pair = (SupportedIdeRuntimePair.admit("262.9437.185", "262.9437.185-IJ") as SupportedIdeRuntimePairAdmission.Admitted).pair
        val identity = (IdeRuntimeIdentity.admit(pair, IdeRuntimeIdentityCandidate(
            pair.ideaBuild, pair.kotlinPluginBuild, "jbr-25.0.3-aarch64", "sha256:${"a".repeat(64)}",
        )) as IdeRuntimeIdentityAdmission.Admitted).identity
        val runtime = InstalledIdeRuntime(home, java, identity)
        val release = (SidecarCacheReleaseIdentity.admit(pair, identity.kastPayloadDigest, runtimeId('9'))
            as SidecarCacheReleaseIdentityAdmission.Admitted).identity
        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot, release, SidecarIdeRuntimeResolver { _, _, _ -> InstalledIdeRuntimeDiscoveryResult.Discovered(runtime) },
            importEnvironment = { Refinement.Refined(GradleImportEnvironment.Empty) },
        )
        val endpoint = (RuntimeEndpoint.at(CanonicalRoot(project), runtimeId('9'), temporary.resolve("base.sock"))
            as RuntimeEndpointResolution.Resolved).endpoint
        return Fixture(project, cacheRoot, runtime, lifecycle, endpoint)
    }

    private data class Fixture(
        val project: Path,
        val cacheRoot: Path,
        val runtime: InstalledIdeRuntime,
        val lifecycle: FilesystemRootSidecarCacheLifecycle,
        val endpoint: RuntimeEndpoint,
    ) {
        fun inventory() = (lifecycle.inventory(project) as Refinement.Refined).value
        fun addCache(character: Char): Path {
            val cache = (KastCacheIdentity.derive(project, runtime, runtimeId(character)) as KastCacheIdentityDerivation.Derived).identity
            val path = Files.createDirectory(cacheRoot.resolve(cache.key)).toRealPath()
            assertEquals(CacheIdentityTransition.Recorded, SidecarCacheIdentityFile.record(path, runtime, cache))
            assertEquals(CacheStateTransition.Recorded, SidecarCacheStateFile.record(path, KastCacheState.SMART))
            return path
        }
    }

    companion object {
        private fun runtimeId(character: Char): SemanticRuntimeId =
            (SemanticRuntimeId.parse("sha256:${character.toString().repeat(64)}") as Refinement.Refined).value
    }
}
