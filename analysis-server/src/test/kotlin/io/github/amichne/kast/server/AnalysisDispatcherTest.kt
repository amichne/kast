package io.github.amichne.kast.server

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `runtime status dispatches without HTTP`() {
        val result = dispatchSuccess<RuntimeStatusResponse>("runtime/status")

        assertEquals(RuntimeState.READY, result.state)
        assertEquals("fake", result.backendName)
    }

    @Test
    fun `capabilities dispatches without HTTP`() {
        val result = dispatchSuccess<BackendCapabilities>("capabilities")

        assertTrue(result.readCapabilities.contains(ReadCapability.RESOLVE_SYMBOL))
        assertEquals("fake", result.backendName)
    }

    @Test
    fun `symbol resolve dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<SymbolResult>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                SymbolQuery.serializer(),
                SymbolQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                ),
            ),
        )

        assertEquals("sample.greet", result.symbol.fqName)
    }

    @Test
    fun `references dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ReferencesResult>(
            method = "references",
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
    fun `rename dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RenameResult>(
            method = "rename",
            params = json.encodeToJsonElement(
                RenameQuery.serializer(),
                RenameQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    newName = "welcome",
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(result.edits.all { edit -> edit.newText == "welcome" })
    }

    @Test
    fun `apply edits dispatches without HTTP`() {
        dispatcher()
        val file = sampleFile()
        val originalContent = file.readText()
        val result = dispatchSuccess<ApplyEditsResult>(
            method = "edits/apply",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = file.toString(),
                            startOffset = 20,
                            endOffset = 25,
                            newText = "hello",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            filePath = file.toString(),
                            hash = FileHashing.sha256(originalContent),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(file.readText().contains("hello"))
    }

    @Test
    fun `invalid diagnostics params return rpc error payload`() {
        val response = dispatchRaw(
            method = "diagnostics",
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

    private fun sampleFile(): Path = tempDir.resolve("src").resolve("Sample.kt")

    private fun dispatcher(): AnalysisDispatcher = AnalysisDispatcher(
        backend = FakeAnalysisBackend.sample(tempDir),
        config = AnalysisServerConfig(),
    )

    private inline fun <reified T> dispatchSuccess(
        method: String,
        params: JsonElement? = null,
    ): T {
        val response = dispatchRaw(method, params)
        val success = json.decodeFromJsonElement(
            JsonRpcSuccessResponse.serializer(),
            response,
        )
        return json.decodeFromJsonElement(
            serializer<T>(),
            success.result,
        )
    }

    private fun dispatchRaw(
        method: String,
        params: JsonElement? = null,
    ): JsonObject {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = method,
            params = params,
        )
        val raw = runBlocking {
            dispatcher().dispatch(request)
        }
        return json.parseToJsonElement(raw).jsonObject
    }
}
