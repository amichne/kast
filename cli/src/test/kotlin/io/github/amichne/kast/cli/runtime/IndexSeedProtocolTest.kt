package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IndexSeedProtocolTest {
    @Test
    fun `runtime and cache identities retain every proven compatibility input`(
        @TempDir temporary: Path,
    ) {
        val runtimeIdentity = supportedRuntime()
        val runtime = installedRuntime(temporary.resolve("idea"), runtimeIdentity)
        val firstRoot = Files.createDirectory(temporary.resolve("first")).toRealPath()
        val secondRoot = Files.createDirectory(temporary.resolve("second")).toRealPath()

        val semanticRuntimeId = semanticRuntimeId()
        val first = KastCacheIdentity.derive(firstRoot, runtime, semanticRuntimeId).derived()
        val same = KastCacheIdentity.derive(firstRoot, runtime, semanticRuntimeId).derived()
        val second = KastCacheIdentity.derive(secondRoot, runtime, semanticRuntimeId).derived()

        assertEquals(first, same)
        assertNotEquals(first, second)
        assertEquals(firstRoot, first.canonicalProjectRoot)
        assertEquals(semanticRuntimeId, first.semanticRuntimeId)
        assertEquals(runtimeIdentity, first.runtimeIdentity)
    }

    @Test
    fun `import input digest changes require distinct cache identity`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val runtime = installedRuntime(temporary.resolve("idea"), supportedRuntime())
        val first = KastCacheIdentity.derive(root, runtime, semanticRuntimeId(), GradleImportEnvironmentIdentity.digest("first")).derived()
        val changed = KastCacheIdentity.derive(root, runtime, semanticRuntimeId(), GradleImportEnvironmentIdentity.digest("changed")).derived()
        assertNotEquals(first.key, changed.key)
    }

    @Test
    fun `physical IDEA and JBR launch authority participates in cache identity`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val identity = supportedRuntime()
        val first = installedRuntime(temporary.resolve("first-idea"), identity)
        val second = installedRuntime(temporary.resolve("second-idea"), identity)

        val firstCache = KastCacheIdentity.derive(root, first, semanticRuntimeId()).derived()
        val secondCache = KastCacheIdentity.derive(root, second, semanticRuntimeId()).derived()

        assertNotEquals(firstCache, secondCache)
        assertNotEquals(firstCache.key, secondCache.key)
    }

    @Test
    fun `runtime admission rejects unsupported IDEA and Kotlin pairs`() {
        val support = supportedPair()
        val admission = IdeRuntimeIdentity.admit(
            support,
            IdeRuntimeIdentityCandidate(
                ideaBuild = "261.1",
                kotlinPluginBuild = "261.1-IJ",
                jbrIdentity = "jbr-21.0.7-aarch64",
                kastPayloadDigest = digest('b'),
            ),
        )

        assertTrue(admission is IdeRuntimeIdentityAdmission.Rejected)
        assertTrue(
            (admission as IdeRuntimeIdentityAdmission.Rejected).failure is
                IndexSeedFailure.Incompatibility,
        )
    }

    @Test
    fun `only a stopped unlocked source can carry quiescence proof`(@TempDir temporary: Path) {
        val source = Files.createDirectory(temporary.resolve("system")).toRealPath()
        val manifest = IndexContentManifest.from(mapOf("index/foo" to digest('c'))).admitted()

        assertEquals(
            IndexSeedFailure.RunningSourceIde,
            QuiescentIdeSystem.admit(
                source,
                supportedRuntime(),
                SourceIdeProcessState.RUNNING,
                SourceIdeLockState.UNLOCKED,
                manifest,
            ).rejected(),
        )
        assertEquals(
            IndexSeedFailure.RunningSourceIde,
            QuiescentIdeSystem.admit(
                source,
                supportedRuntime(),
                SourceIdeProcessState.STOPPED,
                SourceIdeLockState.LOCKED,
                manifest,
            ).rejected(),
        )
        assertTrue(
            QuiescentIdeSystem.admit(
                source,
                supportedRuntime(),
                SourceIdeProcessState.STOPPED,
                SourceIdeLockState.UNLOCKED,
                manifest,
            ) is QuiescentIdeSystemAdmission.Admitted,
        )
    }

    @Test
    fun `manifest admits canonical IntelliJ names and rejects path aliases`() {
        val intellijEntry =
            "classpath/native-libs/Xerial SQLiteJDBC/3.51.1/sqlite-jdbc.dylib"

        assertTrue(
            IndexContentManifest.from(mapOf(intellijEntry to digest('c'))) is
                IndexContentManifestAdmission.Admitted,
        )
        listOf(
            "/index/foo",
            "../index/foo",
            "index/../foo",
            "index/./foo",
            "index//foo",
            "index/foo\u0000bar",
        ).forEach { unsafe ->
            assertTrue(
                IndexContentManifest.from(mapOf(unsafe to digest('c'))) is
                    IndexContentManifestAdmission.Rejected,
                "unsafe manifest entry was admitted: $unsafe",
            )
        }
    }

    @Test
    fun `seed planning fails closed before any copy can begin`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val sourcePath = Files.createDirectory(temporary.resolve("system")).toRealPath()
        val runtime = supportedRuntime()
        val installed = installedRuntime(temporary.resolve("idea"), runtime)
        val cache = KastCacheIdentity.derive(root, installed, semanticRuntimeId()).derived()
        val source = quiescent(sourcePath, runtime)

        assertEquals(
            IndexSeedFailure.ConsentAbsent,
            IndexSeedPlan.create(
                cache,
                source,
                IndexSeedConsent.ABSENT,
                IndexSeedFilesystem.APFS,
            ).rejected(),
        )
        assertEquals(
            IndexSeedFailure.UnsupportedFilesystem,
            IndexSeedPlan.create(
                cache,
                source,
                IndexSeedConsent.GRANTED,
                IndexSeedFilesystem.UNSUPPORTED,
            ).rejected(),
        )

        val incompatible = supportedRuntime(payload = digest('d'))
        val incompatibleSource = quiescent(sourcePath, incompatible)
        assertTrue(
            IndexSeedPlan.create(
                cache,
                incompatibleSource,
                IndexSeedConsent.GRANTED,
                IndexSeedFilesystem.APFS,
            ).rejected() is IndexSeedFailure.Incompatibility,
        )
    }

    @Test
    fun `receipt requires unchanged source and an exact clone manifest`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val sourcePath = Files.createDirectory(temporary.resolve("system")).toRealPath()
        val runtime = supportedRuntime()
        val installed = installedRuntime(temporary.resolve("idea"), runtime)
        val cache = KastCacheIdentity.derive(root, installed, semanticRuntimeId()).derived()
        val before = IndexContentManifest.from(
            mapOf("index/foo" to digest('e'), "caches/bar" to digest('f')),
        ).admitted()
        val source = QuiescentIdeSystem.admit(
            sourcePath,
            runtime,
            SourceIdeProcessState.STOPPED,
            SourceIdeLockState.UNLOCKED,
            before,
        ).admitted()
        val plan = IndexSeedPlan.create(
            cache,
            source,
            IndexSeedConsent.GRANTED,
            IndexSeedFilesystem.APFS,
        ).planned()
        val changed = IndexContentManifest.from(
            mapOf("index/foo" to digest('0'), "caches/bar" to digest('f')),
        ).admitted()

        assertEquals(
            IndexSeedFailure.SourceMutation,
            IndexSeedReceipt.complete(plan, changed, before).rejected(),
        )
        assertEquals(
            IndexSeedFailure.ValidationFailure,
            IndexSeedReceipt.complete(plan, before, changed).rejected(),
        )

        val receipt = IndexSeedReceipt.complete(plan, before, before).completed()
        assertEquals(cache, receipt.cacheIdentity)
        assertEquals(runtime, receipt.runtimeIdentity)
        assertEquals(before, receipt.contentManifest)
    }

    private fun supportedPair() = SupportedIdeRuntimePair.admit(
        ideaBuild = "262.9437.185",
        kotlinPluginBuild = "262.9437.185-IJ",
    ).admitted()

    private fun installedRuntime(
        path: Path,
        identity: IdeRuntimeIdentity,
    ): InstalledIdeRuntime {
        val home = Files.createDirectories(path).toRealPath()
        val java = Files.createDirectories(home.resolve("jbr/Contents/Home/bin"))
            .resolve("java")
        Files.writeString(java, "#!/bin/sh\nexit 0\n")
        java.toFile().setExecutable(true)
        return InstalledIdeRuntime(home, java.toRealPath(), identity)
    }

    private fun supportedRuntime(payload: String = digest('a')): IdeRuntimeIdentity =
        IdeRuntimeIdentity.admit(
            supportedPair(),
            IdeRuntimeIdentityCandidate(
                ideaBuild = "262.9437.185",
                kotlinPluginBuild = "262.9437.185-IJ",
                jbrIdentity = "jbr-21.0.7-aarch64",
                kastPayloadDigest = payload,
            ),
        ).admitted()

    private fun quiescent(path: Path, runtime: IdeRuntimeIdentity): QuiescentIdeSystem =
        QuiescentIdeSystem.admit(
            path,
            runtime,
            SourceIdeProcessState.STOPPED,
            SourceIdeLockState.UNLOCKED,
            IndexContentManifest.from(mapOf("index/foo" to digest('1'))).admitted(),
        ).admitted()

    private fun digest(character: Char): String = "sha256:${character.toString().repeat(64)}"

    private fun semanticRuntimeId(): SemanticRuntimeId = when (
        val refinement = SemanticRuntimeId.parse(digest('9'))
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> error(refinement.failure)
    }
}

