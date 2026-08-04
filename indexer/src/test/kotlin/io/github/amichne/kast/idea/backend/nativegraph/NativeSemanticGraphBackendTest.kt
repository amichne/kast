package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.contract.query.SemanticGraphQuery
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphVisibility
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
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
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", NativeSemanticGraphSources.canonical)
    private val leftTypeFixture = sourceRootFixture.psiFileFixture("LeftType.kt", NativeSemanticGraphSources.leftType)
    private val rightTypeFixture = sourceRootFixture.psiFileFixture("RightType.kt", NativeSemanticGraphSources.rightType)
    private val leftTypeConsumerFixture =
        sourceRootFixture.psiFileFixture("LeftTypeConsumer.kt", NativeSemanticGraphSources.leftTypeConsumer)
    private val rightTypeConsumerFixture =
        sourceRootFixture.psiFileFixture("RightTypeConsumer.kt", NativeSemanticGraphSources.rightTypeConsumer)
    private val localPropertyFixture =
        sourceRootFixture.psiFileFixture("LocalProperty.kt", NativeSemanticGraphSources.localProperty)
    private val functionTypeParameterFixture =
        sourceRootFixture.psiFileFixture("FunctionTypeParameter.kt", NativeSemanticGraphSources.functionTypeParameter)
    private val genericCallableReferenceFixture =
        sourceRootFixture.psiFileFixture("GenericCallableReference.kt", NativeSemanticGraphSources.genericCallableReference)
    private val enumFixture = sourceRootFixture.psiFileFixture("Mode.kt", NativeSemanticGraphSources.enum)
    private val unresolvedCallFixture =
        sourceRootFixture.psiFileFixture("UnresolvedCall.kt", NativeSemanticGraphSources.unresolvedCall)
    private val unresolvedSupertypeFixture =
        sourceRootFixture.psiFileFixture("UnresolvedSupertype.kt", NativeSemanticGraphSources.unresolvedSupertype)
    private val unresolvedTypeFixture =
        sourceRootFixture.psiFileFixture("UnresolvedType.kt", NativeSemanticGraphSources.unresolvedType)

    @TempDir
    lateinit var storeRoot: Path

    @Test
    fun `K2 canonical facts round trip through numeric SQLite identities`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = canonicalFileFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val result = backend.reconcileSemanticGraphForTest(
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

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                backend.reconcileSemanticGraphForTest(
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

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                backend.reconcileSemanticGraphForTest(
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

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val result = backend.reconcileSemanticGraphForTest(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                assertTrue(result.symbolCount.value > 0)
            }
        }
    }

    @Test
    fun `generic callable and external fluent references do not abort semantic graph extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = genericCallableReferenceFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val result = backend.reconcileSemanticGraphForTest(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                assertEquals(listOf(SemanticGraphSourcePath.parse(sourceFile.name)), result.coverage.files.map { it.path })
            }
            val snapshot = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse(sourceFile.name)))
            val target = snapshot.symbols.single { it.name.value == "laterTarget" }
            assertTrue(snapshot.relations.any { it.kind == SemanticGraphRelationKind.CALLS && it.targetKey == target.canonicalKey })
        }
    }

    @Test
    fun `unnamed declarations do not break semantic graph extraction`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = enumFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(sourceFile.virtualFile.path).toRealPath().parent

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                val result = backend.reconcileSemanticGraphForTest(
                    SemanticGraphQuery(
                        filePaths = listOf(SemanticGraphPath.parse(sourceFile.virtualFile.path)),
                    ).parsed(),
                )
                assertTrue(result.symbolCount.value > 0)
            }
        }
    }
    @Test
    fun `semantic graph preserves valid facts and limits unresolved compiler targets`() = runBlocking {
        val project = projectFixture.get()
        val validFile = canonicalFileFixture.get()
        val files = listOf(
            unresolvedCallFixture.get(),
            unresolvedSupertypeFixture.get(),
            unresolvedTypeFixture.get(),
        )
        waitUntilIndexesAreReady(project)
        val workspaceRoot = Path.of(validFile.virtualFile.path).toRealPath().parent

        sourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceRoot,
                limits = limits(),
                semanticGraphStore = store,
                psiGeneration = { 1L },
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
                workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            ).use { backend ->
                files.forEach { file ->
                    val result = backend.reconcileSemanticGraphForTest(
                        SemanticGraphQuery(
                            filePaths = listOf(validFile, file)
                                .map { SemanticGraphPath.parse(it.virtualFile.path) },
                        ).parsed(),
                    )
                    assertTrue(result.symbolCount.value > 0)
                    assertTrue(result.edgeOccurrenceCount.value > 0)
                    val outcome = store.fileStageOutcome(file.virtualFile.path, FileIndexStage.SEMANTIC_GRAPH)
                    assertEquals(listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP), outcome?.limitations)
                    assertTrue(
                        store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse(validFile.name)))
                            .relations.isNotEmpty(),
                    )
                }
            }
        }
    }

    private fun limits(): ServerLimits =
        ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4)

    private fun sourceIndexStore(workspaceRoot: Path): SqliteSourceIndexStore =
        SqliteSourceIndexStore(
            WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).copy(
                sourceIndexDatabasePath = NormalizedPath.ofAbsolute(storeRoot.resolve("source-index.db")),
            ),
        )
}
