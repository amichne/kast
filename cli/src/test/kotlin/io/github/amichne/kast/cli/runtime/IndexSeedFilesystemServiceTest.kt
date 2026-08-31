package io.github.amichne.kast.cli

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
    fun `seed publishes only the fixed global and exact project allowlist`(
        @TempDir temporary: Path,
    ) {
        val fixture = seedFixture(temporary)
        val service = service(cloner = CopyingTestCloner)

        val publication = service.seed(fixture.request()).seeded()

        assertEquals(fixture.cacheIdentity, publication.receipt.cacheIdentity)
        assertEquals(
            fixture.cacheRoot.resolve(fixture.cacheIdentity.key).resolve("system"),
            publication.systemDirectory,
        )
        assertTrue(Files.exists(publication.systemDirectory.resolve(".home")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("caches/vfs.dat")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("index/symbols.dat")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("classpath/roots.dat")))
        assertTrue(Files.exists(publication.systemDirectory.resolve("global-model-cache/model.dat")))
        assertTrue(
            Files.exists(
                publication.systemDirectory.resolve("projects/target.0123abcd/cache-state.xml"),
            ),
        )
        assertFalse(Files.exists(publication.systemDirectory.resolve("LocalHistory")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("vcs-log")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("log")))
        assertFalse(Files.exists(publication.systemDirectory.resolve("projects/other.89abcdef")))
        assertTrue(Files.exists(publication.root.resolve("seed-receipt.properties")))
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
        assertEquals(
            IndexSeedFailure.CopyFailure,
            service(
                cloner = IndexSeedCloner { _, _ -> IndexSeedCopyResult.Rejected },
            ).seed(failed.request()).rejected(),
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
        assertEquals(IndexSeedCategory.entries.toSet(), observed?.categories)
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
    ): IndexSeedFilesystemService = IndexSeedFilesystemService(
        quiescenceProbe,
        filesystemProbe,
        cloner,
        consentProvider,
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
        val cacheIdentity = KastCacheIdentity.derive(project, runtimeIdentity).derivedForSeed()
        val runtime = InstalledIdeRuntime(
            ideaHome,
            Files.createFile(ideaHome.resolve("java")),
            runtimeIdentity,
        )
        return SeedFixture(source, cacheRoot, runtime, cacheIdentity)
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
    val cacheRoot: Path,
    val runtime: InstalledIdeRuntime,
    val cacheIdentity: KastCacheIdentity,
) {
    fun request(
        consent: IndexSeedConsentRequest = IndexSeedConsentRequest.PREGRANTED,
    ): IndexSeedRequest = IndexSeedRequest(
        sourceSystem,
        cacheRoot,
        runtime,
        cacheIdentity,
        consent,
    )
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
