package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
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
            missingRuntimeResolver,
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
        val runtime = InstalledIdeRuntime(ideaHome, java, identity)
        val cacheIdentity = assertInstanceOf(
            KastCacheIdentityDerivation.Derived::class.java,
            KastCacheIdentity.derive(project, runtime, semanticRuntimeId()),
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
                runtime,
                cacheIdentity,
                StartupCacheIntent.ReuseOrFresh,
            ),
        ).cache

        assertEquals(
            physicalParent.resolve("caches").resolve(cacheIdentity.key).toRealPath(),
            prepared.root,
        )
        assertTrue(!Files.isSymbolicLink(prepared.root))
        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            alias.resolve("caches"),
            releaseIdentity(identity),
            cacheRuntimeResolver(runtime),
        )
        val observed = assertInstanceOf(
            RootSidecarCacheObservation.Observed::class.java,
            lifecycle.observe(project),
        )
        assertEquals(cacheIdentity.key, observed.status.cacheIdentity)
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
        val runtime = InstalledIdeRuntime(ideaHome, java, identity)
        val cacheIdentity = assertInstanceOf(
            KastCacheIdentityDerivation.Derived::class.java,
            KastCacheIdentity.derive(project, runtime, semanticRuntimeId()),
        ).identity
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
        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            releaseIdentity(identity),
            cacheRuntimeResolver(runtime),
        )
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
            missingRuntimeResolver,
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
        val staleRuntimeIdentity = runtimeIdentity('b')
        val currentRuntimeIdentity = runtimeIdentity('b')
        val staleRuntime = InstalledIdeRuntime(ideaHome, java, staleRuntimeIdentity)
        val currentRuntime = InstalledIdeRuntime(ideaHome, java, currentRuntimeIdentity)
        val staleSemanticRuntimeId = semanticRuntimeId('8')
        val currentSemanticRuntimeId = semanticRuntimeId()
        val staleCacheIdentity = cacheIdentity(
            project,
            staleRuntime,
            staleSemanticRuntimeId,
        )
        val currentCacheIdentity = cacheIdentity(
            project,
            currentRuntime,
            currentSemanticRuntimeId,
        )
        val staleRoot = Files.createDirectory(cacheRoot.resolve(staleCacheIdentity.key)).toRealPath()
        val currentRoot = Files.createDirectory(cacheRoot.resolve(currentCacheIdentity.key)).toRealPath()

        assertEquals(
            CacheIdentityTransition.Recorded,
            SidecarCacheIdentityFile.record(
                staleRoot,
                staleRuntime,
                staleCacheIdentity,
            ),
        )
        assertEquals(
            CacheIdentityTransition.Recorded,
            SidecarCacheIdentityFile.record(
                currentRoot,
                currentRuntime,
                currentCacheIdentity,
            ),
        )
        assertEquals(CacheStateTransition.Recorded, SidecarCacheStateFile.record(staleRoot, KastCacheState.SMART))
        assertEquals(CacheStateTransition.Recorded, SidecarCacheStateFile.record(currentRoot, KastCacheState.SEEDED))

        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            releaseIdentity(currentRuntimeIdentity, currentSemanticRuntimeId),
            cacheRuntimeResolver(
                currentRuntime,
            ),
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
        val stale = assertInstanceOf(
            RootSidecarCacheObservation.Stale::class.java,
            lifecycle.observe(project),
        )
        assertEquals(staleCacheIdentity.key, stale.status.cacheIdentity)
        assertEquals(staleSemanticRuntimeId, stale.status.semanticRuntimeId)
    }

    @Test
    fun `compatible patch caches select the exact runtime currently installed at IDEA home`() {
        val project = Files.createDirectory(temporary.resolve("patched-project")).toRealPath()
        val ideaHome = Files.createDirectory(temporary.resolve("patched-idea")).toRealPath()
        val java = Files.createFile(ideaHome.resolve("java")).toRealPath()
        val cacheRoot = Files.createDirectory(temporary.resolve("patched-caches")).toRealPath()
        val staleIdentity = runtimeIdentity(
            ideaBuild = "262.9437.185",
            kotlinBuild = "262.9437.185-IJ",
            jbrIdentity = "jbr-25.0.3-aarch64",
        )
        val currentIdentity = runtimeIdentity(
            ideaBuild = "262.9999.41",
            kotlinBuild = "262.8888.17-IJ",
            jbrIdentity = "jbr-25.0.4-aarch64",
        )
        val staleRuntime = InstalledIdeRuntime(ideaHome, java, staleIdentity)
        val currentRuntime = InstalledIdeRuntime(ideaHome, java, currentIdentity)
        val staleCache = cacheIdentity(project, staleRuntime)
        val currentCache = cacheIdentity(project, currentRuntime)
        val staleRoot = Files.createDirectory(cacheRoot.resolve(staleCache.key)).toRealPath()
        val currentRoot = Files.createDirectory(cacheRoot.resolve(currentCache.key)).toRealPath()
        SidecarCacheIdentityFile.record(
            staleRoot,
            staleRuntime,
            staleCache,
        )
        SidecarCacheIdentityFile.record(
            currentRoot,
            currentRuntime,
            currentCache,
        )
        SidecarCacheStateFile.record(staleRoot, KastCacheState.SMART)
        SidecarCacheStateFile.record(currentRoot, KastCacheState.FRESH)
        val lifecycle = FilesystemRootSidecarCacheLifecycle(
            cacheRoot,
            releaseIdentity(currentIdentity),
            cacheRuntimeResolver(
                currentRuntime,
            ),
        )

        val observation = assertInstanceOf(
            RootSidecarCacheObservation.Observed::class.java,
            lifecycle.observe(project),
        )

        assertEquals(currentCache.key, observation.status.cacheIdentity)
        assertEquals(KastCacheState.FRESH, observation.status.state)
    }

    private fun cacheIdentity(
        project: Path,
        runtime: InstalledIdeRuntime,
        semanticRuntimeId: SemanticRuntimeId = semanticRuntimeId(),
    ): KastCacheIdentity = assertInstanceOf(
        KastCacheIdentityDerivation.Derived::class.java,
        KastCacheIdentity.derive(project, runtime, semanticRuntimeId),
    ).identity

    private fun semanticRuntimeId(character: Char = '9'): SemanticRuntimeId = when (
        val refinement = SemanticRuntimeId.parse(
            "sha256:${character.toString().repeat(64)}",
        )
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> error(refinement.failure)
    }

    private fun releaseIdentity(
        runtimeIdentity: IdeRuntimeIdentity,
        semanticRuntimeId: SemanticRuntimeId = semanticRuntimeId(),
    ): SidecarCacheReleaseIdentity =
        assertInstanceOf(
            SidecarCacheReleaseIdentityAdmission.Admitted::class.java,
            SidecarCacheReleaseIdentity.admit(
                runtimeIdentity.supportedPair,
                runtimeIdentity.kastPayloadDigest,
                semanticRuntimeId,
            ),
        ).identity

    private fun runtimeIdentity(
        payloadCharacter: Char = 'a',
        ideaBuild: String = "262.9437.185",
        kotlinBuild: String = "262.9437.185-IJ",
        jbrIdentity: String = "jbr-25.0.3-aarch64",
    ): IdeRuntimeIdentity {
        val pair = assertInstanceOf(
            SupportedIdeRuntimePairAdmission.Admitted::class.java,
            SupportedIdeRuntimePair.admit(ideaBuild, kotlinBuild),
        ).pair
        return assertInstanceOf(
            IdeRuntimeIdentityAdmission.Admitted::class.java,
            IdeRuntimeIdentity.admit(
                pair,
                IdeRuntimeIdentityCandidate(
                    pair.ideaBuild,
                    pair.kotlinPluginBuild,
                    jbrIdentity,
                    "sha256:${payloadCharacter.toString().repeat(64)}",
                ),
            ),
        ).identity
    }

    private fun cacheRuntimeResolver(
        runtime: InstalledIdeRuntime,
    ): SidecarIdeRuntimeResolver = SidecarIdeRuntimeResolver { _, _, selection ->
        if (selection == IdeHomeSelection.Explicit(runtime.home)) {
            InstalledIdeRuntimeDiscoveryResult.Discovered(runtime)
        } else {
            InstalledIdeRuntimeDiscoveryResult.Rejected(IndexSeedFailure.MissingInstallation)
        }
    }

    private val missingRuntimeResolver = SidecarIdeRuntimeResolver { _, _, _ ->
        InstalledIdeRuntimeDiscoveryResult.Rejected(IndexSeedFailure.MissingInstallation)
    }
}
