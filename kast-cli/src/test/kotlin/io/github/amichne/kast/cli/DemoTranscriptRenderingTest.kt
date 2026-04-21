package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.CallHierarchyResult
import io.github.amichne.kast.api.contract.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.RenameResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DemoTranscriptRenderingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `render matches the approved three-act transcript`() {
        val report = demoReport()

        val transcript = DemoCommandSupport(themeProvider = CliTextTheme::ansi).render(report)

        assertEquals(expectedTranscript().trimIndent() + "\n", transcript)
    }

    private fun demoReport(): DemoReport {
        val root = symbol(
            fqName = "demo.WorkflowEngine.execute",
            kind = SymbolKind.FUNCTION,
            filePath = tempDir.resolve("core/src/main/kotlin/WorkflowEngine.kt"),
            line = 42,
            preview = "suspend fun execute(context: ExecutionContext): Result<Unit>",
        )
        val references = listOf(
            location("orchestration/src/main/kotlin/Scheduler.kt", 87, "scheduleNext { workflowEngine.execute(context) }"),
            location("orchestration/src/main/kotlin/Scheduler.kt", 143, "workflowEngine.execute(context)"),
            location("api/src/main/kotlin/WorkflowResource.kt", 31, "workflowEngine.execute(context)"),
            location("core/src/test/kotlin/WorkflowEngineTest.kt", 19, "subject.execute(context)"),
            location("core/src/test/kotlin/WorkflowEngineTest.kt", 67, "subject.execute(context)"),
            location("integration/src/main/kotlin/PipelineRunner.kt", 204, "workflowEngine.execute(context)"),
        )
        return DemoReport(
            workspaceRoot = tempDir,
            selectedSymbol = root,
            textSearch = DemoTextSearchSummary(
                totalMatches = 38,
                likelyCorrect = 19,
                ambiguous = 8,
                falsePositives = 19,
                filesTouched = 6,
                categoryCounts = mapOf(
                    DemoTextMatchCategory.STRING to 12,
                    DemoTextMatchCategory.COMMENT to 9,
                    DemoTextMatchCategory.IMPORT to 8,
                    DemoTextMatchCategory.SUBSTRING to 0,
                ),
                sampleMatches = listOf(
                    DemoTextMatch(
                        filePath = tempDir.resolve("core/src/main/kotlin/Banner.kt").toString(),
                        lineNumber = 8,
                        preview = """println("execute this command")""",
                        category = DemoTextMatchCategory.STRING,
                    ),
                    DemoTextMatch(
                        filePath = tempDir.resolve("core/src/main/kotlin/Notes.kt").toString(),
                        lineNumber = 14,
                        preview = "// TODO: execute after init",
                        category = DemoTextMatchCategory.COMMENT,
                    ),
                    DemoTextMatch(
                        filePath = tempDir.resolve("api/src/main/kotlin/Imports.kt").toString(),
                        lineNumber = 3,
                        preview = "import demo.WorkflowEngine.execute",
                        category = DemoTextMatchCategory.IMPORT,
                    ),
                    DemoTextMatch(
                        filePath = tempDir.resolve("orchestration/src/main/kotlin/Scheduler.kt").toString(),
                        lineNumber = 87,
                        preview = "workflowEngine.execute(context)",
                        category = DemoTextMatchCategory.LIKELY_CORRECT,
                    ),
                ),
            ),
            resolvedSymbol = root,
            references = ReferencesResult(
                declaration = root,
                references = references,
                searchScope = null,
            ),
            rename = RenameResult(
                edits = emptyList(),
                fileHashes = emptyList(),
                affectedFiles = emptyList(),
                searchScope = null,
            ),
            callHierarchy = CallHierarchyResult(
                root = CallNode(
                    symbol = root,
                    children = listOf(
                        callNode("demo.Scheduler.scheduleNext", "orchestration/src/main/kotlin/Scheduler.kt", 87,
                            children = listOf(
                                callNode("demo.PipelineCoordinator.start", "integration/src/main/kotlin/PipelineCoordinator.kt", 18),
                                callNode("demo.RetryPolicy.attempt", "orchestration/src/main/kotlin/RetryPolicy.kt", 12),
                            )),
                        callNode("demo.WorkflowResource.run", "api/src/main/kotlin/WorkflowResource.kt", 31,
                            children = listOf(
                                callNode("demo.AuthMiddleware.withContext", "api/src/main/kotlin/AuthMiddleware.kt", 9),
                            )),
                        callNode("demo.PipelineRunner.executePipeline", "integration/src/main/kotlin/PipelineRunner.kt", 204,
                            children = listOf(
                                callNode("demo.BatchProcessor.processBatch", "integration/src/main/kotlin/BatchProcessor.kt", 16),
                            )),
                    ),
                ),
                stats = CallHierarchyStats(
                    totalNodes = 8,
                    totalEdges = 7,
                    truncatedNodes = 0,
                    maxDepthReached = 2,
                    timeoutReached = false,
                    maxTotalCallsReached = false,
                    maxChildrenPerNodeReached = false,
                    filesVisited = 6,
                ),
            ),
        )
    }

    private fun callNode(
        fqName: String,
        relativePath: String,
        line: Int,
        children: List<CallNode> = emptyList(),
    ): CallNode = CallNode(
        symbol = symbol(
            fqName = fqName,
            kind = SymbolKind.FUNCTION,
            filePath = tempDir.resolve(relativePath),
            line = line,
            preview = "fun ${fqName.substringAfterLast('.')}(...)",
        ),
        children = children,
    )

    private fun symbol(
        fqName: String,
        kind: SymbolKind,
        filePath: Path,
        line: Int,
        preview: String,
    ): Symbol = Symbol(
        fqName = fqName,
        kind = kind,
        location = Location(
            filePath = filePath.toString(),
            startOffset = 0,
            endOffset = preview.length,
            startLine = line,
            startColumn = 1,
            preview = preview,
        ),
        visibility = SymbolVisibility.PUBLIC,
        containingDeclaration = fqName.substringBeforeLast('.', ""),
    )

    private fun location(relativePath: String, line: Int, preview: String): Location = Location(
        filePath = tempDir.resolve(relativePath).toString(),
        startOffset = 0,
        endOffset = preview.length,
        startLine = line,
        startColumn = 1,
        preview = preview,
    )

    private fun expectedTranscript(): String =
        """
        ┌─────────────────────────────────────────────────────┐
        │  Act 1 of 3 — Text Search                           │
        │  grep -rn "execute" --include="*.kt"                │
        └─────────────────────────────────────────────────────┘
        
          Scanning... ████████████████████░░░░  38 hits
        
          ┌──────────────────┬───────┬──────────────────────────────┐
          │ Category         │ Count │ Example                      │
          ├──────────────────┼───────┼──────────────────────────────┤
          │ String literals  │    12 │ println("execute this comman │
          │ Comments         │     9 │ // TODO: execute after init  │
          │ Unrelated scope  │     8 │ import demo.WorkflowEngine.e │
          │ Possible matches │    19 │ workflowEngine.execute(conte │
          └──────────────────┴───────┴──────────────────────────────┘
        
          38 grep hits. No type information. No scope. Just noise.
        
        ┌─────────────────────────────────────────────────────┐
        │  Act 2 of 3 — Symbol Resolution                     │
        │  kast resolve "execute" → WorkflowEngine.execute    │
        └─────────────────────────────────────────────────────┘
        
          Declared in: core/src/main/kotlin/WorkflowEngine.kt:42
          Type:        suspend fun execute(context: ExecutionContext): Result<Unit>
        
          ┌────────────────────────────────┬──────┬───────┬────────────────────┬────────────────┐
          │ File                           │ Line │ Kind  │ Resolved Type      │ Module         │
          ├────────────────────────────────┼──────┼───────┼────────────────────┼────────────────┤
          │ orchestration/Scheduler.kt     │   87 │ call  │ WorkflowEngine     │ :orchestration │
          │ orchestration/Scheduler.kt     │  143 │ call  │ WorkflowEngine     │ :orchestration │
          │ api/WorkflowResource.kt        │   31 │ call  │ WorkflowEngine     │ :api           │
          │ core/WorkflowEngineTest.kt     │   19 │ call  │ WorkflowEngine     │ :core          │
          │ core/WorkflowEngineTest.kt     │   67 │ call  │ WorkflowEngine     │ :core          │
          │ integration/PipelineRunner.kt  │  204 │ call  │ WorkflowEngine     │ :integration   │
          └────────────────────────────────┴──────┴───────┴────────────────────┴────────────────┘
        
          ──────────────────────────────────────────────────────────────────
          38 text matches  →  6 actual references to WorkflowEngine.execute
          Noise eliminated: 84%
          ──────────────────────────────────────────────────────────────────
        
        ┌─────────────────────────────────────────────────────┐
        │  Act 3 of 3 — Caller Graph (depth 2)                │
        └─────────────────────────────────────────────────────┘
        
          WorkflowEngine.execute                                                                     [:core]
          ├── Scheduler.scheduleNext()                                                      [:orchestration]
          │   ├── PipelineCoordinator.start()                                                 [:integration]
          │   └── RetryPolicy.attempt()                                                     [:orchestration]
          ├── WorkflowResource.run()                                                                  [:api]
          │   └── AuthMiddleware.withContext()                                                        [:api]
          └── PipelineRunner.executePipeline()                                                [:integration]
              └── BatchProcessor.processBatch()                                               [:integration]
        
          4 modules. 8 symbols reachable in 2 hops.
          Every edge is a compiler-verified call site.
          kast demo --symbol demo.WorkflowEngine.execute --depth 3
        """
}
