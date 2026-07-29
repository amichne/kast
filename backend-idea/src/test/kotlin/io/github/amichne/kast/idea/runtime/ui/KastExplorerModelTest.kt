package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastExplorerModelTest {
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
        val callee = KastSourceTarget(tempDir.resolve("Callee.kt"), 23)
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
                            layer = KastExplorerEvidenceLayer.OUTGOING,
                            title = NonBlankString("io.demo.Callee"),
                            detail = NonBlankString("CALL"),
                            navigationTarget = callee,
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
                KastExplorerEvidenceLayer.OUTGOING,
                KastExplorerEvidenceLayer.SEMANTIC_GRAPH,
            ),
            model.inspection?.sections?.map(KastExplorerSection::layer),
        )
        assertEquals(type, model.inspection?.sections?.last()?.relations?.single()?.navigationTarget)
    }

    private val tempDir: Path = Path.of("/tmp/kast-explorer-model-test").toAbsolutePath().normalize()

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
