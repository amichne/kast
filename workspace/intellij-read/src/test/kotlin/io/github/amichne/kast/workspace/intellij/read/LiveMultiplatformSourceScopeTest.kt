package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import java.nio.file.Path
import org.jetbrains.kotlin.config.ResourceKotlinRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import org.jetbrains.kotlin.config.TestResourceKotlinRootType
import org.jetbrains.kotlin.config.TestSourceKotlinRootType
import org.jetbrains.kotlin.idea.gradle.configuration.KotlinSourceSetData
import org.jetbrains.kotlin.idea.gradle.configuration.KotlinSourceSetInfo
import org.jetbrains.kotlin.idea.projectModel.KotlinCompilation
import org.jetbrains.kotlin.idea.projectModel.KotlinComponent
import org.jetbrains.kotlin.idea.projectModel.KotlinSourceSet
import org.jetbrains.plugins.gradle.model.DefaultExternalProject
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceDirectorySet
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceSet
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveMultiplatformSourceScopeTest {
    private val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
    private val code = Path.of("/workspace/app/src/common/kotlin")
    private val resources = Path.of("/workspace/app/src/common/resources")
    private val module = gradleModule("app.common", "/workspace", "/workspace/app")
    private val owner =
        DefaultExternalProject().apply {
            projectDir = Path.of("/workspace/app").toFile()
            path = ":app"
        }

    @Test
    fun `multiplatform roots preserve test generated and resource classifications`() {
        for ((codeType, sourceType) in
            listOf(
                SourceKotlinRootType to ExternalSystemSourceType.SOURCE,
                SourceKotlinRootType to ExternalSystemSourceType.SOURCE_GENERATED,
                TestSourceKotlinRootType to ExternalSystemSourceType.TEST,
                TestSourceKotlinRootType to ExternalSystemSourceType.TEST_GENERATED,
            )) {
            val resourceType = if (sourceType.isTest) TestResourceKotlinRootType else ResourceKotlinRootType
            val resourceSourceType =
                if (sourceType.isTest) ExternalSystemSourceType.TEST_RESOURCE else ExternalSystemSourceType.RESOURCE
            val declaration =
                DefaultExternalSourceSet().apply {
                    name = "customSources"
                    addSource(sourceType, DefaultExternalSourceDirectorySet().apply { srcDirs = setOf(code.toFile()) })
                    addSource(
                        resourceSourceType,
                        DefaultExternalSourceDirectorySet().apply { srcDirs = setOf(resources.toFile()) },
                    )
                }
            val result =
                NamedGradleModuleScopeCapture(gradleProjectIndex(root, listOf(owner)))
                    .capture(
                        listOf(module),
                        { mapOf(declaration.name to declaration) },
                        {
                            listOf(
                                sourceFolder(code, rootType = codeType, generated = sourceType.isGenerated),
                                sourceFolder(resources, rootType = resourceType),
                            )
                        },
                    )
            val scope = (result as Refinement.Refined).value
            assertEquals(
                !sourceType.isGenerated,
                scope.contains(code.resolve("Known.kt"), SymbolDiscoverySourceSets.All),
            )
            assertFalse(scope.contains(resources.resolve("NotCode.kt"), SymbolDiscoverySourceSets.All))
            assertEquals(declaration.name, scope.model.sourceRoots.single().sourceSet.value)
            assertEquals(
                if (sourceType.isTest) WorkspaceSourceRootKind.TEST else WorkspaceSourceRootKind.PRODUCTION,
                scope.model.sourceRoots.single().sourceKind,
            )
        }
    }

    @Test
    fun `cached Kotlin source sets preserve exact names and root classifications without legacy evidence`() {
        val generated = code.resolve("generated")
        for (test in listOf(false, true)) {
            val source = kotlinSource(if (test) "commonTest" else "commonMain", code, test, setOf(generated))
            val rootType = if (test) TestSourceKotlinRootType else SourceKotlinRootType
            val observed = SourceSetObservation()
            val scope =
                captureKotlin(
                    kotlinNode(source),
                    listOf(
                        sourceFolder(code, rootType = rootType),
                        sourceFolder(
                            generated,
                            rootType = rootType,
                            generated = true,
                        ),
                        sourceFolder(
                            resources,
                            rootType = if (test) TestResourceKotlinRootType else ResourceKotlinRootType,
                        ),
                    ),
                    observed,
                )
                    as Refinement.Refined
            assertEquals(setOf(source.name), scope.value.model.sourceRoots.map { it.sourceSet.value }.toSet())
            assertTrue(scope.value.contains(code.resolve("Known.kt"), SymbolDiscoverySourceSets.All))
            assertFalse(scope.value.contains(generated.resolve("Generated.kt"), SymbolDiscoverySourceSets.All))
            assertFalse(scope.value.contains(resources.resolve("NotCode.kt"), SymbolDiscoverySourceSets.All))
            assertEquals(1, observed.values[IntellijReadCounter.KOTLIN_GRADLE_SOURCE_SETS])
        }
    }

    @Test
    fun `compilation preserves every declared source set but never acquires inherited roots`() {
        val extra = code.resolveSibling("extra")
        val sources = setOf(kotlinSource("jvmMain", code), kotlinSource("jvmExtra", extra))
        val compilation =
            fixtureProxy<KotlinCompilation> { method, _ ->
                when (method) {
                    "getName" -> "main"
                    "isTestComponent" -> false
                    "getDeclaredSourceSets" -> sources
                    else -> error("Unexpected compilation observation $method")
                }
            }
        val scope =
            captureKotlin(kotlinNode(compilation), listOf(sourceFolder(code), sourceFolder(extra)))
                as Refinement.Refined
        assertEquals(setOf("jvmMain", "jvmExtra"), scope.value.model.sourceRoots.map { it.sourceSet.value }.toSet())
        assertTrue(scope.value.contains(extra.resolve("Extra.kt"), SymbolDiscoverySourceSets.All))
        assertFalse(scope.value.contains(code.resolveSibling("inherited/Known.kt"), SymbolDiscoverySourceSets.All))
    }

    @Test
    fun `cached Kotlin roots count generated overlap once and reject beyond the capture budget`() {
        val generated = code.resolve("generated")
        for (capacity in listOf(2, 3)) {
            val limits =
                (ReadLimits.resolve(
                        environment =
                            mapOf(ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE.environmentKey to "$capacity")
                    ) as Refinement.Refined)
                    .value
            val result =
                captureKotlin(
                    kotlinNode(kotlinSource("commonMain", code, generated = setOf(generated))),
                    listOf(sourceFolder(code), sourceFolder(generated, generated = true)),
                    limits = limits,
                )
            if (capacity == 2) assertEquals(Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT), result)
            else assertTrue(result is Refinement.Refined)
        }
    }

    @Test
    fun `incoherent Kotlin ownership cannot fall back to legacy source sets`() {
        for (change in
            listOf<(KotlinSourceSetInfo) -> Unit>(
                { it.moduleId = ":other:imported" },
                { it.gradleModuleId = ":other" },
                { it.isTestModule = true },
            )) {
            val node = kotlinNode(kotlinSource("commonMain", code))
            val info = node.children.single().data as KotlinSourceSetData
            change(info.sourceSetInfo)
            val observed = SourceSetObservation()
            assertEquals(
                Refinement.Rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT),
                captureKotlin(node, listOf(sourceFolder(code)), observed),
            )
            assertEquals(1, observed.values[IntellijReadCounter.KOTLIN_GRADLE_SOURCE_SETS_INCOHERENT])
        }
    }

    private fun captureKotlin(
        node: DataNode<GradleSourceSetData>,
        folders: List<com.intellij.openapi.roots.SourceFolder>,
        observation: IntellijReadObservation = IntellijReadObservation.None,
        limits: ReadLimits = ReadLimits.Default,
    ) =
        NamedGradleModuleScopeCapture(gradleProjectIndex(root, listOf(owner)), observation, limits)
            .capture(
                listOf(
                    gradleModule("not-a-source-set-name", "/workspace", "/workspace/app", externalId = ":app:imported")
                ),
                { error("KMP capture must not read the legacy projection") },
                { folders },
                { node },
            )

    private fun kotlinSource(
        name: String,
        directory: Path,
        test: Boolean = false,
        generated: Set<Path> = emptySet(),
    ): KotlinSourceSet = fixtureProxy { method, _ ->
        when (method) {
            "getName" -> name
            "isTestComponent" -> test
            "getSourceDirs" -> (setOf(directory) + generated).map { it.toFile() }.toSet()
            "getResourceDirs" -> setOf(resources.toFile())
            "getGeneratedKotlinDirs" -> generated.map { it.toFile() }.toSet()
            else -> error("Unexpected Kotlin source-set observation $method")
        }
    }

    private fun kotlinNode(component: KotlinComponent): DataNode<GradleSourceSetData> {
        val parent =
            DataNode(
                ProjectKeys.MODULE,
                ModuleData(":app", ProjectSystemId("GRADLE"), "JAVA_MODULE", "app", "/workspace/app", "/workspace/app"),
                null,
            )
        return parent
            .createChild(
                GradleSourceSetData.KEY,
                GradleSourceSetData(
                    ":app:imported",
                    "JAVA_MODULE",
                    ":app:imported",
                    "app.imported",
                    "/workspace/app",
                    "/workspace/app",
                ),
            )
            .apply {
                createChild(
                    KotlinSourceSetData.KEY,
                    KotlinSourceSetData(
                        KotlinSourceSetInfo(component).apply {
                            moduleId = ":app:imported"
                            gradleModuleId = ":app"
                            isTestModule = component.isTestComponent
                        }
                    ),
                )
            }
    }

    private class SourceSetObservation : IntellijReadObservation by IntellijReadObservation.None {
        val values = mutableMapOf<IntellijReadCounter, Int>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            values[counter] = values.getOrDefault(counter, 0) + amount
        }
    }
}
