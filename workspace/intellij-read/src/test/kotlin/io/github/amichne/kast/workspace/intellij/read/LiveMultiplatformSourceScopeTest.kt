package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import java.nio.file.Path
import org.jetbrains.kotlin.config.ResourceKotlinRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import org.jetbrains.kotlin.config.TestResourceKotlinRootType
import org.jetbrains.kotlin.config.TestSourceKotlinRootType
import org.jetbrains.plugins.gradle.model.DefaultExternalProject
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceDirectorySet
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
