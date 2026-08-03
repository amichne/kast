package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend

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
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
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
internal class KastIndexerBackendContractTestContinuation : KastIndexerBackendContractTestFixture() {
    @Test
    fun `indexed reference continuation rejects a changed source generation`() = runBlocking {
        ensureProjectReady()
        val referenceData = readAction {
            val usageFile = sampleUsageFileFixture.get()
            IndexedReferenceTestData(
                workspaceRoot = commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFilePath = sampleFile.virtualFile.path,
                declarationOffset = sampleFile.text.indexOf("greet"),
                usageFilePath = usageFile.virtualFile.path,
                usageOffset = usageFile.text.indexOf("greet(\"idea\")"),
            )
        }
        var generation = SourceIndexGeneration(1)
        val lookup = ReferenceIndexLookup { target, _, _ ->
            IndexedReferenceLookupResult.Ready(
                page = SymbolReferencePage(
                    references = listOf(
                        SymbolReferenceRow(
                            sourcePath = referenceData.usageFilePath,
                            sourceOffset = referenceData.usageOffset,
                            targetFqName = target.fqName,
                            targetPath = referenceData.declarationFilePath,
                            targetOffset = referenceData.declarationOffset,
                        ),
                    ),
                    nextOffset = NonNegativeInt(1),
                ),
                generation = generation,
            )
        }
        val backend = backend(referenceData.workspaceRoot, referenceIndexLookup = lookup)
        val position = FilePosition(referenceData.declarationFilePath, referenceData.declarationOffset)
        val first = backend.findReferences(ReferencesQuery(position, maxResults = 1))
        generation = SourceIndexGeneration(2)

        val failure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = position,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("source index changed"))
    }

    @Test
    fun `production source store mutation between indexed pages rejects continuation`() = runBlocking {
        ensureProjectReady()
        val referenceData = readAction {
            val usageFile = sampleUsageFileFixture.get()
            IndexedReferenceTestData(
                workspaceRoot = commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFilePath = sampleFile.virtualFile.path,
                declarationOffset = sampleFile.text.indexOf("greet"),
                usageFilePath = usageFile.virtualFile.path,
                usageOffset = usageFile.text.indexOf("greet(\"idea\")"),
            )
        }
        val storeRoot = Files.createTempDirectory("kast-reference-generation")
        val workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(referenceData.workspaceRoot).copy(
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(storeRoot.resolve("source-index.db")),
        )
        SqliteSourceIndexStore(workspaceIdentity).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = referenceData.declarationFilePath,
                sourceOffset = referenceData.declarationOffset,
                targetFqName = "demo.greet",
                targetPath = referenceData.declarationFilePath,
                targetOffset = referenceData.declarationOffset,
            )
            store.upsertSymbolReference(
                sourcePath = referenceData.usageFilePath,
                sourceOffset = referenceData.usageOffset,
                targetFqName = "demo.greet",
                targetPath = referenceData.declarationFilePath,
                targetOffset = referenceData.declarationOffset,
            )
            val lookup = ReferenceIndexLookup { target, offset, maxResults ->
                val generated = store.generatedReferencePageToExactSymbol(target, offset, maxResults)
                IndexedReferenceLookupResult.Ready(generated.page, generated.generation)
            }
            val backend = backend(referenceData.workspaceRoot, referenceIndexLookup = lookup)
            val position = FilePosition(referenceData.declarationFilePath, referenceData.declarationOffset)
            val first = backend.findReferences(ReferencesQuery(position, maxResults = 1))

            assertEquals(false, first.searchScope?.exhaustive)
            assertEquals(SearchScope.CandidateCoverage.COMPLETE, first.searchScope?.candidateCoverage)
            assertEquals(true, first.page?.truncated)

            store.clearReferencesFromFile(referenceData.usageFilePath)

            val failure = runCatching {
                backend.findReferences(
                    ReferencesQuery(
                        position = position,
                        maxResults = 1,
                        pageToken = requireNotNull(first.page?.nextPageToken),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(failure is ConflictException)
            assertTrue(failure?.message.orEmpty().contains("source index changed"))
        }
    }

    @Test
    fun `indexed reference pages preserve cumulative search scope evidence`() = runBlocking {
        ensureProjectReady()
        val referenceData = readAction {
            val usageFile = sampleUsageFileFixture.get()
            IndexedReferenceTestData(
                workspaceRoot = commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFilePath = sampleFile.virtualFile.path,
                declarationOffset = sampleFile.text.indexOf("greet"),
                usageFilePath = usageFile.virtualFile.path,
                usageOffset = usageFile.text.indexOf("greet(\"idea\")"),
            )
        }
        val lookup = ReferenceIndexLookup { target, offset, _ ->
            val row = if (offset.value == 0) {
                SymbolReferenceRow(
                    sourcePath = referenceData.declarationFilePath,
                    sourceOffset = referenceData.declarationOffset,
                    targetFqName = target.fqName,
                    targetPath = null,
                    targetOffset = null,
                )
            } else {
                SymbolReferenceRow(
                    sourcePath = referenceData.usageFilePath,
                    sourceOffset = referenceData.usageOffset,
                    targetFqName = target.fqName,
                    targetPath = null,
                    targetOffset = null,
                )
            }
            IndexedReferenceLookupResult.Ready(
                page = SymbolReferencePage(
                    references = listOf(row),
                    nextOffset = if (offset.value == 0) NonNegativeInt(1) else null,
                ),
                generation = SourceIndexGeneration(1),
            )
        }
        val backend = backend(referenceData.workspaceRoot, referenceIndexLookup = lookup)
        val position = FilePosition(referenceData.declarationFilePath, referenceData.declarationOffset)
        val first = backend.findReferences(ReferencesQuery(position, maxResults = 1))
        val second = backend.findReferences(
            ReferencesQuery(
                position = position,
                maxResults = 1,
                pageToken = requireNotNull(first.page?.nextPageToken),
            ),
        )

        assertEquals(1, first.searchScope?.candidateFileCount)
        assertEquals(2, second.searchScope?.candidateFileCount)
        assertEquals(2, second.searchScope?.searchedFileCount)
    }

    @Test
    fun `find references reports non exhaustive scope when fallback budget is exhausted`() = runBlocking {
        ensureProjectReady()

        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }
        var currentNanos = 0L
        val exhaustedClock = ReferenceSearchClock {
            currentNanos += 2_000_000L
            currentNanos
        }

        val result = backend(
            workspaceRoot = workspaceRoot,
            limits = contractLimits.copy(requestTimeoutMillis = 1L),
            referenceSearchClock = exhaustedClock,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        assertFalse(result.searchScope?.exhaustive ?: true)
        assertEquals(SearchScope.CandidateCoverage.PARTIAL, result.searchScope?.candidateCoverage)
        assertEquals(null, result.page)
        val evidence = result.evidence as RelationshipResultEvidence.Limited
        assertTrue(RelationshipSearchLimitation.TIMED_OUT in evidence.coverage.limitations)
        assertTrue(
            (result.searchScope?.searchedFileCount ?: Int.MAX_VALUE) <=
                (result.searchScope?.candidateFileCount ?: 0),
        )
    }

    @Test
    fun `reference continuation generation is captured inside the traversal read epoch`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                VfsUtil.saveText(
                    mainSourceRootFixture.get().virtualFile.createChildData(this, "ConcurrentReferenceUsage.kt"),
                    """
                    package demo

                    fun concurrentUses(): List<String> = listOf(greet("one"), greet("two"))
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("ConcurrentReferenceUsage.kt"))
        }
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path) to
                FilePosition(sampleFile.virtualFile.path, sampleFile.text.indexOf("greet"))
        }
        val generation = AtomicLong(1)
        val enteredReadEpoch = CountDownLatch(1)
        val releaseReadEpoch = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)
        val observer = IdeaReadEpochObserver { kind ->
            if (kind == IdeaReadEpochKind.REFERENCES && blockedOnce.compareAndSet(false, true)) {
                enteredReadEpoch.countDown()
                assertTrue(releaseReadEpoch.await(10, TimeUnit.SECONDS))
            }
        }
        val backend = backend(
            workspaceRoot = workspaceRoot,
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
            psiGeneration = generation::get,
            readEpochObserver = observer,
        )
        val firstDeferred = async(Dispatchers.Default) {
            backend.findReferences(
                ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
            )
        }
        assertTrue(enteredReadEpoch.await(10, TimeUnit.SECONDS))

        val writeStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        application.invokeLater {
            writeStarted.countDown()
            application.runWriteAction { generation.set(2) }
            writeCompleted.countDown()
        }
        assertTrue(writeStarted.await(10, TimeUnit.SECONDS))
        assertTrue(!writeCompleted.await(100, TimeUnit.MILLISECONDS))

        releaseReadEpoch.countDown()
        val first = firstDeferred.await()
        assertTrue(writeCompleted.await(10, TimeUnit.SECONDS))
        val failure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("PSI changed"))
    }

    @Test
    fun `find references for internal symbol searches declaring module dependents`() = runBlocking {
        ensureInternalVisibilityProjectReady()

        val (workspaceRoot, filePath, offset) = readAction {
            val declarationFile = internalDeclarationFileFixture.get()
            val dependentFile = internalDependentFileFixture.get()
            Triple(
                commonWorkspaceRoot(declarationFile.virtualFile.path, dependentFile.virtualFile.path),
                declarationFile.virtualFile.path,
                declarationFile.text.indexOf("internalName"),
            )
        }

        val result = backend(workspaceRoot).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        val referenceFileNames = result.references
            .map { Path.of(it.location.filePath).fileName.toString() }
            .toSet()
        assertEquals(SearchScopeKind.DEPENDENT_MODULES, result.searchScope?.scope)
        assertTrue("InternalDeclaration.kt" in referenceFileNames) {
            "Expected declaring module reference, got: $referenceFileNames"
        }
        assertTrue("InternalDependent.kt" in referenceFileNames) {
            "Expected dependent module reference, got: $referenceFileNames"
        }
    }

}
