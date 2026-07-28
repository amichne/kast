package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphGeneration
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphVisibility
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@TestApplication
class NativeSemanticGraphBackendTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private const val canonicalSource = """
            package demo

            annotation class Marker

            sealed class Parent<T> {
                open fun inherited(value: T): T = value
            }

            class Box<T> @Marker constructor(val value: T) : Parent<T>() where T : Any {
                @Marker
                var label: String = "label"

                override fun inherited(value: T): T = value
                fun pick(value: String): String = value
                fun pick(value: Int): Int = value
            }

            class Constructed {
                constructor(value: String)
                constructor(value: Int)
            }

            fun construct(): Constructed = Constructed(1)
        """

        private const val boundarySource = """
            package demo

            fun reachBoundary(): BoundaryTarget = BoundaryTarget()
        """

        private const val boundaryTarget = """
            package demo

            class BoundaryTarget
        """

        private const val leftType = """
            package left

            class Foo
        """

        private const val rightType = """
            package right

            class Foo
        """

        private const val leftTypeConsumer = """
            package consumer

            import left.Foo

            val leftFoo: Foo? = null
        """

        private const val rightTypeConsumer = """
            package consumer

            import right.Foo

            val rightFoo: Foo? = null
        """

        private const val localPropertySource = """
            package demo

            fun useLocalProperty(): Int {
                val localValue = 1
                return localValue
            }
        """

        private const val functionTypeParameterSource = """
            package demo

            typealias Resolver = (workspaceRoot: String) -> String
        """

        private const val enumSource = """
            package demo

            enum class Mode(val value: Int) {
                VALUE(1),
            }
        """

        private const val unresolvedCallSource = "package demo\nfun brokenCall() = missingCall()"
        private const val unresolvedSupertypeSource = "package demo\ninterface Broken : MissingBase"
        private const val unresolvedTypeSource = "package demo\nval broken: MissingType? = null"
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", canonicalSource)
    private val boundarySourceFixture = sourceRootFixture.psiFileFixture("BoundarySource.kt", boundarySource)
    private val boundaryTargetFixture = sourceRootFixture.psiFileFixture("BoundaryTarget.kt", boundaryTarget)
    private val leftTypeFixture = sourceRootFixture.psiFileFixture("LeftType.kt", leftType)
    private val rightTypeFixture = sourceRootFixture.psiFileFixture("RightType.kt", rightType)
    private val leftTypeConsumerFixture = sourceRootFixture.psiFileFixture("LeftTypeConsumer.kt", leftTypeConsumer)
    private val rightTypeConsumerFixture = sourceRootFixture.psiFileFixture("RightTypeConsumer.kt", rightTypeConsumer)
    private val localPropertyFixture = sourceRootFixture.psiFileFixture("LocalProperty.kt", localPropertySource)
    private val functionTypeParameterFixture =
        sourceRootFixture.psiFileFixture("FunctionTypeParameter.kt", functionTypeParameterSource)
    private val enumFixture = sourceRootFixture.psiFileFixture("Mode.kt", enumSource)
    private val unresolvedCallFixture = sourceRootFixture.psiFileFixture("UnresolvedCall.kt", unresolvedCallSource)
    private val unresolvedSupertypeFixture =
        sourceRootFixture.psiFileFixture("UnresolvedSupertype.kt", unresolvedSupertypeSource)
    private val unresolvedTypeFixture = sourceRootFixture.psiFileFixture("UnresolvedType.kt", unresolvedTypeSource)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `K2 canonical facts round trip through numeric SQLite identities`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val result = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                assertTrue(result.symbolCount.value > 0)
                assertTrue(result.edgeOccurrenceCount.value > 0)
                assertTrue(result.coverage.omittedExternalTargetCount.value > 0)
            }

            val snapshot = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("Canonical.kt")))
            val kinds = snapshot.symbols.mapTo(mutableSetOf(), { symbol -> symbol.kind })
            assertTrue(SemanticGraphSymbolKind.CONSTRUCTOR in kinds)
            assertTrue(SemanticGraphSymbolKind.PROPERTY in kinds)
            assertTrue(SemanticGraphSymbolKind.GETTER in kinds)
            assertTrue(SemanticGraphSymbolKind.SETTER in kinds)
            assertTrue(SemanticGraphSymbolKind.VALUE_PARAMETER in kinds)
            assertTrue(SemanticGraphSymbolKind.TYPE_PARAMETER in kinds)
            assertEquals(2, snapshot.symbols.count { symbol -> symbol.name.value == "pick" })
            assertTrue(snapshot.symbols.any { symbol -> symbol.annotations.any { it.value == "demo.Marker" } })
            assertTrue(snapshot.symbols.any { symbol -> symbol.declaredTypeKey != null })
            assertTrue(snapshot.relations.any { relation -> relation.kind == SemanticGraphRelationKind.INHERITS })
            assertTrue(snapshot.relations.any { relation -> relation.kind == SemanticGraphRelationKind.OVERRIDES })
            assertTrue(snapshot.relations.any { relation -> relation.kind == SemanticGraphRelationKind.SEALED_MEMBER })
            val constructorKeys = snapshot.symbols
                .filter { symbol -> symbol.kind == SemanticGraphSymbolKind.CONSTRUCTOR }
                .mapTo(mutableSetOf()) { symbol -> symbol.canonicalKey }
            assertTrue(
                snapshot.relations.any { relation ->
                    relation.kind == SemanticGraphRelationKind.CALLS &&
                        relation.resolvedTargetKey in constructorKeys
                },
            )
        }
    }

    @Test
    fun `source scope excludes unselected targets and widens additively`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = boundarySourceFixture.get()
        val targetFile = boundaryTargetFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)
        val targetPath = SemanticGraphPath.parse(targetFile.virtualFile.path)
        fun query(path: SemanticGraphPath) = SemanticGraphQuery(filePaths = listOf(path)).parsed()
        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val result = backend.semanticGraph(query(sourcePath))
                assertTrue(result.coverage.omittedExternalTargetCount.value > 0)
                val excluded = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("BoundarySource.kt")))
                assertTrue(excluded.boundarySymbols.none { symbol -> symbol.name.value == "BoundaryTarget" })
                assertTrue(excluded.relations.none { relation -> relation.targetKey.value.contains("BoundaryTarget") })
                backend.semanticGraph(query(targetPath))
                backend.semanticGraph(query(sourcePath))
            }
            val snapshot = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("BoundarySource.kt")))
            val boundary = snapshot.boundarySymbols.single { symbol -> symbol.name.value == "BoundaryTarget" }
            assertTrue(snapshot.relations.any { relation -> relation.targetKey == boundary.canonicalKey })
        }
    }

    @Test
    fun `multi file refresh releases IDEA read access between files`() = runBlocking {
        val project = projectFixture.get()
        val files = listOf(canonicalFileFixture.get(), boundaryTargetFixture.get())
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(files.first().virtualFile.path).toRealPath().parent
        val firstReadEntered = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val readCount = AtomicInteger()
        val psiGeneration = AtomicLong(1)
        val secondReadObservedCompletedWrite = AtomicBoolean(false)
        val observer = IdeaReadEpochObserver { kind ->
            if (kind != IdeaReadEpochKind.SEMANTIC_GRAPH) return@IdeaReadEpochObserver
            when (readCount.incrementAndGet()) {
                1 -> {
                    firstReadEntered.countDown()
                    assertTrue(releaseFirstRead.await(10, TimeUnit.SECONDS))
                }
                2 -> secondReadObservedCompletedWrite.set(writeCompleted.count == 0L)
            }
        }

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = psiGeneration::get,
                readEpochObserver = observer,
            ).use { backend ->
                supervisorScope {
                    val refresh = async(Dispatchers.Default) {
                        backend.semanticGraph(
                            SemanticGraphQuery(
                                filePaths = files.map { file -> SemanticGraphPath.parse(file.virtualFile.path) },
                            ).parsed(),
                        )
                    }
                    assertTrue(firstReadEntered.await(10, TimeUnit.SECONDS))

                    val application = ApplicationManager.getApplication()
                    application.invokeLater {
                        writeStarted.countDown()
                        application.runWriteAction {
                            psiGeneration.incrementAndGet()
                            writeCompleted.countDown()
                        }
                    }
                    assertTrue(writeStarted.await(10, TimeUnit.SECONDS))
                    assertFalse(writeCompleted.await(100, TimeUnit.MILLISECONDS))

                    releaseFirstRead.countDown()
                    val failure = runCatching { refresh.await() }.exceptionOrNull()
                    assertTrue(writeCompleted.await(10, TimeUnit.SECONDS))
                    assertTrue(failure is ConflictException, "expected PSI generation conflict, got $failure")
                    assertTrue(store.semanticGraphSourcePaths().isEmpty())
                }
            }
        }

        assertEquals(files.size, readCount.get())
        assertTrue(
            secondReadObservedCompletedWrite.get(),
            "A pending IDEA write action must complete before the next semantic graph file read",
        )
    }

    @Test
    fun `cancelling multi file refresh leaves the committed graph unchanged`() = runBlocking {
        val project = projectFixture.get()
        val files = listOf(canonicalFileFixture.get(), boundaryTargetFixture.get())
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(files.first().virtualFile.path).toRealPath().parent
        val firstReadEntered = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val readCount = AtomicInteger()
        val observer = IdeaReadEpochObserver { kind ->
            if (kind == IdeaReadEpochKind.SEMANTIC_GRAPH && readCount.incrementAndGet() == 1) {
                firstReadEntered.countDown()
                assertTrue(releaseFirstRead.await(10, TimeUnit.SECONDS))
            }
        }

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                readEpochObserver = observer,
            ).use { backend ->
                val refresh = async(Dispatchers.Default) {
                    backend.semanticGraph(
                        SemanticGraphQuery(
                            filePaths = files.map { file -> SemanticGraphPath.parse(file.virtualFile.path) },
                        ).parsed(),
                    )
                }
                assertTrue(firstReadEntered.await(10, TimeUnit.SECONDS))

                refresh.cancel()
                releaseFirstRead.countDown()
                val failure = runCatching { refresh.await() }.exceptionOrNull()

                assertTrue(failure is CancellationException, "expected cancellation, got $failure")
                assertEquals(1, readCount.get())
                assertTrue(store.semanticGraphSourcePaths().isEmpty())
            }
        }
    }

    @Test
    fun `concurrent removal prevents an older refresh from resurrecting cached nodes`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val seeded = backend.semanticGraph(SemanticGraphQuery(filePaths = listOf(sourcePath)).parsed())
                assertTrue(seeded.symbolCount.value > 0)
            }
            val seededGeneration = store.readGeneration()

            val refreshReadEntered = CountDownLatch(1)
            val releaseRefreshRead = CountDownLatch(1)
            val observer = IdeaReadEpochObserver { kind ->
                if (kind == IdeaReadEpochKind.SEMANTIC_GRAPH) {
                    refreshReadEntered.countDown()
                    assertTrue(releaseRefreshRead.await(10, TimeUnit.SECONDS))
                }
            }
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                readEpochObserver = observer,
            ).use { backend ->
                supervisorScope {
                    val refresh = async(Dispatchers.Default) {
                        backend.semanticGraph(
                            SemanticGraphQuery(
                                filePaths = listOf(sourcePath),
                                expectedGeneration = SemanticGraphGeneration(seededGeneration.value),
                            ).parsed(),
                        )
                    }
                    assertTrue(refreshReadEntered.await(10, TimeUnit.SECONDS))
                    val removal = async(Dispatchers.Default) {
                        backend.semanticGraph(
                            SemanticGraphQuery(
                                filePaths = emptyList(),
                                removedFilePaths = listOf(sourcePath),
                                expectedGeneration = SemanticGraphGeneration(seededGeneration.value),
                            ).parsed(),
                        )
                    }

                    withTimeout(10_000) { removal.await() }
                    releaseRefreshRead.countDown()
                    val refreshFailure = runCatching { refresh.await() }.exceptionOrNull()

                    assertTrue(refreshFailure is ConflictException, "expected generation conflict, got $refreshFailure")
                    assertTrue(refreshFailure?.message.orEmpty().contains("retry"))
                    assertTrue(store.semanticGraphSourcePaths().isEmpty())
                }
            }
        }
    }

    @Test
    fun `removal only refresh rejects a changed PSI generation without deleting cached nodes`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                backend.semanticGraph(SemanticGraphQuery(filePaths = listOf(sourcePath)).parsed())
            }
            val generation = store.readGeneration()
            val psiGeneration = AtomicLong()

            val failure = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = psiGeneration::incrementAndGet,
            ).use { backend ->
                runCatching {
                    backend.semanticGraph(
                        SemanticGraphQuery(
                            filePaths = emptyList(),
                            removedFilePaths = listOf(sourcePath),
                            expectedGeneration = SemanticGraphGeneration(generation.value),
                        ).parsed(),
                    )
                }.exceptionOrNull()
            }

            assertTrue(failure is ConflictException, "expected PSI generation conflict, got $failure")
            assertEquals(generation, store.readGeneration())
            assertEquals(setOf(SemanticGraphSourcePath.parse(sourceFile.name)), store.semanticGraphSourcePaths())
        }
    }

    @Test
    fun `stale expected generation rejects refresh before IDEA extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphPath.parse(sourceFile.virtualFile.path)

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            val seededGeneration = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                backend.semanticGraph(SemanticGraphQuery(filePaths = listOf(sourcePath)).parsed()).generation
            }
            store.replaceSemanticGraphFiles(
                updates = emptyList(),
                removedPaths = listOf(SemanticGraphSourcePath.parse(sourceFile.name)),
            )
            val readCount = AtomicInteger()
            val observer = IdeaReadEpochObserver { kind ->
                if (kind == IdeaReadEpochKind.SEMANTIC_GRAPH) readCount.incrementAndGet()
            }

            val failure = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                readEpochObserver = observer,
            ).use { backend ->
                runCatching {
                    backend.semanticGraph(
                        SemanticGraphQuery(
                            filePaths = listOf(sourcePath),
                            expectedGeneration = seededGeneration,
                        ).parsed(),
                    )
                }.exceptionOrNull()
            }

            assertTrue(failure is ConflictException, "expected generation conflict, got $failure")
            assertEquals(0, readCount.get())
            assertTrue(store.semanticGraphSourcePaths().isEmpty())
        }
    }

    @Test
    fun `resolved classifiers distinguish identical short type names`() = runBlocking {
        val project = projectFixture.get()
        val files = listOf(
            leftTypeFixture.get(),
            rightTypeFixture.get(),
            leftTypeConsumerFixture.get(),
            rightTypeConsumerFixture.get(),
        )
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(files.first().virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = files.map { file -> SemanticGraphPath.parse(file.virtualFile.path) },
                    ).parsed(),
                )
            }

            val snapshot = store.readSemanticGraph(
                listOf(
                    SemanticGraphSourcePath.parse("LeftTypeConsumer.kt"),
                    SemanticGraphSourcePath.parse("RightTypeConsumer.kt"),
                ),
            )
            val leftKey = snapshot.symbols.single { symbol -> symbol.name.value == "leftFoo" }.declaredTypeKey
            val rightKey = snapshot.symbols.single { symbol -> symbol.name.value == "rightFoo" }.declaredTypeKey
            assertTrue(leftKey != null && rightKey != null && leftKey != rightKey)
        }
    }

    @Test
    fun `local properties retain local visibility`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = localPropertyFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
            }

            val snapshot = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("LocalProperty.kt")))
            assertEquals(
                SemanticGraphVisibility.LOCAL,
                snapshot.symbols.single { symbol -> symbol.name.value == "localValue" }.visibility,
            )
        }
    }

    @Test
    fun `function type parameters do not abort semantic graph extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = functionTypeParameterFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val result = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                assertTrue(result.symbolCount.value > 0)
            }
        }
    }

    @Test
    fun `unnamed declarations do not break semantic graph extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = enumFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                val result = backend.semanticGraph(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                assertTrue(result.symbolCount.value > 0)
            }
        }
    }

    @Test
    fun `semantic graph rejects unresolved compiler targets`() = runBlocking {
        val project = projectFixture.get()
        val validFile = canonicalFileFixture.get()
        val files = listOf(
            unresolvedCallFixture.get(),
            unresolvedSupertypeFixture.get(),
            unresolvedTypeFixture.get(),
        )
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(validFile.virtualFile.path).toRealPath().parent

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            KastPluginBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
            ).use { backend ->
                files.forEach { file ->
                    val failure = runCatching {
                        backend.semanticGraph(
                            SemanticGraphQuery(
                                filePaths = listOf(validFile, file)
                                    .map { SemanticGraphPath.parse(it.virtualFile.path) },
                            ).parsed(),
                        )
                    }.exceptionOrNull()
                    assertTrue(failure is ValidationException, "${file.name}: $failure")
                    val message = failure?.message.orEmpty()
                    assertTrue(message.contains("${file.name}:"), message)
                    assertTrue(message.contains("Fix Kotlin diagnostics"), message)
                }
            }
            assertTrue(
                store.readSemanticGraph((files + validFile).map { SemanticGraphSourcePath.parse(it.name) }).files.isEmpty(),
            )
        }
    }

    private fun limits(): ServerLimits =
        ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4)
}
