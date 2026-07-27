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

class AnalysisDispatcherWorkspaceTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `workspace files dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceFilesResult>(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(),
            ),
        )

        assertTrue(result.modules.isNotEmpty())
        assertEquals("fake-module", result.modules.first().name)
    }

    @Test
    fun `workspace files filters by module name`() {
        val result = dispatchSuccess<WorkspaceFilesResult>(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(moduleName = "nonexistent"),
            ),
        )

        assertTrue(result.modules.isEmpty())
    }

    @Test
    fun `workspace files rejects blank module name`() {
        val response = dispatchRaw(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(moduleName = "  "),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace files rejects non positive file cap`() {
        val response = dispatchRaw(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(includeFiles = true, maxFilesPerModule = 0),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace files rejects file cap above server max results`() {
        val response = dispatchRaw(
            method = "raw/workspace-files",
            params = json.encodeToJsonElement(
                WorkspaceFilesQuery.serializer(),
                WorkspaceFilesQuery(includeFiles = true, maxFilesPerModule = 501),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace symbol dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceSymbolResult>(
            method = "raw/workspace-symbol",
            params = json.encodeToJsonElement(
                WorkspaceSymbolQuery.serializer(),
                WorkspaceSymbolQuery(pattern = "greet"),
            ),
        )

        assertTrue(result.symbols.isNotEmpty())
        assertEquals("sample.greet", result.symbols.first().fqName)
    }

    @Test
    fun `workspace symbol rejects blank pattern`() {
        val response = dispatchRaw(
            method = "raw/workspace-symbol",
            params = json.encodeToJsonElement(
                WorkspaceSymbolQuery.serializer(),
                WorkspaceSymbolQuery(pattern = "  "),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace symbol rejects zero max results`() {
        val response = dispatchRaw(
            method = "raw/workspace-symbol",
            params = json.encodeToJsonElement(
                WorkspaceSymbolQuery.serializer(),
                WorkspaceSymbolQuery(pattern = "greet", maxResults = 0),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace search dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceSearchResult>(
            method = "raw/workspace-search",
            params = json.encodeToJsonElement(
                WorkspaceSearchQuery.serializer(),
                WorkspaceSearchQuery(pattern = "greet"),
            ),
        )

        assertTrue(result.matches.isNotEmpty())
        assertTrue(result.matches.first().preview.contains("greet"))
    }

    @Test
    fun `workspace search rejects blank pattern`() {
        val response = dispatchRaw(
            method = "raw/workspace-search",
            params = json.encodeToJsonElement(
                WorkspaceSearchQuery.serializer(),
                WorkspaceSearchQuery(pattern = "  "),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }
}
