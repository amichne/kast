package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.contract.ApplyEditsResult
import io.github.amichne.kast.api.contract.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.wrapper.KastCallersQuery
import io.github.amichne.kast.api.wrapper.KastCallersSuccessResponse
import io.github.amichne.kast.api.wrapper.KastCandidate
import io.github.amichne.kast.api.wrapper.KastDiagnosticsQuery
import io.github.amichne.kast.api.wrapper.KastDiagnosticsSuccessResponse
import io.github.amichne.kast.api.wrapper.KastDiagnosticsSummary
import io.github.amichne.kast.api.wrapper.KastReferencesQuery
import io.github.amichne.kast.api.wrapper.KastReferencesSuccessResponse
import io.github.amichne.kast.api.wrapper.KastRenameBySymbolQuery
import io.github.amichne.kast.api.wrapper.KastRenameSuccessResponse
import io.github.amichne.kast.api.wrapper.KastResolveFailureResponse
import io.github.amichne.kast.api.wrapper.KastResolveQuery
import io.github.amichne.kast.api.wrapper.KastResolveSuccessResponse
import io.github.amichne.kast.api.wrapper.KastScaffoldQuery
import io.github.amichne.kast.api.wrapper.KastScaffoldSuccessResponse
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesQuery
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesSuccessResponse
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateCreateFileQuery
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateSuccessResponse
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.WorkspaceModule
import io.github.amichne.kast.cli.defaultCliJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that wrapper response JSON matches the contract required by agent consumers.
 * The `type` discriminator, `ok` field, `query` object, and `log_file` must appear
 * exactly as the shell wrappers produce them.
 */
class SkillWrapperContractTest {

    private val json: Json = defaultCliJson()

    private fun testLocation() = Location(
        filePath = "/tmp/ws/src/Foo.kt",
        startOffset = 10,
        endOffset = 17,
        startLine = 1,
        startColumn = 10,
        preview = "class Foo",
    )

    private fun testSymbol() = Symbol(
        fqName = "com.example.Foo",
        kind = SymbolKind.CLASS,
        location = testLocation(),
    )

