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
internal class KastPluginBackendContractTestRelationships : KastPluginBackendContractTestFixture() {
    @Test
    fun `type hierarchy returns subtypes for interface`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            hierarchyFile.virtualFile.path to hierarchyFile.text.indexOf("Shape")
        }

        val result = backend(Path.of(filePath).parent).typeHierarchy(
            TypeHierarchyQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
            ),
        )

        assertNotNull(result.root)
        assertTrue(result.stats.totalNodes >= 1)
        val childFqNames = result.root.children.map { it.symbol.fqName }
        assertTrue(
            childFqNames.any { it.contains("Circle") },
            "Expected Circle in subtypes but got: $childFqNames",
        )
    }

    @Test
    fun `implementations returns concrete subtypes for interface`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            hierarchyFile.virtualFile.path to hierarchyFile.text.indexOf("Shape")
        }

        val result = backend(Path.of(filePath).parent).implementations(
            ImplementationsQuery(
                position = FilePosition(filePath = filePath, offset = offset),
            ),
        )

        assertEquals("demo.hierarchy.Shape", result.declaration.fqName)
        val implementationFqNames = result.implementations.map { it.fqName }
        assertTrue(
            implementationFqNames.any { it == "demo.hierarchy.Circle" },
            "Expected Circle in implementations but got: $implementationFqNames",
        )
    }

    @Test
    fun `typed relationship snapshots execute against IDEA and preserve exact anchors`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, greetSelector, shapeSelector) = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            Triple(
                root,
                KastExactSymbolSelector(
                    fqName = "demo.greet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = sampleFile.text.indexOf("greet"),
                    kind = SymbolKind.FUNCTION,
                ),
                KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        val backend = backend(workspaceRoot)

        val callers = when (val result = backend.callRelations(
            KastCallersQuery(
                workspaceRoot = workspaceRoot.toString(),
                selector = greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        )) {
            is CallRelationsResult.Available -> result
            is CallRelationsResult.Limited -> error("Expected complete caller coverage: ${result.evidence}")
        }
        val implementations = when (val result = backend.implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = workspaceRoot.toString(),
                selector = shapeSelector,
                maxResults = 4,
            ),
        )) {
            is ImplementationRelationsResult.Available -> result
            is ImplementationRelationsResult.Limited ->
                error("Expected complete implementation coverage: ${result.evidence}")
        }
        val hierarchy = when (val result = backend.hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = workspaceRoot.toString(),
                selector = shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        )) {
            is HierarchyRelationsResult.Available -> result
            is HierarchyRelationsResult.Limited -> error("Expected complete hierarchy coverage: ${result.evidence}")
        }

        assertEquals(listOf("demo.useGreeting"), callers.records.map { it.relatedSymbol.fqName })
        assertEquals(ResultCardinality.Exact(1), callers.page.cardinality)
        assertEquals(
            listOf("demo.hierarchy.Circle"),
            implementations.records.map { it.implementation.fqName },
        )
        assertEquals(ResultCardinality.Exact(1), implementations.page.cardinality)
        assertEquals(
            listOf("demo.hierarchy.Circle"),
            hierarchy.records.map { it.relatedSymbol.fqName },
        )
        assertEquals(ResultCardinality.Exact(1), hierarchy.page.cardinality)
    }

}
