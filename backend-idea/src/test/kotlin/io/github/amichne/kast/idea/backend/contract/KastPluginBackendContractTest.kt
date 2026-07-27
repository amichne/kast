package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
internal class KastPluginBackendContractTest : KastPluginBackendContractTestFixture() {
    @Test
    fun `runtime status lists source module names`() = runBlocking {
        ensureProjectReady()

        val status = backend().runtimeStatus()

        assertEquals(listOf("main", "secondary"), status.sourceModuleNames)
    }

    @Test
    fun `relationship coverage is complete only for equal Gradle and IDEA inventories`() {
        ensureProjectReady()

        val coverage = relationshipCoverageAuthority().assess(
            RelationshipCoverageAuthority.FamilyCompletion.COMPLETE,
        )

        assertTrue(coverage is RelationshipSearchCoverage.Complete, coverage.toString())
    }

    @Test
    fun `relationship coverage rejects a Gradle project omitted from IDEA`() {
        ensureProjectReady()

        val coverage = relationshipCoverageAuthority { model ->
            val omitted = IdeaGradleProjectLoadBridge.GradleModuleIdentity(
                model.linkedBuildRoots().single(),
                ":omitted",
            )
            IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
                model.linkedBuildRoots(),
                model.importedModelComplete(),
                model.importedModuleIdentities() + omitted,
                model.loadedModules(),
                model.importedSourceRoots(),
                model.moduleAssociations(),
            )
        }.assess(RelationshipCoverageAuthority.FamilyCompletion.COMPLETE)

