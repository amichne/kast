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

        private const val boundarySource = """
            package demo

            fun reachBoundary(): BoundaryTarget = BoundaryTarget()
        """

        private const val boundaryTarget = """
            package demo

            class BoundaryTarget
        """
    }

    private val moduleFixture = projectFixture.moduleFixture("main")
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val canonicalFileFixture = sourceRootFixture.psiFileFixture("Canonical.kt", canonicalSource)
    private val boundarySourceFixture = sourceRootFixture.psiFileFixture("BoundarySource.kt", boundarySource)
    private val boundaryTargetFixture = sourceRootFixture.psiFileFixture("BoundaryTarget.kt", boundaryTarget)

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
    fun `scoped reads obtain boundary symbols by indexed target identity`() = runBlocking {
        val project = projectFixture.get()
        val sourceFile = boundarySourceFixture.get()
        boundaryTargetFixture.get()
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

            val snapshot = store.readSemanticGraph(listOf(SemanticGraphSourcePath.parse("BoundarySource.kt")))
            val boundary = snapshot.boundarySymbols.single { symbol -> symbol.name.value == "BoundaryTarget" }
            assertTrue(snapshot.relations.any { relation -> relation.targetKey == boundary.canonicalKey })
            assertTrue(snapshot.symbols.none { symbol -> symbol.canonicalKey == boundary.canonicalKey })
        }
    }

    private fun limits(): ServerLimits =
        ServerLimits(maxResults = 500, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4)
}
