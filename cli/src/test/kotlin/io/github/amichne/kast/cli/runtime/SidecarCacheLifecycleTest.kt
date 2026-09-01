package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarCacheLifecycleTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `an uncreated Kast cache root is observed as absent without creating it`() {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val cacheRoot = temporary.resolve("not-created")
        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            releaseIdentity(runtimeIdentity()),
        )

        assertEquals(RootSidecarCacheObservation.Absent, lifecycle.observe(project))
        assertEquals(RootSidecarCacheQuarantine.NoCache(), lifecycle.quarantine(project))
        assertTrue(Files.notExists(cacheRoot))
    }

    @Test
    fun `fresh cache root refines an authorized parent alias to physical ownership`() {
        val project = Files.createDirectory(temporary.resolve("aliased-project")).toRealPath()
        val ideaHome = Files.createDirectory(temporary.resolve("aliased-idea")).toRealPath()
        val java = Files.createFile(ideaHome.resolve("java")).toRealPath()
        val physicalParent = Files.createDirectory(temporary.resolve("physical-cache-parent"))
            .toRealPath()
        val alias = temporary.resolve("cache-parent-alias")
        Files.createSymbolicLink(alias, physicalParent)
        val identity = runtimeIdentity()
        val cacheIdentity = assertInstanceOf(
            KastCacheIdentityDerivation.Derived::class.java,
            KastCacheIdentity.derive(project, identity),
        ).identity
        val preparer = FilesystemSidecarCachePreparer(
            alias.resolve("caches"),
            temporary.resolve("unused-source"),
            IndexSeedFilesystemService(
                SourceIdeQuiescenceProbe {
                    SourceIdeQuiescence(
                        SourceIdeProcessState.UNKNOWN,
                        SourceIdeLockState.UNKNOWN,
                    )
                },
                IndexSeedFilesystemProbe { _, _ -> IndexSeedFilesystem.UNSUPPORTED },
                IndexSeedCloner { _, _ -> IndexSeedCopyResult.Rejected },
            ),
        )

        val prepared = assertInstanceOf(
            SidecarCachePreparation.Prepared::class.java,
            preparer.prepare(
                InstalledIdeRuntime(ideaHome, java, identity),
                cacheIdentity,
                StartupCacheIntent.ReuseOrFresh,
            ),
        ).cache

        assertEquals(
            physicalParent.resolve("caches").resolve(cacheIdentity.key).toRealPath(),
            prepared.root,
        )
        assertTrue(!Files.isSymbolicLink(prepared.root))
    }

    @Test
    fun `cache identity and state survive restart and exact cache is quarantined`() {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val ideaHome = Files.createDirectory(temporary.resolve("idea")).toRealPath()
        val java = Files.createFile(ideaHome.resolve("java")).toRealPath()
        val sourceIdea = Files.createDirectory(temporary.resolve("source-idea")).toRealPath()
        val sourceMarker = Files.writeString(sourceIdea.resolve("must-not-change"), "source")
        val cacheRoot = Files.createDirectory(temporary.resolve("caches")).toRealPath()
        val identity = runtimeIdentity()
        val cacheIdentity = assertInstanceOf(
            KastCacheIdentityDerivation.Derived::class.java,
            KastCacheIdentity.derive(project, identity),
        ).identity
        val runtime = InstalledIdeRuntime(ideaHome, java, identity)
        val preparer = FilesystemSidecarCachePreparer(
            cacheRoot,
            sourceIdea,
            IndexSeedFilesystemService(
                SourceIdeQuiescenceProbe {
                    SourceIdeQuiescence(
                        SourceIdeProcessState.UNKNOWN,
                        SourceIdeLockState.UNKNOWN,
                    )
                },
                IndexSeedFilesystemProbe { _, _ -> IndexSeedFilesystem.UNSUPPORTED },
                IndexSeedCloner { _, _ -> IndexSeedCopyResult.Rejected },
            ),
        )

        val prepared = assertInstanceOf(
            SidecarCachePreparation.Prepared::class.java,
            preparer.prepare(runtime, cacheIdentity, StartupCacheIntent.ReuseOrFresh),
        ).cache
        assertEquals(KastCacheState.FRESH, prepared.state)
        assertEquals(
            CacheStateObservation.Observed(KastCacheState.FRESH),
            SidecarCacheStateFile.observe(prepared.root),
        )

        assertEquals(CacheStateTransition.Recorded, SidecarCacheStateFile.record(
            prepared.root,
            KastCacheState.SMART,
        ))
        val lifecycle = FilesystemRootSidecarCacheLifecycle(cacheRoot, releaseIdentity(identity))
        val smart = assertInstanceOf(
            RootSidecarCacheObservation.Observed::class.java,
            lifecycle.observe(project),
        )
        assertEquals(cacheIdentity.key, smart.status.cacheIdentity)
        assertEquals(KastCacheState.SMART, smart.status.state)
        assertEquals(identity.supportedPair.ideaBuild, smart.status.ideaBuild)

        val quarantine = assertInstanceOf(
            RootSidecarCacheQuarantine.Quarantined::class.java,
            lifecycle.quarantine(project),
        )
        assertTrue(Files.notExists(prepared.root))
        assertTrue(Files.isDirectory(quarantine.quarantinedRoot))
        val restart = assertInstanceOf(
            RuntimeStartupRequest.Requested::class.java,
            quarantine.restart,
        )
        assertEquals(StartupIdeHome.Explicit(ideaHome), restart.ideHome)
        assertEquals("source", Files.readString(sourceMarker))
    }

    @Test
    fun `ambiguous and malformed identities fail closed`() {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val cacheRoot = Files.createDirectory(temporary.resolve("caches")).toRealPath()
        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            releaseIdentity(runtimeIdentity()),
        )
        repeat(2) { index ->
            val root = Files.createDirectory(cacheRoot.resolve("invalid-$index"))
            Files.writeString(
                root.resolve("cache-identity.properties"),
                "format=not-kast\nproject.root=$project\n",
            )
        }

        assertEquals(
            RootSidecarCacheObservation.Rejected(SidecarCacheLifecycleFailure.INVALID_IDENTITY),
            lifecycle.observe(project),
        )
        assertEquals(
            RootSidecarCacheQuarantine.Rejected(SidecarCacheLifecycleFailure.INVALID_IDENTITY),
            lifecycle.quarantine(project),
        )
    }

    @Test
    fun `current release cache is selected while valid stale payload caches coexist`() {
        val project = Files.createDirectory(temporary.resolve("versioned-project")).toRealPath()
        val ideaHome = Files.createDirectory(temporary.resolve("versioned-idea")).toRealPath()
        val java = Files.createFile(ideaHome.resolve("java")).toRealPath()
        val cacheRoot = Files.createDirectory(temporary.resolve("versioned-caches")).toRealPath()
        val staleRuntimeIdentity = runtimeIdentity('a')
        val currentRuntimeIdentity = runtimeIdentity('b')
        val staleCacheIdentity = cacheIdentity(project, staleRuntimeIdentity)
        val currentCacheIdentity = cacheIdentity(project, currentRuntimeIdentity)
        val staleRoot = Files.createDirectory(cacheRoot.resolve(staleCacheIdentity.key)).toRealPath()
        val currentRoot = Files.createDirectory(cacheRoot.resolve(currentCacheIdentity.key)).toRealPath()

        assertEquals(
            CacheIdentityTransition.Recorded,
            SidecarCacheIdentityFile.record(
                staleRoot,
                InstalledIdeRuntime(ideaHome, java, staleRuntimeIdentity),
                staleCacheIdentity,
            ),
        )
        assertEquals(
            CacheIdentityTransition.Recorded,
            SidecarCacheIdentityFile.record(
                currentRoot,
                InstalledIdeRuntime(ideaHome, java, currentRuntimeIdentity),
                currentCacheIdentity,
            ),
        )
        assertEquals(CacheStateTransition.Recorded, SidecarCacheStateFile.record(staleRoot, KastCacheState.SMART))
        assertEquals(CacheStateTransition.Recorded, SidecarCacheStateFile.record(currentRoot, KastCacheState.SEEDED))

        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            releaseIdentity(currentRuntimeIdentity),
        )
        val observation = assertInstanceOf(
            RootSidecarCacheObservation.Observed::class.java,
            lifecycle.observe(project),
        )

        assertEquals(currentCacheIdentity.key, observation.status.cacheIdentity)
        assertEquals(KastCacheState.SEEDED, observation.status.state)
        val quarantine = assertInstanceOf(
            RootSidecarCacheQuarantine.Quarantined::class.java,
            lifecycle.quarantine(project),
        )
        assertTrue(Files.isDirectory(staleRoot))
        assertTrue(Files.notExists(currentRoot))
        assertTrue(Files.isDirectory(quarantine.quarantinedRoot))
        assertEquals(RootSidecarCacheObservation.Absent, lifecycle.observe(project))
    }

    private fun cacheIdentity(
        project: Path,
        runtimeIdentity: IdeRuntimeIdentity,
    ): KastCacheIdentity = assertInstanceOf(
        KastCacheIdentityDerivation.Derived::class.java,
        KastCacheIdentity.derive(project, runtimeIdentity),
    ).identity

    private fun releaseIdentity(runtimeIdentity: IdeRuntimeIdentity): SidecarCacheReleaseIdentity =
        assertInstanceOf(
            SidecarCacheReleaseIdentityAdmission.Admitted::class.java,
            SidecarCacheReleaseIdentity.admit(
                runtimeIdentity.supportedPair,
                runtimeIdentity.kastPayloadDigest,
            ),
        ).identity

    private fun runtimeIdentity(payloadCharacter: Char = 'a'): IdeRuntimeIdentity {
        val pair = assertInstanceOf(
            SupportedIdeRuntimePairAdmission.Admitted::class.java,
            SupportedIdeRuntimePair.admit("262.9437.185", "262.9437.185-IJ"),
        ).pair
        return assertInstanceOf(
            IdeRuntimeIdentityAdmission.Admitted::class.java,
            IdeRuntimeIdentity.admit(
                pair,
                IdeRuntimeIdentityCandidate(
                    pair.ideaBuild,
                    pair.kotlinPluginBuild,
                    "jbr-25.0.3-aarch64",
                    "sha256:${payloadCharacter.toString().repeat(64)}",
                ),
            ),
        ).identity
    }
}