        val limited = coverage as RelationshipSearchCoverage.Limited
        assertTrue(RelationshipSearchLimitation.PROJECT_SCOPE_INCOMPLETE in limited.limitations)
    }

    @Test
    fun `relationship coverage rejects a Gradle source root omitted from IDEA`() {
        ensureProjectReady()

        val coverage = relationshipCoverageAuthority { model ->
            val materializedOmittedSourceRoot = model.linkedBuildRoots().single()
                .resolve("omitted/src/testFixtures/kotlin")
            Files.createDirectories(materializedOmittedSourceRoot)
            IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
                model.linkedBuildRoots(),
                model.importedModelComplete(),
                model.importedModuleIdentities(),
                model.loadedModules(),
                model.importedSourceRoots() + listOf(materializedOmittedSourceRoot),
                model.moduleAssociations(),
            )
        }.assess(RelationshipCoverageAuthority.FamilyCompletion.COMPLETE)

        val limited = coverage as RelationshipSearchCoverage.Limited
        assertTrue(RelationshipSearchLimitation.SOURCE_SET_SCOPE_INCOMPLETE in limited.limitations)
    }

    @Test
    fun `relationship coverage ignores declared source roots absent from the read epoch`() {
        ensureProjectReady()

        val coverage = relationshipCoverageAuthority { model ->
            val absentSourceRoot = model.linkedBuildRoots().single().resolve("absent/src/main/java")
            assertTrue(Files.notExists(absentSourceRoot))
            IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
                model.linkedBuildRoots(),
                model.importedModelComplete(),
                model.importedModuleIdentities(),
                model.loadedModules(),
                model.importedSourceRoots() + listOf(absentSourceRoot),
                model.moduleAssociations(),
            )
        }.assess(RelationshipCoverageAuthority.FamilyCompletion.COMPLETE)

        assertTrue(coverage is RelationshipSearchCoverage.Complete, coverage.toString())
    }

    @Test
    fun `relationship coverage rejects an IDEA source root omitted from Gradle inventory`() {
        ensureProjectReady()

        val coverage = relationshipCoverageAuthority { model ->
            IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
                model.linkedBuildRoots(),
                model.importedModelComplete(),
                model.importedModuleIdentities(),
                model.loadedModules(),
                model.importedSourceRoots().dropLast(1),
                model.moduleAssociations(),
            )
        }.assess(RelationshipCoverageAuthority.FamilyCompletion.COMPLETE)

        val limited = coverage as RelationshipSearchCoverage.Limited
        assertTrue(RelationshipSearchLimitation.SOURCE_SET_SCOPE_INCOMPLETE in limited.limitations)
    }

    @Test
    fun `runtime cannot report ready while compiler semantic admission is pending`() = runBlocking {
        ensureProjectReady()

        val status = backend(
            indexSemanticAdmissionStatus = {
                IdeaIndexSemanticAdmission.Status.Pending("Kotlin runtime unresolved in :main")
            },
        ).runtimeStatus()

        assertEquals(RuntimeState.INDEXING, status.state)
        assertTrue(status.healthy)
        assertTrue(status.indexing)
        assertTrue(status.message.orEmpty().contains("Kotlin runtime unresolved"))
    }

    @Test
    fun `all IntelliJ Platform products publish the shared IDEA backend identity`() = runBlocking {
        assertEquals("idea", backend().capabilities().backendName)
    }

    @Test
    fun `runtime degrades when compiler semantic admission fails`() = runBlocking {
        ensureProjectReady()

        val status = backend(
            indexSemanticAdmissionStatus = {
                IdeaIndexSemanticAdmission.Status.Failed("K2 diagnostics unavailable")
            },
        ).runtimeStatus()

        assertEquals(RuntimeState.DEGRADED, status.state)
        assertFalse(status.healthy)
        assertFalse(status.indexing)
        assertTrue(status.message.orEmpty().contains("K2 diagnostics unavailable"))
    }

    @Test
    fun `workspace files caps included files per module and reports truncation`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, hierarchyFile.virtualFile.path)
        }

        val result = backend(workspaceRoot).workspaceFiles(
            WorkspaceFilesQuery(
                moduleName = "main",
                includeFiles = true,
                maxFilesPerModule = 1,
            ),
        )

        val module = result.modules.single()
        assertEquals("main", module.name)
        assertEquals(1, module.files.size)
        assertTrue(module.fileCount > module.files.size)
        assertTrue(module.filesTruncated)
    }

    @Test
    fun `workspace files exclude project module files outside canonical workspace root`() = runBlocking {
        ensureInternalVisibilityProjectReady()
        val workspaceRoot = readAction {
            Path.of(sampleFile.virtualFile.path).parent.toAbsolutePath().normalize()
        }

        val result = backend(workspaceRoot).workspaceFiles(
            WorkspaceFilesQuery(
                includeFiles = true,
            ),
        )

        val mainModule = result.modules.single { it.name == "main" }
        val secondaryModule = result.modules.single { it.name == "secondary" }
        assertTrue(mainModule.fileCount > 0)
        assertEquals(0, secondaryModule.fileCount)
        assertTrue(result.modules.flatMap { it.files }.all { filePath -> Path.of(filePath).startsWith(workspaceRoot) })
    }

    @Test
    fun `workspace search returns content matches from project files`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, sampleUsageFileFixture.get().virtualFile.path)
        }

        val result = backend(workspaceRoot).workspaceSearch(
            WorkspaceSearchQuery(
                pattern = "greet",
            ),
        )

        assertTrue(result.matches.isNotEmpty())
        assertTrue(result.matches.any { match -> match.preview.contains("fun greet") })
        assertTrue(result.matches.all { match -> match.filePath.endsWith(".kt") })
    }

    @Test
    fun `resolve symbol includes declaration scope when requested`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            sampleFile.virtualFile.path to sampleFile.text.indexOf("greet")
        }
        val result = backend(Path.of(filePath).parent).resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = filePath,
                    offset = offset,
                ),
                includeDeclarationScope = true,
            ),
        )

        val declarationScope = result.symbol.declarationScope
        assertNotNull(declarationScope)
        assertTrue(declarationScope?.sourceText.orEmpty().contains("fun greet"))
    }

    @Test
    fun `resolve symbol includes compiler enclosing declaration identity`() = runBlocking {
        ensureProjectReady()

        val memberFile = memberFileFixture.get()
        val (filePath, offset) = readAction {
            memberFile.virtualFile.path to memberFile.text.indexOf("parse")
        }
        val result = backend(Path.of(filePath).parent).resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = filePath,
                    offset = offset,
                ),
            ),
        )

        assertEquals("demo.Parser", result.symbol.containingDeclaration)
    }

}
