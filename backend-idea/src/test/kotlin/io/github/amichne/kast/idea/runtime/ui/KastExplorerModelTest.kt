package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.idea.diagnostics.KastBackendUiState
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class KastExplorerModelTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `tool window prioritizes exploration while retaining activity`() {
        assertEquals(
            listOf("Explore", "Activity"),
            KastToolWindowContent.entries.map(KastToolWindowContent::title),
        )
    }

    @Test
    fun `search results retain exact source navigation evidence`() {
        val model = KastExplorerModel()
        val declaration = declaration("io.demo.GraphExplorer", "GraphExplorer.kt", 42)

        model.accept(
            KastExplorerResult.SearchResults(
                listOf(KastExplorerSearchItem(declaration)),
            ),
        )

        assertEquals("GraphExplorer", model.searchItems.single().displayName)
        assertEquals(
            KastSourceTarget(Path.of(declaration.filePath), 42),
            model.searchItems.single().navigationTarget,
        )
        assertNull(model.inspection)
    }

    @Test
    fun `inspection separates indexed and semantic graph evidence`() {
        val model = KastExplorerModel()
        val selected = KastExplorerSearchItem(declaration("io.demo.GraphExplorer", "GraphExplorer.kt", 42))
        val caller = KastSourceTarget(tempDir.resolve("Caller.kt"), 17)
        val type = KastSourceTarget(tempDir.resolve("GraphNode.kt"), 11)

        model.accept(
            KastExplorerResult.Inspection(
                KastExplorerInspection(
                    selected = selected,
                    relations = listOf(
                        KastExplorerRelation(
                            layer = KastExplorerEvidenceLayer.INCOMING,
                            title = NonBlankString("io.demo.Caller"),
                            detail = NonBlankString("CALL"),
                            navigationTarget = caller,
                        ),
                        KastExplorerRelation(
                            layer = KastExplorerEvidenceLayer.SEMANTIC_GRAPH,
                            title = NonBlankString("io.demo.GraphNode"),
                            detail = NonBlankString("IMPLEMENTS"),
                            navigationTarget = type,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                KastExplorerEvidenceLayer.INCOMING,
                KastExplorerEvidenceLayer.SEMANTIC_GRAPH,
            ),
            model.inspection?.sections?.map(KastExplorerSection::layer),
        )
        assertEquals(type, model.inspection?.sections?.last()?.relations?.single()?.navigationTarget)
    }

    @Test
    fun `inspection isolates declarations that share an fq name`() {
        SqliteSourceIndexStore(tempDir).use { store ->
            store.ensureSchema()
            val first = declaration("io.demo.overloaded", "First.kt", 10)
            val second = declaration("io.demo.overloaded", "Second.kt", 40)
            store.replaceDeclarationsFromFiles(
                listOf(first.filePath to listOf(first), second.filePath to listOf(second)),
            )
            store.upsertSymbolReference(
                sourcePath = tempDir.resolve("FirstCaller.kt").toString(),
                sourceOffset = 1,
                targetFqName = first.fqName,
                targetPath = first.filePath,
                targetOffset = first.declarationOffset,
            )
            store.upsertSymbolReference(
                sourcePath = tempDir.resolve("SecondCaller.kt").toString(),
                sourceOffset = 2,
                targetFqName = second.fqName,
                targetPath = second.filePath,
                targetOffset = second.declarationOffset,
            )

            val inspection = store.explore(
                tempDir,
                KastExplorerRequest.Inspect(KastExplorerSearchItem(second)),
            ) as KastExplorerResult.Inspection

            assertEquals(
                listOf(tempDir.resolve("SecondCaller.kt")),
                inspection.value.relations.mapNotNull(KastExplorerRelation::navigationTarget)
                    .map(KastSourceTarget::filePath),
            )
        }
    }

    @Test
    fun `overview results are independent from interactive request ordering`() {
        assertTrue(
            shouldAcceptExplorerResult(
                KastExplorerRequest.Overview,
                resultSequence = 1,
                currentSequence = 2,
            ),
        )
        assertFalse(
            shouldAcceptExplorerResult(
                KastExplorerRequest.Search(NonBlankString("GraphExplorer")),
                resultSequence = 1,
                currentSequence = 2,
            ),
        )
    }

    @Test
    fun `overview refreshes once on ready without invalidating interactive requests`() {
        assertTrue(shouldRefreshExplorerOverview(KastBackendUiState.INDEXING, KastBackendUiState.READY))
        assertFalse(shouldRefreshExplorerOverview(KastBackendUiState.READY, KastBackendUiState.READY))
        assertEquals(4, nextExplorerRequestSequence(KastExplorerRequest.Overview, 4))
        assertEquals(
            5,
            nextExplorerRequestSequence(
                KastExplorerRequest.Search(NonBlankString("GraphExplorer")),
                4,
            ),
        )
    }

    private fun declaration(
        fqName: String,
        fileName: String,
        offset: Int,
    ): DeclarationRow = DeclarationRow(
        fqName = fqName,
        kind = DeclarationKind.CLASS,
        visibility = DeclarationVisibility.INTERNAL,
        filePath = tempDir.resolve(fileName).toString(),
        declarationOffset = offset,
        modulePath = ":demo",
        sourceSet = "main",
    )
}
