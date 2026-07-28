package io.github.amichne.kast.idea

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
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphVisibility
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

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

        private const val genericCallableReferenceSource = """
            package demo

            data class Entry<Query, State>(val state: State)
            data class List<Element>(val size: Int) {
                fun isNotEmpty(): Boolean = size > 0
            }
            data class Pair<First, Second>(val first: First)

            fun <Query, State> stateReference(): (Entry<Query, State>) -> State = Entry<Query, State>::state
            fun sizeReference(): (List<String>) -> Int = List<String>::size
            fun nonEmptyReference(): (List<String>) -> Boolean = List<String>::isNotEmpty
            fun firstReference(): (Pair<String, String?>) -> String = Pair<String, String?>::first
        """

        private const val enumSource = """
            package demo

            enum class Mode(val value: Int) {
                VALUE(1),
            }
        """

        private const val unresolvedCallSource = """
            package demo

            import java.util.concurrent.CompletableFuture

            fun <T> generatedFluentCall(value: T): T =
                CompletableFuture.completedFuture(value).thenApply { it }.join()

            fun laterTarget(): String = "ok"

            fun resilientCall(): String {
                missingCall()
                return laterTarget()
            }
        """
        private const val unresolvedSupertypeSource = "package demo\ninterface Broken : MissingBase"
        private const val unresolvedTypeSource = "package demo\nval broken: MissingType? = null"
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", canonicalSource)
    private val leftTypeFixture = sourceRootFixture.psiFileFixture("LeftType.kt", leftType)
    private val rightTypeFixture = sourceRootFixture.psiFileFixture("RightType.kt", rightType)
    private val leftTypeConsumerFixture = sourceRootFixture.psiFileFixture("LeftTypeConsumer.kt", leftTypeConsumer)
    private val rightTypeConsumerFixture = sourceRootFixture.psiFileFixture("RightTypeConsumer.kt", rightTypeConsumer)
    private val localPropertyFixture = sourceRootFixture.psiFileFixture("LocalProperty.kt", localPropertySource)
    private val functionTypeParameterFixture =
        sourceRootFixture.psiFileFixture("FunctionTypeParameter.kt", functionTypeParameterSource)
    private val genericCallableReferenceFixture =
        sourceRootFixture.psiFileFixture("GenericCallableReference.kt", genericCallableReferenceSource)
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
    fun `generic callable references do not abort semantic graph extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = genericCallableReferenceFixture.get()
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
                assertEquals(
                    listOf(SemanticGraphSourcePath.parse(sourceFile.name)),
                    result.coverage.files.map { coverage -> coverage.path },
                )
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
    fun `one unresolved generated external call does not discard later call relations`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = unresolvedCallFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent
        val sourcePath = SemanticGraphSourcePath.parse(sourceFile.name)

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

            val snapshot = store.readSemanticGraph(listOf(sourcePath))
            val laterTarget = snapshot.symbols.single { symbol -> symbol.name.value == "laterTarget" }
            assertTrue(
                snapshot.relations.any { relation ->
                    relation.kind == SemanticGraphRelationKind.CALLS &&
                        relation.targetKey == laterTarget.canonicalKey
                },
            )
        }
    }

    @Test
    fun `semantic graph rejects unresolved compiler targets`() = runBlocking {
        val project = projectFixture.get()
        val validFile = canonicalFileFixture.get()
        val files = listOf(
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
