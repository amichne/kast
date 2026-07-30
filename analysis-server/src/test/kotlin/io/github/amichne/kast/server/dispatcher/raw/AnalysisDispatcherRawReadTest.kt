package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherRawReadTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `references dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ReferencesResult>(
            method = "raw/references",
            params = json.encodeToJsonElement(
                ReferencesQuery.serializer(),
                ReferencesQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    includeDeclaration = true,
                ),
            ),
        )

        assertEquals("sample.greet", result.declaration?.fqName)
        assertEquals(1, result.references.size)
    }

    @Test
    fun `call hierarchy dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<CallHierarchyResult>(
            method = "raw/call-hierarchy",
            params = json.encodeToJsonElement(
                CallHierarchyQuery.serializer(),
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    direction = CallDirection.INCOMING,
                    depth = 1,
                ),
            ),
        )

        assertEquals("sample.greet", result.root.symbol.fqName)
        assertEquals(2, result.stats.totalNodes)
    }

    @Test
    fun `type hierarchy dispatches without HTTP`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")

        val result = dispatchSuccess<TypeHierarchyResult>(
            method = "raw/type-hierarchy",
            params = json.encodeToJsonElement(
                TypeHierarchyQuery.serializer(),
                TypeHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                    direction = TypeHierarchyDirection.BOTH,
                    depth = 1,
                ),
            ),
        )

        assertEquals("sample.FriendlyGreeter", result.root.symbol.fqName)
        assertEquals(listOf("sample.Greeter", "sample.LoudGreeter"), result.root.children.map { child -> child.symbol.fqName })
    }

    @Test
    fun `semantic insertion point dispatches without HTTP`() {
        dispatcher()
        val file = sampleFile()
        val content = file.readText()

        val result = dispatchSuccess<SemanticInsertionResult>(
            method = "raw/semantic-insertion-point",
            params = json.encodeToJsonElement(
                SemanticInsertionQuery.serializer(),
                SemanticInsertionQuery(
                    position = FilePosition(filePath = file.toString(), offset = 0),
                    target = SemanticInsertionTarget.FILE_BOTTOM,
                ),
            ),
        )

        assertEquals(content.length, result.insertionOffset)
        assertEquals(file.toString(), result.filePath)
    }

    @Test
    fun `file outline dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<FileOutlineResult>(
            method = "raw/file-outline",
            params = json.encodeToJsonElement(
                FileOutlineQuery.serializer(),
                FileOutlineQuery(filePath = file.toString()),
            ),
        )

        assertTrue(result.symbols.isNotEmpty())
        assertEquals("sample.greet", result.symbols.first().symbol.fqName)
    }

    @Test
    fun `file outline validates absolute file path`() {
        val response = dispatchRaw(
            method = "raw/file-outline",
            params = json.encodeToJsonElement(
                FileOutlineQuery.serializer(),
                FileOutlineQuery(filePath = "relative/File.kt"),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `implementations dispatches without HTTP`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")
        val result = dispatchSuccess<ImplementationsResult>(
            method = "raw/implementations",
            params = json.encodeToJsonElement(
                ImplementationsQuery.serializer(),
                ImplementationsQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                ),
            ),
        )
        assertEquals("sample.Greeter", result.declaration.fqName)
        assertTrue(result.implementations.isNotEmpty())
    }

    @Test
    fun `code actions dispatches without HTTP`() {
        val file = sampleFile()
        val result = dispatchSuccess<CodeActionsResult>(
            method = "raw/code-actions",
            params = json.encodeToJsonElement(
                CodeActionsQuery.serializer(),
                CodeActionsQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                ),
            ),
        )
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `completions dispatches without HTTP`() {
        val file = sampleFile()
        val result = dispatchSuccess<CompletionsResult>(
            method = "raw/completions",
            params = json.encodeToJsonElement(
                CompletionsQuery.serializer(),
                CompletionsQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                ),
            ),
        )
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `invalid diagnostics params return rpc error payload`() {
        val response = dispatchRaw(
            method = "raw/diagnostics",
            params = json.encodeToJsonElement(
                DiagnosticsQuery.serializer(),
                DiagnosticsQuery(filePaths = listOf("relative/File.kt")),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
        assertTrue(checkNotNull(error.error.data?.details?.get("filePath")).contains("relative/File.kt"))
    }

    @Test
    fun `invalid call hierarchy max total calls returns rpc error payload`() {
        val file = sampleFile()
        val response = dispatchRaw(
            method = "raw/call-hierarchy",
            params = json.encodeToJsonElement(
                CallHierarchyQuery.serializer(),
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    direction = CallDirection.OUTGOING,
                    depth = 0,
                    maxTotalCalls = 0,
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `invalid type hierarchy max results returns rpc error payload`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")
        val response = dispatchRaw(
            method = "raw/type-hierarchy",
            params = json.encodeToJsonElement(
                TypeHierarchyQuery.serializer(),
                TypeHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                    direction = TypeHierarchyDirection.SUBTYPES,
                    depth = 1,
                    maxResults = 0,
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `semantic graph scope is not capped by the result limit`() {
        val backend = FakeAnalysisBackend.sample(tempDir)
        val params = json.encodeToJsonElement(
            SemanticGraphQuery.serializer(),
            SemanticGraphQuery(
                filePaths = listOf(
                    SemanticGraphPath.parse(sampleFile().toAbsolutePath().toString()),
                    SemanticGraphPath.parse(sampleTypeFile().toAbsolutePath().toString()),
                ),
            ),
        )

        val result = dispatchSuccessWithBackend<SemanticGraphResult>(
            backend = backend,
            config = AnalysisServerConfig(maxResults = 1),
            method = "raw/semantic-graph",
            params = params,
        )

        assertEquals(2, result.coverage.files.size)
        assertEquals(2, result.symbolCount.value)
    }
}
