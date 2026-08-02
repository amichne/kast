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
internal class KastIndexerBackendContractTestDiscovery : KastIndexerBackendContractTestFixture() {
    @Test
    fun `find references includes usage site scope when requested`() = runBlocking {
        ensureProjectReady()

        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        val result = backend(workspaceRoot).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeUsageSiteScope = true,
            ),
        )

        val usageScope = result.references
            .single { reference -> reference.location.preview.contains("greet(\"idea\")") }
            .location.usageSiteScope
        assertNotNull(usageScope)
        assertTrue(usageScope?.sourceText.orEmpty().contains("fun useGreeting"))
    }

    @Test
    fun `fallback discovery resumes across many nonmatching files without heuristic filtering`() = runBlocking {
        ensureProjectReady()
        val irrelevantFiles = createIrrelevantKotlinFiles(count = 200)
        try {
            val (workspaceRoot, filePath, offset) = readAction {
                val usageFile = sampleUsageFileFixture.get()
                Triple(
                    commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                    sampleFile.virtualFile.path,
                    sampleFile.text.indexOf("greet"),
                )
            }

            val backend = backend(workspaceRoot)
            var result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                    maxResults = 4,
                ),
            )
            val references = mutableListOf<ReferenceOccurrence>()
            var pageCount = 0
            while (true) {
                pageCount += 1
                references += result.references
                val nextPageToken = result.page?.nextPageToken ?: break
                assertTrue(pageCount < 16, "Candidate discovery did not terminate: $result")
                result = backend.findReferences(
                    ReferencesQuery(
                        position = FilePosition(filePath = filePath, offset = offset),
                        includeDeclaration = false,
                        maxResults = 4,
                        pageToken = nextPageToken,
                    ),
                )
            }

            val searchScope = checkNotNull(result.searchScope)
            assertTrue(pageCount > 1)
            assertTrue(searchScope.candidateFileCount > 64)
            assertTrue(searchScope.searchedFileCount <= searchScope.candidateFileCount)
            assertTrue(references.any { reference -> reference.location.preview.contains("greet(\"idea\")") })
        } finally {
            deleteKotlinFiles(irrelevantFiles)
        }
    }

    @Test
    fun `fallback discovery checkpoints remain resumable between candidate files`() = runBlocking {
        ensureProjectReady()
        val source = """
            package demo.deepcheckpoint

            fun deepCheckpointAnchor(): Unit = Unit

            fun deepCheckpointUse(): Unit = deepCheckpointAnchor()
        """.trimIndent()
        lateinit var deepRoot: VirtualFile
        lateinit var deepFile: VirtualFile
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                deepRoot = mainSourceRootFixture.get().virtualFile.createChildDirectory(this, "DeepCheckpoint")
                var current = deepRoot
                repeat(80) { depth ->
                    current = current.createChildDirectory(this, "level${depth.toString().padStart(3, '0')}")
                }
                deepFile = current.createChildData(this, "DeepCheckpoint.kt")
                VfsUtil.saveText(deepFile, source)
            }
        }
        waitUntilIndexesAreReady(project)

        try {
            val position = FilePosition(
                filePath = deepFile.path,
                offset = source.indexOf("deepCheckpointAnchor"),
            )
            val backend = backend(Path.of(mainSourceRootFixture.get().virtualFile.path))
            var result = backend.findReferences(
                ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
            )
            assertTrue(result.references.isEmpty())
            assertTrue(result.evidence is RelationshipResultEvidence.Resumable, result.evidence.toString())
            assertNotNull(result.page?.nextPageToken)

            val references = mutableListOf<ReferenceOccurrence>()
            references += result.references
            var pageCount = 1
            while (result.page?.nextPageToken != null) {
                assertTrue(pageCount < 16, "Candidate discovery did not terminate: $result")
                result = backend.findReferences(
                    ReferencesQuery(
                        position = position,
                        includeDeclaration = false,
                        maxResults = 1,
                        pageToken = result.page?.nextPageToken,
                    ),
                )
                references += result.references
                pageCount += 1
            }

            val complete = result.evidence as RelationshipResultEvidence.Complete
            assertEquals(ResultCardinality.Exact(1), complete.cardinality)
            assertEquals(1, references.size)
            assertTrue(references.single().location.preview.contains("deepCheckpointAnchor()"))
        } finally {
            application.invokeAndWait {
                application.runWriteAction {
                    if (deepRoot.isValid) deepRoot.delete(this)
                }
            }
            waitUntilIndexesAreReady(project)
        }
    }

    @Test
    fun `find references trace includes fallback candidate and resolution spans`() = runBlocking {
        ensureProjectReady()

        val traceFile = Files.createTempFile("kast-references-trace", ".jsonl")
        val telemetry = IdeaBackendTelemetry.create(
            IdeaTelemetryConfig(
                enabled = true,
                scopes = setOf(IdeaTelemetryScope.REFERENCES),
                detail = IdeaTelemetryDetail.BASIC,
                outputFile = traceFile,
            ),
        )
        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        backend(
            workspaceRoot = workspaceRoot,
            telemetry = telemetry,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        val trace = Files.readString(traceFile)
        listOf(
            "kast.idea.findReferences.indexLookup",
            "kast.idea.findReferences.findUsagesFallback",
            "kast.idea.findReferences.candidateDiscovery",
            "kast.idea.findReferences.referenceResolution",
        ).forEach { spanName ->
            assertTrue(trace.contains("\"name\":\"$spanName\"")) {
                "Expected trace span $spanName in:\n$trace"
            }
        }
    }

    @Test
    fun `find references uses ready source index before IDEA enumeration`() = runBlocking {
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
        var lookedUpFqName: String? = null
        val referenceIndexLookup = ReferenceIndexLookup { target, offset, maxResults ->
            lookedUpFqName = target.fqName
            assertEquals(0, offset.value)
            assertEquals(100, maxResults.value)
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
                    nextOffset = null,
                ),
                generation = SourceIndexGeneration(1),
            )
        }

        val result = backend(
            workspaceRoot = referenceData.workspaceRoot,
            referenceIndexLookup = referenceIndexLookup,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(
                    filePath = referenceData.declarationFilePath,
                    offset = referenceData.declarationOffset,
                ),
                includeDeclaration = false,
                includeUsageSiteScope = true,
            ),
        )

        assertEquals("demo.greet", lookedUpFqName)
        val reference = result.references.single()
        assertEquals(referenceData.usageFilePath, reference.location.filePath)
        assertTrue(reference.location.preview.contains("greet(\"idea\")"))
        assertNotNull(reference.location.usageSiteScope)
        assertEquals(true, result.searchScope?.exhaustive)
        assertEquals(result.searchScope?.candidateFileCount, result.searchScope?.searchedFileCount)
    }

    @Test
    fun `empty initial source index page falls back to compiler reference search`() = runBlocking {
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
        val emptyReadyIndex = ReferenceIndexLookup { _, _, _ ->
            IndexedReferenceLookupResult.Ready(
                page = SymbolReferencePage(references = emptyList(), nextOffset = null),
                generation = SourceIndexGeneration(1),
            )
        }

        val result = backend(
            workspaceRoot = referenceData.workspaceRoot,
            referenceIndexLookup = emptyReadyIndex,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(
                    filePath = referenceData.declarationFilePath,
                    offset = referenceData.declarationOffset,
                ),
                includeDeclaration = false,
            ),
        )

        assertTrue(
            result.references.any { reference ->
                reference.location.filePath == referenceData.usageFilePath &&
                    reference.location.startOffset == referenceData.usageOffset
            },
        )
        assertEquals(ResultCardinality.Exact(result.references.size), result.cardinality)
    }

}
