package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.idea.diagnostics.KastBackendUiState
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
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
    fun `current symbol preserves the exact caret declaration offset`() {
        val persisted = KastExplorerSearchItem(
            declaration("io.demo.overloaded", "Overloads.kt", 10),
        )
        val current = KastCurrentSymbol(
            fqName = FqName("io.demo.overloaded"),
            navigationTarget = KastSourceTarget(tempDir.resolve("Overloads.kt"), 40),
        )

        val selected = preferExactCurrentSymbol(listOf(persisted), current).single()

        assertEquals(current.navigationTarget, selected.navigationTarget)
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
    fun `empty search clears the previous inspection`() {
        val model = KastExplorerModel()
        val selected = KastExplorerSearchItem(declaration("io.demo.GraphExplorer", "GraphExplorer.kt", 42))
        model.accept(
            KastExplorerResult.Inspection(
                KastExplorerInspection(selected, emptyList()),
            ),
        )

        model.accept(KastExplorerResult.SearchResults(emptyList()))

        assertTrue(model.searchItems.isEmpty())
        assertNull(model.inspection)
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
    fun `semantic graph matches the name offset inside the declaration range`() {
        val sourcePath = SemanticGraphSourcePath.parse("Example.kt")
        val selected = semanticSymbol(
            key = "class:Example",
            name = "Example",
            fqName = "io.demo.Example",
            path = sourcePath,
            startOffset = 0,
            endOffset = 20,
        )
        val target = semanticSymbol(
            key = "function:target",
            name = "target",
            fqName = "io.demo.target",
            path = sourcePath,
            startOffset = 22,
            endOffset = 40,
        )
        val declaration = declaration("io.demo.Example", "Example.kt", 6)

        SqliteSourceIndexStore(tempDir).use { store ->
            store.ensureSchema()
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(
                        path = sourcePath,
                        symbols = listOf(selected, target),
                        relations = listOf(semanticRelation(selected, target)),
                    ),
                ),
            )

            val inspection = store.explore(
                tempDir,
                KastExplorerRequest.Inspect(KastExplorerSearchItem(declaration)),
            ) as KastExplorerResult.Inspection

            assertEquals(
                listOf("io.demo.target"),
                inspection.value.relations.map { relation -> relation.title.value },
            )
        }
    }

    @Test
    fun `semantic graph falls back to the projected constructor target`() {
        val sourcePath = SemanticGraphSourcePath.parse("Factory.kt")
        val targetPath = SemanticGraphSourcePath.parse("Target.kt")
        val selected = semanticSymbol(
            key = "function:factory",
            name = "factory",
            fqName = "io.demo.factory",
            path = sourcePath,
            startOffset = 0,
            endOffset = 20,
        )
        val projectedTarget = semanticSymbol(
            key = "class:Target",
            name = "Target",
            fqName = "io.demo.Target",
            path = targetPath,
            startOffset = 0,
            endOffset = 20,
        )
        val resolvedConstructor = semanticSymbol(
            key = "constructor:Target",
            name = "Target",
            fqName = "io.demo.Target",
            path = targetPath,
            startOffset = 10,
            endOffset = 18,
        )

        SqliteSourceIndexStore(tempDir).use { store ->
            store.ensureSchema()
            store.replaceSemanticGraphFiles(
                listOf(
                    semanticUpdate(
                        path = sourcePath,
                        symbols = listOf(selected),
                        boundarySymbols = listOf(projectedTarget),
                        relations = listOf(
                            semanticRelation(
                                selected,
                                projectedTarget,
                                resolvedTargetKey = resolvedConstructor.canonicalKey,
                            ),
                        ),
                    ),
                    semanticUpdate(
                        path = targetPath,
                        symbols = listOf(projectedTarget, resolvedConstructor),
                        relations = emptyList(),
                    ),
                ),
            )

            val inspection = store.explore(
                tempDir,
                KastExplorerRequest.Inspect(
                    KastExplorerSearchItem(declaration("io.demo.factory", "Factory.kt", 0)),
                ),
            ) as KastExplorerResult.Inspection

            assertEquals(
                listOf("io.demo.Target"),
                inspection.value.relations.map { relation -> relation.title.value },
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

    private fun semanticSymbol(
        key: String,
        name: String,
        fqName: String,
        path: SemanticGraphSourcePath,
        startOffset: Int,
        endOffset: Int,
    ): SemanticGraphSymbol = SemanticGraphSymbol(
        canonicalKey = SemanticGraphSymbolKey.parse(key),
        kind = SemanticGraphSymbolKind.FUNCTION,
        name = NonBlankString(name),
        fqName = FqName(fqName),
        path = path,
        startOffset = ByteOffset(startOffset),
        endOffset = ByteOffset(endOffset),
        line = LineNumber(1),
    )

    private fun semanticRelation(
        source: SemanticGraphSymbol,
        target: SemanticGraphSymbol,
        resolvedTargetKey: SemanticGraphSymbolKey? = null,
    ): SemanticGraphRelation = SemanticGraphRelation(
        sourceKey = source.canonicalKey,
        targetKey = target.canonicalKey,
        resolvedTargetKey = resolvedTargetKey,
        kind = SemanticGraphRelationKind.CALLS,
        sourcePath = source.path,
        startOffset = source.startOffset,
        endOffset = source.endOffset,
        line = LineNumber(1),
    )

    private fun semanticUpdate(
        path: SemanticGraphSourcePath,
        symbols: List<SemanticGraphSymbol>,
        boundarySymbols: List<SemanticGraphSymbol> = emptyList(),
        relations: List<SemanticGraphRelation>,
    ): SemanticGraphFileIndexUpdate = SemanticGraphFileIndexUpdate(
        path = path,
        packageName = "io.demo",
        moduleName = ":demo",
        contentHash = SemanticGraphSha256.parse("a".repeat(64)),
        status = SemanticGraphFileStatus.REFRESHED,
        diagnostics = emptyList(),
        types = emptyList(),
        symbols = symbols,
        boundarySymbols = boundarySymbols,
        relations = relations,
    )
}
