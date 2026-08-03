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
internal class KastIndexerBackendContractTestIndexPaging : KastIndexerBackendContractTestFixture() {
    @Test
    fun `indexed reference cursor fails typed when index becomes unavailable`() = runBlocking {
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
        var indexReady = true
        val lookup = ReferenceIndexLookup { target, _, _ ->
            if (indexReady) {
                IndexedReferenceLookupResult.Ready(
                    SymbolReferencePage(
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
                    generation = SourceIndexGeneration(1),
                )
            } else {
                IndexedReferenceLookupResult.NotReady
            }
        }
        val backend = backend(
            workspaceRoot = referenceData.workspaceRoot,
            referenceIndexLookup = lookup,
        )
        val position = FilePosition(
            filePath = referenceData.declarationFilePath,
            offset = referenceData.declarationOffset,
        )
        val first = backend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
        )
        indexReady = false

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
        assertTrue(failure?.message.orEmpty().contains("source index became unavailable"))
    }

    @Test
    fun `find references fallback stops at page evidence and continues without overlap`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val file = mainSourceRootFixture.get().virtualFile.createChildData(this, "HighCardinalityUsage.kt")
                VfsUtil.saveText(file, highCardinalitySource)
            }
        }
        waitUntilIndexesAreReady(project)
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("HighCardinalityUsage.kt"))
        }
        val traceFile = Files.createTempFile("kast-high-cardinality-references", ".jsonl")
        val telemetry = IdeaBackendTelemetry.create(
            IdeaTelemetryConfig(
                enabled = true,
                scopes = setOf(IdeaTelemetryScope.REFERENCES),
                detail = IdeaTelemetryDetail.BASIC,
                outputFile = traceFile,
            ),
        )
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }
        var indexReady = false
        var indexLookupCount = 0
        val changingIndexLookup = ReferenceIndexLookup { _, _, _ ->
            indexLookupCount += 1
            if (indexReady) {
                IndexedReferenceLookupResult.Ready(
                    SymbolReferencePage(references = emptyList(), nextOffset = null),
                    generation = SourceIndexGeneration(1),
                )
            } else {
                IndexedReferenceLookupResult.NotReady
            }
        }
        val traversalCloseCount = AtomicInteger()
        val backend = backend(
            workspaceRoot = workspaceRoot,
            limits = contractLimits.copy(
                requestTimeoutMillis = 60_000,
                perFileScanBudgetMillis = 30_000,
            ),
            telemetry = telemetry,
            referenceIndexLookup = changingIndexLookup,
            referenceTraversalObserver = ReferenceTraversalObserver { traversalCloseCount.incrementAndGet() },
        )

        val first = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 4,
            ),
        )
        indexReady = true
        val second = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 4,
                pageToken = requireNotNull(first.page?.nextPageToken),
            ),
        )

        assertEquals(4, first.references.size)
        assertEquals(4, second.references.size)
        assertTrue(first.cardinality is ResultCardinality.KnownMinimum)
        assertFalse(first.searchScope?.exhaustive ?: true)
        assertEquals(SearchScope.CandidateCoverage.COMPLETE, first.searchScope?.candidateCoverage)
        assertEquals(1, indexLookupCount)
        assertTrue(first.references.toSet().intersect(second.references.toSet()).isEmpty())
        val trace = Files.readString(traceFile)
        assertEquals(2, trace.windowed("\"kast.references.observedEvidenceCount\":\"5\"".length)
            .count { it == "\"kast.references.observedEvidenceCount\":\"5\"" }) {
            "Expected every reference page to stop after four results plus one lookahead:\n$trace"
        }
        assertTrue(trace.lineSequence().filter { it.contains("kast.references.pathProbeCount") }.all { line ->
            Regex(""""kast.references.pathProbeCount":"([0-9]+)"""")
                .find(line)?.groupValues?.get(1)?.toInt()?.let { it <= 64 } == true
        }) { "Candidate traversal exceeded page plus lookahead:\n$trace" }

        val replayFailure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                    maxResults = 4,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(replayFailure is ConflictException)

        val mismatchFailure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                    maxResults = 5,
                    pageToken = requireNotNull(second.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(mismatchFailure is ConflictException)
        assertEquals(1, traversalCloseCount.get())
    }

    @Test
    fun `find references fallback preserves aliased compiler identity`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val file = mainSourceRootFixture.get().virtualFile.createChildData(this, "AliasedUsage.kt")
                VfsUtil.saveText(
                    file,
                    """
                    package demo.alias

                    import demo.greet as welcome

                    fun useAlias(): String = welcome("idea")
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val aliasFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("AliasedUsage.kt"))
        }
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, aliasFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        val result = backend(
            workspaceRoot = workspaceRoot,
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 50,
            ),
        )

        assertTrue(result.references.any { reference ->
            reference.location.filePath.endsWith("AliasedUsage.kt") &&
                reference.location.startOffset == aliasFile.text.indexOf("welcome(\"idea\")")
        }) { "Expected aliased compiler reference, got: ${result.references}" }
    }

    @Test
    fun `find references fallback preserves operator convention identity`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val root = mainSourceRootFixture.get().virtualFile
                VfsUtil.saveText(
                    root.createChildData(this, "OperatorDeclaration.kt"),
                    """
                    package demo.operator

                    data class Box(val value: Int)

                    operator fun Box.plus(other: Box): Box = Box(value + other.value)
                    """.trimIndent(),
                )
                VfsUtil.saveText(
                    root.createChildData(this, "OperatorUsage.kt"),
                    """
                    package demo.operator

                    fun combine(): Box = Box(1) + Box(2)
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val declarationFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("OperatorDeclaration.kt"))
        }
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("OperatorUsage.kt"))
        }
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFile.virtualFile.path,
                declarationFile.text.indexOf("plus"),
            )
        }

        val result = backend(
            workspaceRoot = workspaceRoot,
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 50,
            ),
        )

        assertTrue(result.references.any { reference ->
            reference.location.filePath.endsWith("OperatorUsage.kt") &&
                reference.location.startOffset == usageFile.text.indexOf("+")
        }) { "Expected operator compiler reference, got: ${result.references}" }
    }

}
