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
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
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
internal class KastPluginBackendContractTestCoverage : KastPluginBackendContractTestFixture() {
    @Test
    fun `relationship queries fail closed when source set coverage is excluded`() = runBlocking {
        ensureProjectReady()
        val inputs = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            val greetOffset = sampleFile.text.indexOf("greet")
            RelationshipCoverageTestInputs(
                workspaceRoot = root,
                greetPosition = FilePosition(sampleFile.virtualFile.path, greetOffset),
                greetSelector = KastExactSymbolSelector(
                    fqName = "demo.greet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = greetOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                shapeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        val excludedCoverage = RelationshipSearchCoverage.limited(
            RelationshipSearchLimitation.SOURCE_SET_EXCLUDED,
        )
        val backend = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = RelationshipCoverageAuthority { excludedCoverage },
        )

        val references = backend.findReferences(ReferencesQuery(position = inputs.greetPosition))
        val referenceEvidence = when (val evidence = references.evidence) {
            is RelationshipResultEvidence.Limited -> evidence
            is RelationshipResultEvidence.Complete,
            is RelationshipResultEvidence.Resumable,
            -> error("Expected limited reference evidence, got $evidence")
        }
        assertEquals(ResultCardinality.KnownMinimum(references.references.size), referenceEvidence.cardinality)
        assertEquals(listOf(RelationshipSearchLimitation.SOURCE_SET_EXCLUDED), referenceEvidence.coverage.limitations)

        val callers = backend.callRelations(
            KastCallersQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        )
        val implementations = backend.implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                maxResults = 4,
            ),
        )
        val hierarchy = backend.hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        )

        assertTrue(callers is CallRelationsResult.Limited)
        assertTrue(implementations is ImplementationRelationsResult.Limited)
        assertTrue(hierarchy is HierarchyRelationsResult.Limited)
    }

    @Test
    fun `persisted limited relationship outcome degrades reference adapter evidence`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, hierarchyFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                entries = listOf(
                    fileInventoryEntry(
                        workspaceRoot = workspaceRoot,
                        path = filePath,
                        lastModifiedMillis = 1,
                        contentHash = FileContentHash.parse("a".repeat(64)),
                        moduleName = ":main[main]",
                        sourceSet = "main",
                    ),
                ),
                versions = FileStageVersions.CURRENT,
            )
            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single(),
                        scannedContentHash = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                            .single()
                            .contentHash,
                        references = emptyList(),
                        declarations = emptyList(),
                        limitations = listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP),
                    ),
                ),
            )

            val result = backend(
                workspaceRoot = workspaceRoot,
                relationshipCoverageAuthority = relationshipCoverageAuthority(sourceIndexStore = store),
            ).findReferences(ReferencesQuery(position = FilePosition(filePath, offset)))

            val evidence = result.evidence as RelationshipResultEvidence.Limited
            assertTrue(RelationshipSearchLimitation.BACKEND_INCOMPLETE in evidence.coverage.limitations)
            assertFalse(result.searchScope?.exhaustive ?: true)
            assertEquals(SearchScope.CandidateCoverage.PARTIAL, result.searchScope?.candidateCoverage)
        }
    }

    @Test
    fun `relationship queries fail closed when the backend root does not match the exact selector`() = runBlocking {
        ensureProjectReady()
        val inputs = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            val greetOffset = sampleFile.text.indexOf("greet")
            RelationshipCoverageTestInputs(
                workspaceRoot = root,
                greetPosition = FilePosition(sampleFile.virtualFile.path, greetOffset),
                greetSelector = KastExactSymbolSelector(
                    fqName = "demo.notGreet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = greetOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                shapeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.NotShape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        val backend = backend(inputs.workspaceRoot)

        val callers = backend.callRelations(
            KastCallersQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        ) as CallRelationsResult.Limited
        val implementations = backend.implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                maxResults = 4,
            ),
        ) as ImplementationRelationsResult.Limited
        val hierarchy = backend.hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        ) as HierarchyRelationsResult.Limited

        listOf(callers.evidence, implementations.evidence, hierarchy.evidence).forEach { evidence ->
            assertTrue(RelationshipSearchLimitation.IDENTITY_UNPROVEN in evidence.coverage.limitations)
        }
    }

    @Test
    fun `relationship queries reassess coverage in the final commit epoch`() = runBlocking {
        ensureProjectReady()
        val inputs = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            val greetOffset = sampleFile.text.indexOf("greet")
            RelationshipCoverageTestInputs(
                workspaceRoot = root,
                greetPosition = FilePosition(sampleFile.virtualFile.path, greetOffset),
                greetSelector = KastExactSymbolSelector(
                    fqName = "demo.greet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = greetOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                shapeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        fun changingAuthority(): RelationshipCoverageAuthority {
            val assessments = AtomicInteger()
            return RelationshipCoverageAuthority {
                if (assessments.getAndIncrement() == 0) {
                    RelationshipSearchCoverage.complete()
                } else {
                    RelationshipSearchCoverage.limited(RelationshipSearchLimitation.INDEX_NOT_READY)
                }
            }
        }

        val callers = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = changingAuthority(),
        ).callRelations(
            KastCallersQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        )
        val implementations = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = changingAuthority(),
        ).implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                maxResults = 4,
            ),
        )
        val hierarchy = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = changingAuthority(),
        ).hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        )

        assertTrue(callers is CallRelationsResult.Limited)
        assertTrue(implementations is ImplementationRelationsResult.Limited)
        assertTrue(hierarchy is HierarchyRelationsResult.Limited)
    }

    @Test
    fun `capabilities read backend version from generated resource`() = runBlocking {
        ensureProjectReady()

        val expectedVersion = KastPluginBackend::class.java
            .getResource("/kast-backend-version.txt")
            ?.readText()
            ?.trim()

        assertNotNull(expectedVersion)
        assertEquals(expectedVersion, backend().capabilities().backendVersion)
    }
}