    @Test
    fun `workspace-files success response has correct JSON shape`() {
        val response = KastWorkspaceFilesSuccessResponse(
            ok = true,
            query = KastWorkspaceFilesQuery(
                workspaceRoot = "/tmp/ws",
                moduleName = null,
                includeFiles = false,
            ),
            modules = listOf(
                WorkspaceModule(
                    name = ":app",
                    sourceRoots = listOf("src/main/kotlin"),
                    dependencyModuleNames = listOf(":core"),
                    fileCount = 10,
                ),
            ),
            schemaVersion = 1,
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.WORKSPACE_FILES, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("WORKSPACE_FILES_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(parsed["query"])
        val query = parsed["query"] as JsonObject
        assertEquals("/tmp/ws", query["workspaceRoot"]?.jsonPrimitive?.content)
        assertNotNull(parsed["modules"]?.jsonArray)
        assertEquals(1, parsed["schemaVersion"]?.jsonPrimitive?.int)
        assertEquals("/tmp/log.txt", parsed["logFile"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resolve failure response has correct JSON shape`() {
        val response = KastResolveFailureResponse(
            ok = false,
            stage = "resolve",
            message = "Symbol not found",
            query = KastResolveQuery(
                workspaceRoot = "/tmp/ws",
                symbol = "Missing",
            ),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.RESOLVE, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("RESOLVE_FAILURE", parsed["type"]?.jsonPrimitive?.content)
        assertFalse(parsed["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("resolve", parsed["stage"]?.jsonPrimitive?.content)
        assertEquals("Symbol not found", parsed["message"]?.jsonPrimitive?.content)
        assertNotNull(parsed["query"])
        assertEquals("/tmp/log.txt", parsed["logFile"]?.jsonPrimitive?.content)
    }

    @Test
    fun `diagnostics success response has correct JSON shape`() {
        val response = KastDiagnosticsSuccessResponse(
            ok = true,
            query = KastDiagnosticsQuery(
                workspaceRoot = "/tmp/ws",
                filePaths = listOf("src/Main.kt"),
            ),
            clean = true,
            errorCount = 0,
            warningCount = 0,
            infoCount = 0,
            diagnostics = emptyList(),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.DIAGNOSTICS, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("DIAGNOSTICS_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertTrue(parsed["clean"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(0, parsed["errorCount"]?.jsonPrimitive?.int)
        assertEquals(0, parsed["warningCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `resolve success response has correct JSON shape`() {
        val response = KastResolveSuccessResponse(
            ok = true,
            query = KastResolveQuery(workspaceRoot = "/tmp/ws", symbol = "Foo"),
            symbol = testSymbol(),
            filePath = "/tmp/ws/src/Foo.kt",
            offset = 10,
            candidate = KastCandidate(line = 1, column = 10, context = "class Foo"),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.RESOLVE, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("RESOLVE_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(parsed["symbol"])
        assertNotNull(parsed["query"])
        assertNotNull(parsed["filePath"])
        assertNotNull(parsed["offset"])
        assertNotNull(parsed["candidate"])
        assertEquals("/tmp/log.txt", parsed["logFile"]?.jsonPrimitive?.content)
    }

    @Test
    fun `references success response has correct JSON shape`() {
        val response = KastReferencesSuccessResponse(
            ok = true,
            query = KastReferencesQuery(workspaceRoot = "/tmp/ws", symbol = "myFun"),
            symbol = testSymbol(),
            filePath = "/tmp/ws/src/Foo.kt",
            offset = 10,
            references = emptyList(),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.REFERENCES, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("REFERENCES_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(parsed["symbol"])
        assertNotNull(parsed["references"]?.jsonArray)
    }

    @Test
    fun `callers success response has correct JSON shape`() {
        val response = KastCallersSuccessResponse(
            ok = true,
            query = KastCallersQuery(workspaceRoot = "/tmp/ws", symbol = "myFun"),
            symbol = testSymbol(),
            filePath = "/tmp/ws/src/Foo.kt",
            offset = 10,
            root = CallNode(
                symbol = testSymbol(),
                children = emptyList(),
            ),
            stats = CallHierarchyStats(
                totalNodes = 1,
                totalEdges = 0,
                truncatedNodes = 0,
                maxDepthReached = 0,
                timeoutReached = false,
                maxTotalCallsReached = false,
                maxChildrenPerNodeReached = false,
                filesVisited = 1,
            ),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.CALLERS, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("CALLERS_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(parsed["root"])
        assertNotNull(parsed["stats"])
    }

    @Test
    fun `rename success response has correct JSON shape`() {
        val applyResult = ApplyEditsResult(
            applied = emptyList(),
            affectedFiles = listOf("/tmp/ws/src/Foo.kt"),
        )
        val response = KastRenameSuccessResponse(
            ok = true,
            query = KastRenameBySymbolQuery(
                workspaceRoot = "/tmp/ws",
                symbol = "oldName",
                newName = "newName",
                filePath = "/tmp/ws/src/Foo.kt",
                offset = 10,
            ),
            editCount = 2,
            affectedFiles = listOf("/tmp/ws/src/Foo.kt"),
            applyResult = applyResult,
            diagnostics = KastDiagnosticsSummary(clean = true, errorCount = 0, warningCount = 0, errors = emptyList()),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.RENAME, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("RENAME_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(2, parsed["editCount"]?.jsonPrimitive?.int)
        assertNotNull(parsed["affectedFiles"]?.jsonArray)
        assertNotNull(parsed["diagnostics"])
        assertNotNull(parsed["applyResult"])
    }

    @Test
    fun `scaffold success response has correct JSON shape`() {
        val response = KastScaffoldSuccessResponse(
            ok = true,
            query = KastScaffoldQuery(workspaceRoot = "/tmp/ws", targetFile = "/tmp/ws/src/MyClass.kt"),
            outline = emptyList(),
            references = null,
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.SCAFFOLD, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("SCAFFOLD_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(parsed["outline"]?.jsonArray)
    }

    @Test
    fun `write-and-validate success response has correct JSON shape`() {
        val response = KastWriteAndValidateSuccessResponse(
            ok = true,
            query = KastWriteAndValidateCreateFileQuery(
                workspaceRoot = "/tmp/ws",
                filePath = "/tmp/ws/src/New.kt",
            ),
            appliedEdits = 1,
            importChanges = 0,
            diagnostics = KastDiagnosticsSummary(clean = true, errorCount = 0, warningCount = 0, errors = emptyList()),
            logFile = "/tmp/log.txt",
        )

        val encoded = SkillWrapperSerializer.encode(json, SkillWrapperName.WRITE_AND_VALIDATE, response)
        val parsed = json.parseToJsonElement(encoded) as JsonObject

        assertEquals("WRITE_AND_VALIDATE_SUCCESS", parsed["type"]?.jsonPrimitive?.content)
        assertTrue(parsed["ok"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(1, parsed["appliedEdits"]?.jsonPrimitive?.int)
        assertEquals(0, parsed["importChanges"]?.jsonPrimitive?.int)
        assertNotNull(parsed["diagnostics"])
    }
}
