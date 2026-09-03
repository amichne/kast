package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class IndexSeedFilesystemServiceTest {
    @Test
    fun `stale source pid marker is not a live lock`(
        @TempDir temporary: Path,
    ) {
        val source = Files.createDirectory(temporary.resolve("source-system")).toRealPath()
        Files.writeString(source.resolve(".pid"), Long.MAX_VALUE.toString())

        assertEquals(
            SourceIdeQuiescence(
                SourceIdeProcessState.STOPPED,
                SourceIdeLockState.UNLOCKED,
            ),
            FilesystemSourceIdeQuiescenceProbe.observe(source),
        )
    }

    @Test
    fun `live source pid and source port remain locked`(
        @TempDir temporary: Path,
    ) {
        val liveSource = Files.createDirectory(temporary.resolve("live-source")).toRealPath()
        Files.writeString(liveSource.resolve(".pid"), ProcessHandle.current().pid().toString())
        assertEquals(
            SourceIdeQuiescence(
                SourceIdeProcessState.RUNNING,
                SourceIdeLockState.LOCKED,
            ),
            FilesystemSourceIdeQuiescenceProbe.observe(liveSource),
        )

        val portLockedSource = Files.createDirectory(temporary.resolve("port-locked-source"))
            .toRealPath()
        Files.writeString(portLockedSource.resolve(".pid"), Long.MAX_VALUE.toString())
        Files.writeString(portLockedSource.resolve(".port"), "6942")
        assertEquals(
            SourceIdeQuiescence(
                SourceIdeProcessState.STOPPED,
                SourceIdeLockState.LOCKED,
            ),
            FilesystemSourceIdeQuiescenceProbe.observe(portLockedSource),
        )
    }

    @Test
    fun `escaped IntelliJ project state identifies the exact project cache`(
        @TempDir temporary: Path,
    ) {
        val fixture = seedFixture(temporary)
        val escapedProjectRoot = fixture.projectRoot.toString().replace("&", "&amp;")
        Files.writeString(
            fixture.sourceSystem.resolve("projects/target.0123abcd/cache-state.xml"),
            """
            <project version="4">
              <component name="ExternalSystemProjectTracker">{
                &quot;projectData&quot;: {
                  &quot;GRADLE&quot;: {
                    &quot;$escapedProjectRoot&quot;: {}
                  }
                }
              }</component>
            </project>
            """.trimIndent(),
        )

        val projectIdentity = fixture.projectIdentity()
        val publication = service(cloner = CopyingTestCloner)
            .seed(
                fixture.request(
                    projectEvidence = SeedProjectEvidence.Comparison(
                        projectIdentity,
                        projectIdentity,
                    ),
                ),
            )
            .seeded()

        assertTrue(
            Files.exists(
                publication.systemDirectory.resolve("projects/target.0123abcd/cache-state.xml"),
            ),
        )
    }

    @Test
    fun `external seed publishes only the global allowlist without project proof`(
        @TempDir temporary: Path,
    ) {
        val fixture = seedFixture(temporary)
        val activity = mutableListOf<IndexSeedActivity>()
        val service = service(
            cloner = CopyingTestCloner,
            activitySink = IndexSeedActivitySink { event ->
                activity += event
                IndexSeedActivityPublication.PUBLISHED
            },
        )

        val publication = service.seed(fixture.request()).seeded()

        assertEquals(fixture.cacheIdentity, publication.receipt.cacheIdentity)
        assertEquals(
            fixture.cacheRoot.resolve(fixture.cacheIdentity.key).resolve("system"),
            publication.systemDirectory,
        )
        assertTrue(Files.exists(publication.systemDirectory.resolve(".home")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("caches/vfs.dat")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("index/symbols.dat")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("classpath")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("global-model-cache")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("projects")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("LocalHistory")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("vcs-log")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("log")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("projects/other.89abcdef")))
        assertTrue(Files.exists(publication.root.resolve("seed-receipt.properties")))
        assertEquals(SeedProjectProofState.GlobalOnly, publication.receipt.projectProofState)
        assertEquals(
            IndexSeedStage.entries.flatMap { stage ->
                listOf(
                    IndexSeedActivity.Started(stage),
                    IndexSeedActivity.Completed(stage),
                )
            },
            activity,
        )
    }

    @Test
    fun `exact project proof admits project model and classpath categories`(
        @TempDir temporary: Path,
    ) {
        val fixture = seedFixture(temporary)
        val identity = fixture.projectIdentity()

        val publication = service(cloner = CopyingTestCloner).seed(
            fixture.request(
                projectEvidence = SeedProjectEvidence.Comparison(identity, identity),
            ),
        ).seeded()

        assertEquals(IndexSeedCategory.entries.toSet(), publication.receipt.categories)
        assertTrue(publication.receipt.projectProofState is SeedProjectProofState.Verified)
        assertTrue(Files.exists(publication.systemDirectory.resolve("classpath/roots.dat")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("global-model-cache/model.dat")))
        assertTrue(
            Files.exists(
                publication.systemDirectory.resolve("projects/target.0123abcd/cache-state.xml"),
            ),
        )
    }

    @Test
    fun `incompatible project proof retires project categories but keeps global seed`(
        @TempDir temporary: Path,
    ) {
        val fixture = seedFixture(temporary)
        val expected = fixture.projectIdentity(gradleDistribution = "8.8")
        val observed = fixture.projectIdentity(gradleDistribution = "8.7")

        val publication = service(cloner = CopyingTestCloner).seed(
            fixture.request(
                projectEvidence = SeedProjectEvidence.Comparison(expected, observed),
            ),
        ).seeded()

        assertTrue(publication.receipt.projectProofState is SeedProjectProofState.Retired)
        assertEquals(
            setOf(IndexSeedCategory.GLOBAL_VFS, IndexSeedCategory.GLOBAL_INDEXES),
            publication.receipt.categories,
        )
        assertTrue(Files.exists(publication.systemDirectory.resolve("index/symbols.dat")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("classpath")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("projects")))
    }

    @Test
    fun `source mutation or relaunch rejects and removes unpublished staging`(
        @TempDir temporary: Path,
    ) {
        val mutated = seedFixture(temporary.resolve("mutated"))
        val mutatingCloner = IndexSeedCloner { entries, target ->
            val copied = CopyingTestCloner.clone(entries, target)
            Files.writeString(mutated.sourceSystem.resolve("index/symbols.dat"), "changed")
            copied
        }

        assertEquals(
            IndexSeedFailure.SourceMutation,
            service(cloner = mutatingCloner).seed(mutated.request()).rejected(),
        )
        assertFalse(Files.exists(mutated.cacheRoot.resolve(mutated.cacheIdentity.key)))
        assertTrue(Files.list(mutated.cacheRoot).use { entries -> entries.findAny().isEmpty })

        val relaunched = seedFixture(temporary.resolve("relaunched"))
        val observations = ArrayDeque(
            listOf(
                SourceIdeQuiescence(
                    SourceIdeProcessState.STOPPED,
                    SourceIdeLockState.UNLOCKED,
                ),
                SourceIdeQuiescence(
                    SourceIdeProcessState.RUNNING,
                    SourceIdeLockState.LOCKED,
                ),
            ),
        )
        val relaunchingService = service(
            cloner = CopyingTestCloner,
            quiescenceProbe = SourceIdeQuiescenceProbe { observations.removeFirst() },
        )

        assertEquals(
            IndexSeedFailure.RunningSourceIde,
            relaunchingService.seed(relaunched.request()).rejected(),
        )
        assertFalse(Files.exists(relaunched.cacheRoot.resolve(relaunched.cacheIdentity.key)))
    }

    @Test
    fun `copy capability and failures remain finite before publication`(
        @TempDir temporary: Path,
    ) {
        val unsupported = seedFixture(temporary.resolve("unsupported"))
        var copyAttempted = false
        val recordingCloner = IndexSeedCloner { _, _ ->
            copyAttempted = true
            IndexSeedCopyResult.Copied
        }
        assertEquals(
            IndexSeedFailure.UnsupportedFilesystem,
            service(
                cloner = recordingCloner,
                filesystemProbe = IndexSeedFilesystemProbe { _, _ ->
                    IndexSeedFilesystem.UNSUPPORTED
                },
            ).seed(unsupported.request()).rejected(),
        )
        assertFalse(copyAttempted)

        val failed = seedFixture(temporary.resolve("failed"))
        val activity = mutableListOf<IndexSeedActivity>()
        assertEquals(
            IndexSeedFailure.CopyFailure,
            service(
                cloner = IndexSeedCloner { _, _ -> IndexSeedCopyResult.Rejected },
                activitySink = IndexSeedActivitySink { event ->
                    activity += event
                    IndexSeedActivityPublication.PUBLISHED
                },
            ).seed(failed.request()).rejected(),
        )
        assertEquals(
            IndexSeedActivity.Rejected(IndexSeedStage.COPY, IndexSeedFailure.CopyFailure),
            activity.last(),
        )
        assertFalse(Files.exists(failed.cacheRoot.resolve(failed.cacheIdentity.key)))
    }

    @Test
    fun `interactive seed discloses exact categories and measured size before consent`(
        @TempDir temporary: Path,
    ) {
        val fixture = seedFixture(temporary)
        var observed: IndexSeedDisclosure? = null
        val interactive = service(
            cloner = CopyingTestCloner,
            consentProvider = IndexSeedConsentProvider { disclosure ->
                observed = disclosure
                IndexSeedConsent.GRANTED
            },
        )

        assertTrue(
            interactive.seed(
                fixture.request(IndexSeedConsentRequest.INTERACTIVE),
            ) is IndexSeedExecution.Seeded,
        )
        assertEquals(
            setOf(IndexSeedCategory.GLOBAL_VFS, IndexSeedCategory.GLOBAL_INDEXES),
            observed?.categories,
        )
        assertTrue((observed?.estimatedBytes?.value ?: 0L) > 0L)

        val denied = seedFixture(temporary.resolve("denied"))
        assertEquals(
            IndexSeedFailure.ConsentAbsent,
            service(cloner = CopyingTestCloner).seed(
                denied.request(IndexSeedConsentRequest.INTERACTIVE),
            ).rejected(),
        )
        assertFalse(Files.exists(denied.cacheRoot.resolve(denied.cacheIdentity.key)))
    }

    private fun service(
        cloner: IndexSeedCloner,
        quiescenceProbe: SourceIdeQuiescenceProbe = SourceIdeQuiescenceProbe {
            SourceIdeQuiescence(
                SourceIdeProcessState.STOPPED,
                SourceIdeLockState.UNLOCKED,
            )
        },
        filesystemProbe: IndexSeedFilesystemProbe = IndexSeedFilesystemProbe { _, _ ->
            IndexSeedFilesystem.APFS
        },
        consentProvider: IndexSeedConsentProvider = RejectingIndexSeedConsentProvider,
        activitySink: IndexSeedActivitySink = IndexSeedActivitySink.Disabled,
    ): IndexSeedFilesystemService = IndexSeedFilesystemService(
        quiescenceProbe,
        filesystemProbe,
        cloner,
        consentProvider,
        activitySink,
    )

    private fun seedFixture(temporary: Path): SeedFixture {
        Files.createDirectories(temporary)
        val ideaHome = Files.createDirectory(temporary.resolve("idea-home")).toRealPath()
        val source = Files.createDirectory(temporary.resolve("source-system")).toRealPath()
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val cacheRoot = Files.createDirectory(temporary.resolve("kast-cache")).toRealPath()
        Files.writeString(source.resolve(".home"), ideaHome.toString())
        write(source, "caches/vfs.dat", "vfs")
        write(source, "index/symbols.dat", "symbols")
        write(source, "classpath/roots.dat", "roots")
        write(source, "global-model-cache/model.dat", "model")
        write(
            source,
            "projects/target.0123abcd/cache-state.xml",
            "<project><root value=\"$project\"/></project>",
        )
        write(source, "projects/target.0123abcd/project-model-cache/cache.data", "target")
        write(source, "projects/other.89abcdef/cache-state.xml", "<project/>")
        write(source, "projects/other.89abcdef/project-model-cache/cache.data", "other")
        write(source, "LocalHistory/history.dat", "history")
        write(source, "vcs-log/index.dat", "vcs")
        write(source, "log/idea.log", "log")

        val runtimeIdentity = supportedRuntime()
        val runtime = InstalledIdeRuntime(
            ideaHome,
            Files.createFile(ideaHome.resolve("java")),
            runtimeIdentity,
        )
        val semanticRuntimeId = when (
            val refinement = SemanticRuntimeId.parse("sha256:${"9".repeat(64)}")
        ) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error(refinement.failure)
        }
        val cacheIdentity = KastCacheIdentity.derive(
            project,
            runtime,
            semanticRuntimeId,
        ).derivedForSeed()
        return SeedFixture(source, project, cacheRoot, runtime, cacheIdentity)
    }

    private fun write(root: Path, relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun supportedRuntime(): IdeRuntimeIdentity {
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

private data class SeedFixture(
    val sourceSystem: Path,
    val projectRoot: Path,
    val cacheRoot: Path,
    val runtime: InstalledIdeRuntime,
    val cacheIdentity: KastCacheIdentity,
) {
    fun request(
        consent: IndexSeedConsentRequest = IndexSeedConsentRequest.PREGRANTED,
        projectEvidence: SeedProjectEvidence = SeedProjectEvidence.Absent,
    ): IndexSeedRequest = IndexSeedRequest(
        sourceSystem,
        cacheRoot,
        runtime,
        cacheIdentity,
        consent,
        projectEvidence,
    )

    fun projectIdentity(
        gradleDistribution: String = "8.8",
    ): SeedProjectIdentity = when (
        val admission = SeedProjectIdentity.admit(
            SeedProjectIdentityCandidate(
                projectRoot,
                gradleDistribution,
                "sha256:${"1".repeat(64)}",
                "sha256:${"2".repeat(64)}",
                "sha256:${"3".repeat(64)}",
                "sha256:${"4".repeat(64)}",
                "sha256:${"5".repeat(64)}",
            ),
        )
    ) {
        is SeedProjectIdentityAdmission.Admitted -> admission.identity
        is SeedProjectIdentityAdmission.Rejected -> error(admission.failure)
    }
}

private data object CopyingTestCloner : IndexSeedCloner {
    override fun clone(
        entries: List<IndexSeedCopyEntry>,
        targetSystem: Path,
    ): IndexSeedCopyResult = try {
        entries.forEach { entry ->
            val target = targetSystem.resolve(entry.relativePath)
            if (Files.isDirectory(entry.source)) {
                Files.walk(entry.source).use { paths ->
                    paths.forEach { source ->
                        val relative = entry.source.relativize(source)
                        val destination = target.resolve(relative)
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(destination)
                        } else {
                            Files.createDirectories(destination.parent)
                            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                }
            } else {
                Files.createDirectories(target.parent)
                Files.copy(entry.source, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        IndexSeedCopyResult.Copied
    } catch (_: Exception) {
        IndexSeedCopyResult.Rejected
    }
}

private fun IndexSeedExecution.seeded(): IndexSeedPublication =
    (this as IndexSeedExecution.Seeded).publication

private fun IndexSeedExecution.rejected(): IndexSeedFailure =
    (this as IndexSeedExecution.Rejected).failure

private fun KastCacheIdentityDerivation.derivedForSeed(): KastCacheIdentity =
    (this as KastCacheIdentityDerivation.Derived).identity
