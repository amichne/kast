package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.CallNode
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.wrapper.KastCallersFailureResponse
import io.github.amichne.kast.api.wrapper.KastCallersQuery
import io.github.amichne.kast.api.wrapper.KastCallersSuccessResponse
import io.github.amichne.kast.api.wrapper.KastCandidate
import io.github.amichne.kast.api.wrapper.KastDiagnosticsFailureResponse
import io.github.amichne.kast.api.wrapper.KastDiagnosticsQuery
import io.github.amichne.kast.api.wrapper.KastDiagnosticsSuccessResponse
import io.github.amichne.kast.api.wrapper.KastDiagnosticsSummary
import io.github.amichne.kast.api.wrapper.KastMetricsFailureResponse
import io.github.amichne.kast.api.wrapper.KastMetricsQuery
import io.github.amichne.kast.api.wrapper.KastMetricsSuccessResponse
import io.github.amichne.kast.api.wrapper.KastReferencesFailureResponse
import io.github.amichne.kast.api.wrapper.KastReferencesQuery
import io.github.amichne.kast.api.wrapper.KastReferencesSuccessResponse
import io.github.amichne.kast.api.wrapper.KastRenameBySymbolQuery
import io.github.amichne.kast.api.wrapper.KastRenameFailureQuery
import io.github.amichne.kast.api.wrapper.KastRenameFailureResponse
import io.github.amichne.kast.api.wrapper.KastRenameSuccessResponse
import io.github.amichne.kast.api.wrapper.KastResolveFailureResponse
import io.github.amichne.kast.api.wrapper.KastResolveQuery
import io.github.amichne.kast.api.wrapper.KastResolveSuccessResponse
import io.github.amichne.kast.api.wrapper.KastScaffoldFailureResponse
import io.github.amichne.kast.api.wrapper.KastScaffoldQuery
import io.github.amichne.kast.api.wrapper.KastScaffoldSuccessResponse
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesFailureResponse
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesQuery
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesSuccessResponse
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateCreateFileQuery
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateFailureQuery
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateFailureResponse
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateSuccessResponse
import io.github.amichne.kast.api.wrapper.WrapperCallDirection
import io.github.amichne.kast.api.wrapper.WrapperMetric
import io.github.amichne.kast.cli.defaultCliJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SkillWrapperSerializerTest {
    private val json: Json = defaultCliJson()

    @Test
    fun `wrapper responses round-trip with documented casing`() {
        wrapperCases().forEach { case ->
            assertResponse(case.name, case.success, case.successType, case.successAssertions)
            assertResponse(case.name, case.failure, case.failureType, case.failureAssertions)
        }
    }

    private fun assertResponse(
        name: SkillWrapperName,
        response: Any,
        expectedType: String,
        extraAssertions: (JsonObject) -> Unit,
    ) {
        val parsed = json.parseToJsonElement(
            SkillWrapperSerializer.encode(json, name, response),
        ).jsonObject

        assertNotNull(parsed["ok"], "missing ok for $name")
        assertEquals(expectedType, parsed["type"]?.jsonPrimitive?.content, "wrong type for $name")
        assertNotNull(parsed["query"], "missing query for $name")
        assertNotNull(parsed["logFile"], "missing logFile for $name")
        extraAssertions(parsed)
    }

    private fun wrapperCases(): List<WrapperCase> = listOf(
        WrapperCase(
            name = SkillWrapperName.RESOLVE,
            success = KastResolveSuccessResponse(
                query = KastResolveQuery(workspaceRoot = "/tmp/ws", symbol = "Foo"),
                symbol = testSymbol(),
                filePath = "/tmp/ws/src/Foo.kt",
                offset = 10,
                candidate = KastCandidate(line = 1, column = 7, context = "class Foo"),
                candidateCount = 2,
                alternatives = listOf("com.example.OtherFoo"),
                logFile = "/tmp/log.txt",
            ),
            failure = KastResolveFailureResponse(
                stage = "resolve",
                message = "missing",
                query = KastResolveQuery(workspaceRoot = "/tmp/ws", symbol = "Missing"),
                logFile = "/tmp/log.txt",
                error = apiError(),
                errorText = "boom",
            ),
            successType = "RESOLVE_SUCCESS",
            failureType = "RESOLVE_FAILURE",
            successAssertions = { parsed ->
                assertEquals(2, parsed["candidateCount"]?.jsonPrimitive?.content?.toInt())
                assertEquals("com.example.Foo", parsed["symbol"]?.jsonObject?.get("fqName")?.jsonPrimitive?.content)
                assertEquals(
                    "/tmp/ws/src/Foo.kt",
                    parsed["symbol"]?.jsonObject?.get("location")?.jsonObject?.get("filePath")?.jsonPrimitive?.content,
                )
            },
            failureAssertions = { parsed ->
                assertEquals("boom", parsed["errorText"]?.jsonPrimitive?.content)
                assertEquals("REQUEST_FAILED", parsed["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
            },
        ),
        WrapperCase(
            name = SkillWrapperName.REFERENCES,
            success = KastReferencesSuccessResponse(
                query = KastReferencesQuery(workspaceRoot = "/tmp/ws", symbol = "Foo"),
                symbol = testSymbol(),
                filePath = "/tmp/ws/src/Foo.kt",
                offset = 10,
                references = listOf(testLocation("/tmp/ws/src/Bar.kt", "Foo()")),
                declaration = testSymbol("com.example.FooDeclaration"),
                candidateCount = 2,
                alternatives = listOf("com.example.OtherFoo"),
                logFile = "/tmp/log.txt",
            ),
            failure = KastReferencesFailureResponse(
                stage = "resolve",
                message = "missing",
                query = KastReferencesQuery(workspaceRoot = "/tmp/ws", symbol = "Missing"),
                logFile = "/tmp/log.txt",
            ),
            successType = "REFERENCES_SUCCESS",
            failureType = "REFERENCES_FAILURE",
            successAssertions = { parsed ->
                assertEquals(
                    "/tmp/ws/src/Bar.kt",
                    parsed["references"]?.jsonArray?.firstOrNull()?.jsonObject?.get("filePath")?.jsonPrimitive?.content,
                )
                assertEquals(
                    "com.example.FooDeclaration",
                    parsed["declaration"]?.jsonObject?.get("fqName")?.jsonPrimitive?.content,
                )
            },
        ),
        WrapperCase(
            name = SkillWrapperName.CALLERS,
            success = KastCallersSuccessResponse(
                query = KastCallersQuery(
                    workspaceRoot = "/tmp/ws",
                    symbol = "process",
                    direction = WrapperCallDirection.INCOMING,
                ),
                symbol = testSymbol(),
                filePath = "/tmp/ws/src/Foo.kt",
                offset = 10,
                root = CallNode(
                    symbol = testSymbol("com.example.CallRoot"),
                    callSite = testLocation("/tmp/ws/src/Caller.kt", "process()"),
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
                candidateCount = 3,
                alternatives = listOf("com.example.Handler.process"),
                logFile = "/tmp/log.txt",
            ),
            failure = KastCallersFailureResponse(
                stage = "resolve",
                message = "missing",
                query = KastCallersQuery(workspaceRoot = "/tmp/ws", symbol = "Missing"),
                logFile = "/tmp/log.txt",
            ),
            successType = "CALLERS_SUCCESS",
            failureType = "CALLERS_FAILURE",
            successAssertions = { parsed ->
                assertEquals(
                    "com.example.CallRoot",
                    parsed["root"]?.jsonObject?.get("symbol")?.jsonObject?.get("fqName")?.jsonPrimitive?.content,
                )
                assertEquals(
                    "/tmp/ws/src/Caller.kt",
                    parsed["root"]?.jsonObject?.get("callSite")?.jsonObject?.get("filePath")?.jsonPrimitive?.content,
                )
            },
        ),
        WrapperCase(
            name = SkillWrapperName.DIAGNOSTICS,
            success = KastDiagnosticsSuccessResponse(
                query = KastDiagnosticsQuery(
                    workspaceRoot = "/tmp/ws",
                    filePaths = listOf("/tmp/ws/src/Foo.kt"),
                ),
                clean = false,
                errorCount = 1,
                warningCount = 0,
                infoCount = 0,
                diagnostics = listOf(
                    Diagnostic(
                        location = testLocation(),
                        severity = DiagnosticSeverity.ERROR,
                        message = "broken",
                    ),
                ),
                logFile = "/tmp/log.txt",
            ),
            failure = KastDiagnosticsFailureResponse(
                stage = "diagnostics",
                message = "failed",
                query = KastDiagnosticsQuery(
                    workspaceRoot = "/tmp/ws",
                    filePaths = listOf("/tmp/ws/src/Foo.kt"),
                ),
                logFile = "/tmp/log.txt",
            ),
            successType = "DIAGNOSTICS_SUCCESS",
            failureType = "DIAGNOSTICS_FAILURE",
            successAssertions = { parsed ->
                assertEquals(
                    "/tmp/ws/src/Foo.kt",
                    parsed["diagnostics"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("location")?.jsonObject?.get("filePath")?.jsonPrimitive?.content,
                )
            },
        ),
        WrapperCase(
            name = SkillWrapperName.RENAME,
            success = KastRenameSuccessResponse(
                ok = true,
                query = KastRenameBySymbolQuery(
                    workspaceRoot = "/tmp/ws",
                    symbol = "oldName",
                    newName = "newName",
                    filePath = "/tmp/ws/src/Foo.kt",
                    offset = 10,
                ),
                editCount = 1,
                affectedFiles = listOf("/tmp/ws/src/Foo.kt"),
                applyResult = ApplyEditsResult(
                    applied = emptyList(),
                    affectedFiles = listOf("/tmp/ws/src/Foo.kt"),
                ),
                diagnostics = KastDiagnosticsSummary(clean = true, errorCount = 0, warningCount = 0, errors = emptyList()),
                logFile = "/tmp/log.txt",
            ),
            failure = KastRenameFailureResponse(
                stage = "resolve",
                message = "missing",
                query = KastRenameFailureQuery(
                    type = "RENAME_BY_SYMBOL_REQUEST",
                    workspaceRoot = "/tmp/ws",
                    symbol = "oldName",
                    newName = "newName",
                ),
                logFile = "/tmp/log.txt",
            ),
            successType = "RENAME_SUCCESS",
            failureType = "RENAME_FAILURE",
            successAssertions = { parsed ->
                assertEquals(
                    listOf("/tmp/ws/src/Foo.kt"),
                    parsed["applyResult"]?.jsonObject?.get("affectedFiles")?.jsonArray?.map { it.jsonPrimitive.content },
                )
            },
        ),
        WrapperCase(
            name = SkillWrapperName.SCAFFOLD,
            success = KastScaffoldSuccessResponse(
                query = KastScaffoldQuery(workspaceRoot = "/tmp/ws", targetFile = "/tmp/ws/src/Foo.kt"),
                outline = emptyList(),
                symbol = testSymbol(),
                logFile = "/tmp/log.txt",
            ),
            failure = KastScaffoldFailureResponse(
                stage = "scaffold",
                message = "failed",
                query = KastScaffoldQuery(workspaceRoot = "/tmp/ws", targetFile = "/tmp/ws/src/Foo.kt"),
                logFile = "/tmp/log.txt",
            ),
            successType = "SCAFFOLD_SUCCESS",
            failureType = "SCAFFOLD_FAILURE",
            successAssertions = { parsed ->
                assertEquals("com.example.Foo", parsed["symbol"]?.jsonObject?.get("fqName")?.jsonPrimitive?.content)
            },
        ),
        WrapperCase(
            name = SkillWrapperName.WORKSPACE_FILES,
            success = KastWorkspaceFilesSuccessResponse(
                query = KastWorkspaceFilesQuery(workspaceRoot = "/tmp/ws"),
                modules = listOf(
                    WorkspaceModule(
                        name = ":app",
                        sourceRoots = listOf("/tmp/ws/src"),
                        dependencyModuleNames = listOf(":core"),
                        fileCount = 1,
                    ),
                ),
                schemaVersion = 1,
                logFile = "/tmp/log.txt",
            ),
            failure = KastWorkspaceFilesFailureResponse(
                stage = "workspace-files",
                message = "failed",
                query = KastWorkspaceFilesQuery(workspaceRoot = "/tmp/ws"),
                logFile = "/tmp/log.txt",
            ),
            successType = "WORKSPACE_FILES_SUCCESS",
            failureType = "WORKSPACE_FILES_FAILURE",
            successAssertions = { parsed ->
                assertEquals(
                    listOf(":core"),
                    parsed["modules"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("dependencyModuleNames")?.jsonArray?.map { it.jsonPrimitive.content },
                )
            },
        ),
        WrapperCase(
            name = SkillWrapperName.WRITE_AND_VALIDATE,
            success = KastWriteAndValidateSuccessResponse(
                ok = true,
                query = KastWriteAndValidateCreateFileQuery(
                    workspaceRoot = "/tmp/ws",
                    filePath = "/tmp/ws/src/New.kt",
                ),
                appliedEdits = 1,
                importChanges = 0,
                diagnostics = KastDiagnosticsSummary(
                    clean = false,
                    errorCount = 1,
                    warningCount = 0,
                    errors = listOf(
                        Diagnostic(
                            location = testLocation("/tmp/ws/src/New.kt", "class New"),
                            severity = DiagnosticSeverity.ERROR,
                            message = "broken",
                        ),
                    ),
                ),
                message = "created",
                logFile = "/tmp/log.txt",
            ),
            failure = KastWriteAndValidateFailureResponse(
                stage = "validate",
                message = "failed",
                query = KastWriteAndValidateFailureQuery(
                    type = "CREATE_FILE_REQUEST",
                    workspaceRoot = "/tmp/ws",
                    filePath = "/tmp/ws/src/New.kt",
                ),
                logFile = "/tmp/log.txt",
            ),
            successType = "WRITE_AND_VALIDATE_SUCCESS",
            failureType = "WRITE_AND_VALIDATE_FAILURE",
            successAssertions = { parsed ->
                assertEquals(
                    "/tmp/ws/src/New.kt",
                    parsed["diagnostics"]?.jsonObject?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("location")?.jsonObject?.get("filePath")?.jsonPrimitive?.content,
                )
            },
        ),
        WrapperCase(
            name = SkillWrapperName.METRICS,
            success = KastMetricsSuccessResponse(
                query = KastMetricsQuery(workspaceRoot = "/tmp/ws", metric = WrapperMetric.FAN_IN),
                results = buildJsonObject {
                    put("topSymbol", JsonPrimitive("com.example.Foo"))
                },
                logFile = "/tmp/log.txt",
            ),
            failure = KastMetricsFailureResponse(
                stage = "metrics",
                message = "failed",
                query = KastMetricsQuery(workspaceRoot = "/tmp/ws", metric = WrapperMetric.FAN_IN),
                logFile = "/tmp/log.txt",
            ),
            successType = "METRICS_SUCCESS",
            failureType = "METRICS_FAILURE",
            successAssertions = { parsed ->
                assertEquals("com.example.Foo", parsed["results"]?.jsonObject?.get("topSymbol")?.jsonPrimitive?.content)
            },
        ),
    )

    private fun testLocation(
        filePath: String = "/tmp/ws/src/Foo.kt",
        preview: String = "class Foo",
    ) = Location(
        filePath = filePath,
        startOffset = 10,
        endOffset = 17,
        startLine = 1,
        startColumn = 7,
        preview = preview,
    )

    private fun testSymbol(fqName: String = "com.example.Foo") = Symbol(
        fqName = fqName,
        kind = SymbolKind.CLASS,
        location = testLocation(),
    )

    private fun apiError() = ApiErrorResponse(
        requestId = "req-1",
        code = "REQUEST_FAILED",
        message = "broken",
        retryable = false,
    )

    private data class WrapperCase(
        val name: SkillWrapperName,
        val success: Any,
        val failure: Any,
        val successType: String,
        val failureType: String,
        val successAssertions: (JsonObject) -> Unit = {},
        val failureAssertions: (JsonObject) -> Unit = {},
    )
}
