package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.continuation.ContinuationClock
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.protocol.InvalidSemanticGraphCursorException
import io.github.amichne.kast.api.protocol.SemanticGraphSnapshotExpiredException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestApplication
class SemanticGraphBackendTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val source = """
            package demo

            class Box<T> {
                fun pick(value: String): String = value
                fun pick(value: Int): Int = value
            }

            class Created(val value: String)

            fun use(box: Box<String>): String = box.pick("value")
            fun create(): Created = Created("value")
        """

        private const val brokenSource = """
            package broken

            fun broken(): String = 1
        """

        private const val duplicateSource = """
            package demo

            class Box<T>
        """

        private const val boundarySource = """
            package demo

            fun reachBoundary(): BoundaryTarget = BoundaryTarget()
        """

        private const val boundaryTarget = """
            package demo

            class BoundaryTarget
        """

        private val oversizedSource = buildString {
            appendLine("package oversized")
            repeat(300) { index -> appendLine("fun declaration$index(): Int = $index") }
        }
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val sourceFileFixture = sourceRootFixture.psiFileFixture("SemanticGraph.kt", source)
    private val brokenSourceFileFixture = sourceRootFixture.psiFileFixture("Broken.kt", brokenSource)
    private val boundarySourceFileFixture = sourceRootFixture.psiFileFixture("BoundarySource.kt", boundarySource)
    private val boundaryTargetFileFixture = sourceRootFixture.psiFileFixture("BoundaryTarget.kt", boundaryTarget)
    private val oversizedSourceFileFixture = sourceRootFixture.psiFileFixture("Oversized.kt", oversizedSource)
    private val duplicateModuleFixture = projectFixture.moduleFixture("duplicate")
    private val duplicateSourceRootFixture = duplicateModuleFixture.sourceRootFixture()
    private val duplicateSourceFileFixture = duplicateSourceRootFixture.psiFileFixture("Duplicate.kt", duplicateSource)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `scoped graph returns unexpanded cross file targets`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = boundarySourceFileFixture.get()
        val targetFile = boundaryTargetFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val scoped = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                val boundary = scoped.boundarySymbols.single { symbol -> symbol.name.value == "BoundaryTarget" }

                assertEquals("BoundaryTarget.kt", boundary.path.value)
                assertTrue(scoped.symbols.none { symbol -> symbol.canonicalKey == boundary.canonicalKey })
                assertTrue(scoped.relations.any { relation -> relation.targetKey == boundary.canonicalKey })
                assertTrue(
                    scoped.relations.all { relation ->
                        relation.targetKey in scoped.symbols.map { it.canonicalKey }.toSet() ||
                            relation.targetKey in scoped.boundarySymbols.map { it.canonicalKey }.toSet()
                    },
                )

                val expanded = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(
                            SemanticGraphPath.parse(sourceFile.virtualFile.path),
                            SemanticGraphPath.parse(targetFile.virtualFile.path),
                        ),
                    ).parsed(),
                )

                assertTrue(expanded.symbols.any { symbol -> symbol.canonicalKey == boundary.canonicalKey })
                assertTrue(expanded.boundarySymbols.none { symbol -> symbol.canonicalKey == boundary.canonicalKey })
            }
        }
    }

    @Test
    fun `returns compiler diagnostics without aborting graph extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = brokenSourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val result = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )

                assertTrue(
                    result.coverage.files.single().diagnostics.any { diagnostic ->
                        diagnostic.severity == DiagnosticSeverity.ERROR
                    },
                )
            }
        }
    }

    @Test
    fun `persists duplicate fully qualified declarations from separate modules`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        val duplicateSourceFile = duplicateSourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val sourcePath = Path.of(sourceFile.virtualFile.path).toRealPath()
        val duplicatePath = Path.of(duplicateSourceFile.virtualFile.path).toRealPath()
        val workspaceRoot = generateSequence(sourcePath.parent) { it.parent }
            .first { candidate -> duplicatePath.startsWith(candidate) }

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val result = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(
                            SemanticGraphPath.parse(sourceFile.virtualFile.path),
                            SemanticGraphPath.parse(duplicateSourceFile.virtualFile.path),
                        ),
                    ).parsed(),
                )

                val boxKeys = result.symbols
                    .filter { symbol -> symbol.name.value == "Box" }
                    .map { symbol -> symbol.canonicalKey }
                assertEquals(2, boxKeys.size)
                assertEquals(2, boxKeys.distinct().size)
            }
        }
    }

    @Test
    fun `exports overload-safe compiler-resolved graph records`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                assertTrue(ReadCapability.SEMANTIC_GRAPH in backend.capabilities().readCapabilities)

                val result = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                        pageSize = PositiveInt(100),
                    ).parsed(),
                )
                val overloads = result.symbols.filter { symbol -> symbol.name.value == "pick" }
                val use = result.symbols.single { symbol -> symbol.name.value == "use" }
                val stringOverload = overloads.single { symbol ->
                    symbol.signature?.value?.contains("kotlin.String") == true
                }
                val created = result.symbols.single { symbol -> symbol.name.value == "Created" }
                val create = result.symbols.single { symbol -> symbol.name.value == "create" }

                assertEquals(2, overloads.size)
                assertNotEquals(overloads[0].canonicalKey, overloads[1].canonicalKey)
                assertTrue(
                    overloads.all { symbol ->
                        symbol.canonicalKey.value.contains(":${symbol.startOffset.value}:")
                    },
                )
                assertTrue(overloads.all { symbol -> symbol.kind == SemanticGraphSymbolKind.MEMBER_FUNCTION })
                assertTrue(
                    result.relations.any { relation ->
                        relation.kind == SemanticGraphRelationKind.CALLS &&
                            relation.sourceKey == use.canonicalKey &&
                            relation.targetKey == stringOverload.canonicalKey
                    },
                )
                assertTrue(
                    result.relations.any { relation ->
                            relation.kind == SemanticGraphRelationKind.CALLS &&
                            relation.sourceKey == create.canonicalKey &&
                            relation.targetKey == created.canonicalKey &&
                            relation.resolvedTargetKey?.value
                                ?.startsWith("constructor:SemanticGraph.kt:demo.Created.<init>") == true
                    },
                )
                assertEquals(
                    SemanticGraphFileStatus.REFRESHED,
                    result.coverage.files.single().status,
                )

                val pagedSymbols = mutableListOf<io.github.amichne.kast.api.contract.result.SemanticGraphSymbol>()
                val pagedRelations = mutableListOf<io.github.amichne.kast.api.contract.result.SemanticGraphRelation>()
                var continuation = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                        pageSize = PositiveInt(2),
                    ).parsed(),
                ).also { page ->
                    pagedSymbols += page.symbols
                    pagedRelations += page.relations
                }.nextPageToken
                while (continuation != null) {
                    continuation = backend.semanticGraph(
                        SemanticGraphQuery(
                            filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                            pageSize = PositiveInt(2),
                            continuation = continuation,
                        ).parsed(),
                    ).also { page ->
                        pagedSymbols += page.symbols
                        pagedRelations += page.relations
                    }.nextPageToken
                }
                assertEquals(result.symbols.map { it.canonicalKey }, pagedSymbols.map { it.canonicalKey })
                assertEquals(result.relations, pagedRelations)

                val snapshotPage = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                        pageSize = PositiveInt(2),
                    ).parsed(),
                )
                val snapshotToken = requireNotNull(snapshotPage.nextPageToken)
                val indexed = store.readSemanticGraph(listOf(result.coverage.files.single().path))
                val beforeGeneration = store.readGeneration()
                val afterGeneration = store.replaceSemanticGraphFiles(
                    listOf(
                        SemanticGraphFileIndexUpdate(
                            path = indexed.files.single().path,
                            contentHash = requireNotNull(indexed.files.single().contentHash),
                            status = SemanticGraphFileStatus.REFRESHED,
                            diagnostics = indexed.files.single().diagnostics,
                            configurationFingerprint = indexed.fileRecords.single().configurationFingerprint,
                            omittedExternalTargetCount = indexed.fileRecords.single().omittedExternalTargetCount,
                            dependencyContentHashes = indexed.fileRecords.single().dependencyContentHashes,
                            symbols = indexed.symbols,
                            relations = indexed.relations,
                        ),
                    ),
                )
                assertEquals(beforeGeneration.value + 1, afterGeneration.value)
                val resumedPage = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                        pageSize = PositiveInt(2),
                        continuation = snapshotToken,
                    ).parsed(),
                )
                assertEquals(snapshotPage.generation, resumedPage.generation)
            }
        }
    }

    @Test
    fun `reextracts selected files instead of retaining stale targets`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val query = SemanticGraphQuery(
                    filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    pageSize = PositiveInt(100),
                ).parsed()
                val first = backend.semanticGraph(query)
                val currentPath = first.coverage.files.single().path
                val indexed = store.readSemanticGraph(listOf(currentPath))
                val stalePath = SemanticGraphSourcePath.parse("Deleted.kt")
                val staleKey = SemanticGraphSymbolKey.parse("class:CLASS:demo.Deleted")
                val staleSymbol = first.symbols.single { symbol -> symbol.name.value == "Created" }.copy(
                    canonicalKey = staleKey,
                    name = io.github.amichne.kast.api.contract.NonBlankString("Deleted"),
                    fqName = io.github.amichne.kast.api.contract.FqName("demo.Deleted"),
                    ownerKey = null,
                    path = stalePath,
                )
                store.replaceSemanticGraphFiles(
                    updates = listOf(
                        SemanticGraphFileIndexUpdate(
                            path = currentPath,
                            contentHash = requireNotNull(indexed.files.single().contentHash),
                            status = SemanticGraphFileStatus.REFRESHED,
                            diagnostics = indexed.files.single().diagnostics,
                            configurationFingerprint = indexed.fileRecords.single().configurationFingerprint,
                            omittedExternalTargetCount = indexed.fileRecords.single().omittedExternalTargetCount,
                            dependencyContentHashes = indexed.fileRecords.single().dependencyContentHashes,
                            symbols = indexed.symbols,
                            relations = indexed.relations + indexed.relations.first().copy(targetKey = staleKey),
                        ),
                        SemanticGraphFileIndexUpdate(
                            path = stalePath,
                            contentHash = SemanticGraphSha256.parse("b".repeat(64)),
                            status = SemanticGraphFileStatus.REFRESHED,
                            diagnostics = emptyList(),
                            configurationFingerprint = null,
                            omittedExternalTargetCount = NonNegativeInt(0),
                            dependencyContentHashes = emptyMap(),
                            symbols = listOf(staleSymbol),
                            relations = emptyList(),
                        ),
                    ),
                )

                val refreshed = backend.semanticGraph(query)

                assertEquals(SemanticGraphFileStatus.REFRESHED, refreshed.coverage.files.single().status)
                assertTrue(refreshed.relations.none { relation -> relation.targetKey == staleKey })
            }
        }
    }

    @Test
    fun `unchanged semantic graph files are reused from the persistent index`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                semanticGraphConfigurationFingerprint = BuildClasspathFingerprint.parse("a".repeat(64)),
                psiGeneration = { 1L },
            ).use { backend ->
                val query = SemanticGraphQuery(
                    filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    pageSize = PositiveInt(500),
                ).parsed()
                backend.semanticGraph(query)

                val reused = backend.semanticGraph(query)

                assertEquals(SemanticGraphFileStatus.CACHED, reused.coverage.files.single().status)
            }
        }
    }

    @Test
    fun `compiler configuration change invalidates persistent semantic fragments`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val query = SemanticGraphQuery(
            filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
            pageSize = PositiveInt(500),
        ).parsed()

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                semanticGraphConfigurationFingerprint = BuildClasspathFingerprint.parse("a".repeat(64)),
                psiGeneration = { 1L },
            ).use { backend -> backend.semanticGraph(query) }

            val refreshed = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                semanticGraphConfigurationFingerprint = BuildClasspathFingerprint.parse("b".repeat(64)),
                psiGeneration = { 1L },
            ).use { backend -> backend.semanticGraph(query) }

            assertEquals(SemanticGraphFileStatus.REFRESHED, refreshed.coverage.files.single().status)
        }
    }

    @Test
    fun `source dependency changes refresh dependents while unrelated files remain cached`() = runBlocking {
        val project = projectFixture.get()
        val dependent = boundarySourceFileFixture.get()
        val target = boundaryTargetFileFixture.get()
        val unrelated = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(dependent.virtualFile.path).toRealPath().parent
        val queryPaths = listOf(dependent, target, unrelated).map { file ->
            SemanticGraphPath.parse(file.virtualFile.path)
        }
        val query = SemanticGraphQuery(
            filePaths = queryPaths,
            pageSize = PositiveInt(500),
        ).parsed()

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                semanticGraphConfigurationFingerprint = BuildClasspathFingerprint.parse("a".repeat(64)),
                psiGeneration = { 1L },
            ).use { backend ->
                backend.semanticGraph(query)
                assertEquals(
                    setOf(SemanticGraphSourcePath.parse("BoundaryTarget.kt")),
                    store.readSemanticGraph(
                        listOf(SemanticGraphSourcePath.parse("BoundarySource.kt")),
                    ).fileRecords.single().dependencyContentHashes.keys,
                )
                assertTrue(
                    backend.semanticGraph(query).coverage.files.all { file ->
                        file.status == SemanticGraphFileStatus.CACHED
                    },
                )
                val oldSnapshotPage = backend.semanticGraph(
                    SemanticGraphQuery(filePaths = queryPaths, pageSize = PositiveInt(2)).parsed(),
                )
                val oldSnapshotToken = requireNotNull(oldSnapshotPage.nextPageToken)
                val concurrentSnapshotPage = backend.semanticGraph(
                    SemanticGraphQuery(filePaths = queryPaths, pageSize = PositiveInt(2)).parsed(),
                )
                val concurrentSnapshotToken = requireNotNull(concurrentSnapshotPage.nextPageToken)
                assertNotEquals(oldSnapshotToken, concurrentSnapshotToken)

                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) {
                        (target as KtFile).declarations.single().replace(
                            KtPsiFactory(project).createClass("class BoundaryTarget(val changed: Int = 1)"),
                        )
                    }
                }
                waitUntilIndexesAreReady(project)

                val refreshed = backend.semanticGraph(query).coverage.files.associateBy { file -> file.path.value }

                assertEquals(
                    SemanticGraphFileStatus.REFRESHED,
                    refreshed.getValue("BoundarySource.kt").status,
                )
                assertEquals(
                    SemanticGraphFileStatus.REFRESHED,
                    refreshed.getValue("BoundaryTarget.kt").status,
                )
                assertEquals(
                    SemanticGraphFileStatus.CACHED,
                    refreshed.getValue("SemanticGraph.kt").status,
                )
                val oldSnapshotContinuation = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = queryPaths,
                        pageSize = PositiveInt(2),
                        continuation = oldSnapshotToken,
                    ).parsed(),
                )
                assertEquals(oldSnapshotPage.generation, oldSnapshotContinuation.generation)
                val concurrentSnapshotContinuation = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = queryPaths,
                        pageSize = PositiveInt(2),
                        continuation = concurrentSnapshotToken,
                    ).parsed(),
                )
                assertEquals(concurrentSnapshotPage.generation, concurrentSnapshotContinuation.generation)
            }
        }
    }

    @Test
    fun `oversized single file completes after a newer generation is published`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = oversizedSourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val query = SemanticGraphQuery(
                    filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    pageSize = PositiveInt(500),
                )
                val first = backend.semanticGraph(query.parsed())
                var recordCount = first.symbols.size + first.relations.size
                var continuation = requireNotNull(first.nextPageToken)
                val indexed = store.readSemanticGraph(listOf(first.coverage.files.single().path))
                store.replaceSemanticGraphFiles(
                    listOf(
                        SemanticGraphFileIndexUpdate(
                            path = indexed.files.single().path,
                            contentHash = requireNotNull(indexed.files.single().contentHash),
                            status = SemanticGraphFileStatus.REFRESHED,
                            diagnostics = indexed.files.single().diagnostics,
                            configurationFingerprint = indexed.fileRecords.single().configurationFingerprint,
                            omittedExternalTargetCount = indexed.fileRecords.single().omittedExternalTargetCount,
                            dependencyContentHashes = indexed.fileRecords.single().dependencyContentHashes,
                            symbols = indexed.symbols,
                            relations = indexed.relations,
                        ),
                    ),
                )

                while (true) {
                    val page = backend.semanticGraph(query.copy(continuation = continuation).parsed())
                    recordCount += page.symbols.size + page.relations.size
                    continuation = page.nextPageToken ?: break
                }

                assertTrue(recordCount > 500)
            }
        }
    }

    @Test
    fun `continuation cannot be consumed by a replacement backend`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val query = SemanticGraphQuery(
            filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
            pageSize = PositiveInt(2),
        )

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            val token = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                requireNotNull(backend.semanticGraph(query.parsed()).nextPageToken)
            }

            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { replacement ->
                val failure = runCatching {
                    replacement.semanticGraph(query.copy(continuation = token).parsed())
                }.exceptionOrNull()

                assertTrue(failure is InvalidSemanticGraphCursorException)
            }
        }
    }

    @Test
    fun `expired semantic graph snapshot is retryable and distinct from an invalid cursor`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = sourceFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val clock = MutableClock()
        val query = SemanticGraphQuery(
            filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
            pageSize = PositiveInt(2),
        )

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 500,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                    continuationTtlMillis = 1,
                ),
                semanticGraphStore = store,
                semanticGraphContinuationClock = clock,
                psiGeneration = { 1L },
            ).use { backend ->
                val token = requireNotNull(backend.semanticGraph(query.parsed()).nextPageToken)
                clock.nowNanos = 2_000_000

                val failure = runCatching {
                    backend.semanticGraph(query.copy(continuation = token).parsed())
                }.exceptionOrNull()

                assertTrue(failure is SemanticGraphSnapshotExpiredException)
            }
        }
    }

    private class MutableClock : ContinuationClock {
        var nowNanos: Long = 0

        override fun nowNanos(): Long = nowNanos
    }
}
