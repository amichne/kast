package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.jetbrains.plugins.gradle.model.DefaultExternalProject
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceDirectorySet
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveNamedGradleSourceScopeCaptureTest {
    private val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
    private val code = Path.of("/workspace/app/src/main/kotlin")
    private val main = gradleModule("app.main", "/workspace", "/workspace/app")
    private val model =
        DefaultExternalProject().apply {
            projectDir = Path.of("/workspace/app").toFile()
            path = ":app"
        }
    private val sources =
        DefaultExternalSourceSet().apply {
            name = "main"
            addSource(
                ExternalSystemSourceType.SOURCE,
                DefaultExternalSourceDirectorySet().apply { srcDirs = setOf(code.toFile()) },
            )
        }

    @Test
    fun `selected build modules remain searchable`() {
        val result =
            capture()
                .capture(
                    listOf(main),
                    { mapOf("main" to sources) },
                    { listOf(sourceFolder(code)) },
                )
        val scope = assertInstanceOf(Refinement.Refined::class.java, result).value as NamedGradleSourceScope
        assertTrue(scope.contains(code.resolve("KnownDeclaration.kt"), SymbolDiscoverySourceSets.All))
    }

    @Test
    fun `foreign build unavailable root cannot reject selected build scope`() {
        val foreign = gradleModule("plugins.main", "/workspace/build-logic", "/workspace/build-logic/plugins")
        val inspected = mutableListOf<String>()
        val result =
            capture()
                .capture(
                    listOf(foreign, main),
                    { module ->
                        assertSame(main, module, "Foreign module must not query the selected cached model")
                        mapOf("main" to sources)
                    },
                    { module ->
                        inspected += module.name
                        if (module === foreign)
                            listOf(sourceFolder(Path.of("/workspace/build-logic/plugins/missing"), available = false))
                        else listOf(sourceFolder(code))
                    },
                )
        val scope = assertInstanceOf(Refinement.Refined::class.java, result).value as NamedGradleSourceScope
        assertEquals(listOf("app.main"), inspected)
        assertTrue(scope.contains(code.resolve("KnownDeclaration.kt"), SymbolDiscoverySourceSets.All))
    }

    @Test
    fun `selected build unavailable root still rejects`() {
        val result =
            capture()
                .capture(
                    listOf(main),
                    { mapOf("main" to sources) },
                    { listOf(sourceFolder(code, available = false)) },
                )
        assertInstanceOf(Refinement.Rejected::class.java, result)
    }

    @Test
    fun `foreign malformed resources and unmatched code never enter source observation`() {
        for (build in listOf("/workspace/build-logic", "/independent")) {
            for (badFolder in
                listOf(
                    sourceFolder(Path.of("$build/missing"), available = false, resource = true),
                    sourceFolder(Path.of("$build/unmatched")),
                    sourceFolder(Path.of("$build/unavailable"), mapping = { error("unavailable mapping") }),
                )) {
                val foreign = gradleModule("foreign.main", build, null)
                val counts = RecordingScopeObservation()
                val result =
                    capture(observation = counts)
                        .capture(
                            listOf(main, foreign),
                            { mapOf("main" to sources) },
                            { module ->
                                if (module === foreign) listOf(badFolder) else listOf(sourceFolder(code))
                            },
                        )
                val scope = assertInstanceOf(Refinement.Refined::class.java, result).value as NamedGradleSourceScope
                assertTrue(scope.contains(code.resolve("KnownDeclaration.kt"), SymbolDiscoverySourceSets.All))
                assertEquals(1, counts.values[IntellijReadCounter.SELECTED_GRADLE_MODULES])
                assertEquals(1, counts.values[IntellijReadCounter.FOREIGN_GRADLE_MODULES])
                assertEquals(1, counts.values[IntellijReadCounter.SOURCE_ROOTS])
                assertTrue(counts.failures.isEmpty())
                assertEquals(setOf("app.main"), scope.model.sourceRoots.map { it.module.value }.toSet())
            }
        }
    }

    @Test
    fun `selected unmatched and unavailable mappings retain module root and closed cause`() {
        val path = code.resolve("unmatched")
        val evidence =
            IdeSourceRootEvidence(
                BoundedModuleName.observe("app.main"),
                BoundedSourceRootIdentity.observe("file://$path"),
            )
        val cases =
            listOf(
                sourceFolder(path) to IdeRootMappingFailure.GradleOwnerMissing(evidence),
                sourceFolder(path, available = false) to IdeRootMappingFailure.SourceFolderUnavailable(evidence),
                sourceFolder(path, available = false, resource = true) to
                    IdeRootMappingFailure.SourceFolderUnavailable(evidence),
                sourceFolder(path, mapping = { error("source payload must never appear in evidence") }) to
                    IdeRootMappingFailure.SourceFolderObservationFailed(evidence),
            )
        for ((folder, expected) in cases) {
            val observed = RecordingScopeObservation()
            val result =
                capture(observation = observed)
                    .capture(
                        listOf(main),
                        { mapOf("main" to sources) },
                        { listOf(folder) },
                    )
            assertEquals(Refinement.Rejected(NamedGradleSourceScopeFailure.RootMapping(expected)), result)
            assertEquals(
                if (expected is IdeRootMappingFailure.SourceFolderObservationFailed) 1 else 0,
                observed.failures.size,
            )
            assertFalse(observed.failures.toString().contains("source payload"))
        }
    }

    @Test
    fun `composite import root cannot erase included build authority`() {
        val included =
            DefaultExternalProject().apply {
                projectDir = Path.of("/workspace/build-logic").toFile()
                path = ":"
            }
        val plugin =
            DefaultExternalProject().apply {
                projectDir = Path.of("/workspace/build-logic/plugins").toFile()
                path = ":plugins"
            }
        included.childProjects = mapOf("plugins" to plugin)
        val foreign = gradleModule("plugins.main", "/workspace", "/workspace/build-logic/plugins")
        val result =
            capture(listOf(model, included))
                .capture(
                    listOf(main, foreign),
                    { mapOf("main" to sources) },
                    { module ->
                        if (module === foreign)
                            listOf(sourceFolder(Path.of("/workspace/build-logic/plugins/missing"), available = false))
                        else listOf(sourceFolder(code))
                    },
                )
        val scope = assertInstanceOf(Refinement.Refined::class.java, result).value as NamedGradleSourceScope
        assertTrue(scope.contains(code.resolve("KnownDeclaration.kt"), SymbolDiscoverySourceSets.All))
        assertEquals(setOf("app.main"), scope.model.sourceRoots.map { it.module.value }.toSet())
    }

    @Test
    fun `unknown build metadata rejects before even unavailable folders can be inspected`() {
        val result =
            capture()
                .capture(
                    listOf(gradleModule("unknown", null, "/workspace/app")),
                    { error("No source set inspection") },
                    { error("No folder inspection") },
                )
        val failure = (result as Refinement.Rejected).failure as NamedGradleSourceScopeFailure.ModuleOwnership
        assertEquals(GradleModuleOwnershipFailure.BUILD_ROOT_UNAVAILABLE, failure.cause)
        assertEquals("unknown", failure.module.value)
    }

    private fun capture(
        projects: List<DefaultExternalProject> = listOf(model),
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ) = NamedGradleModuleScopeCapture(gradleProjectIndex(root, projects), observation)
}

private class RecordingScopeObservation : IntellijReadObservation {
    val values = mutableMapOf<IntellijReadCounter, Int>()
    val failures = mutableListOf<IntellijReadUnexpectedFailure>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        values[counter] = values.getOrDefault(counter, 0) + amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

    override fun unexpected(failure: IntellijReadUnexpectedFailure) {
        failures += failure
    }
}