private fun IdeRuntimeIdentityAdmission.admitted(): IdeRuntimeIdentity =
    (this as IdeRuntimeIdentityAdmission.Admitted).identity

private fun SupportedIdeRuntimePairAdmission.admitted(): SupportedIdeRuntimePair =
    (this as SupportedIdeRuntimePairAdmission.Admitted).pair

private fun KastCacheIdentityDerivation.derived(): KastCacheIdentity =
    (this as KastCacheIdentityDerivation.Derived).identity

private fun IndexContentManifestAdmission.admitted(): IndexContentManifest =
    (this as IndexContentManifestAdmission.Admitted).manifest

private fun QuiescentIdeSystemAdmission.admitted(): QuiescentIdeSystem =
    (this as QuiescentIdeSystemAdmission.Admitted).system

private fun QuiescentIdeSystemAdmission.rejected(): IndexSeedFailure =
    (this as QuiescentIdeSystemAdmission.Rejected).failure

private fun IndexSeedPlanning.planned(): IndexSeedPlan =
    (this as IndexSeedPlanning.Planned).plan

private fun IndexSeedPlanning.rejected(): IndexSeedFailure =
    (this as IndexSeedPlanning.Rejected).failure

private fun IndexSeedCompletion.completed(): IndexSeedReceipt =
    (this as IndexSeedCompletion.Completed).receipt

private fun IndexSeedCompletion.rejected(): IndexSeedFailure =
    (this as IndexSeedCompletion.Rejected).failure
