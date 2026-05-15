package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.result.ImplementationsResult
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.testing.FakeAnalysisBackend
import io.github.amichne.kast.api.validation.ParsedSymbolQuery
import io.github.amichne.kast.api.validation.ParsedWorkspaceSymbolQuery
import io.github.amichne.kast.api.validation.parsed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
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
    fun `dispatcher bytecode avoids kotlin Duration ABI coupling`() {
        val classFileText = classFileText(AnalysisDispatcher::class.java)

        assertFalse(classFileText.contains("kotlin/time/Duration"))
        assertFalse(classFileText.contains("fromRawValue-UwyO8pc"))
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
                    includeDocumentation = true,
                ),
            ),
        )

        assertEquals("sample.greet", result.symbol.fqName)
        assertTrue(result.symbol.documentation != null)
        assertTrue(result.symbol.parameters != null)
    }

    @Test
    fun `symbol resolve with includeDeclarationScope passes through`() {
        val file = sampleFile()

        val result = dispatchSuccess<SymbolResult>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                SymbolQuery.serializer(),
                SymbolQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    includeDeclarationScope = true,
                ),
            ),
        )

        assertEquals("sample.greet", result.symbol.fqName)
    }

    @Test
    fun `file outline includes declarationScope on symbols`() {
        val file = sampleFile()

        val result = dispatchSuccess<FileOutlineResult>(
            method = "file-outline",
            params = json.encodeToJsonElement(
                FileOutlineQuery.serializer(),
                FileOutlineQuery(filePath = file.toString()),
            ),
        )

        assertTrue(result.symbols.isNotEmpty())
        assertEquals("sample.greet", result.symbols.first().symbol.fqName)
    }

    @Test
    fun `skill resolve dispatches named-symbol orchestration`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "skill/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        assertEquals("sample.greet", success.symbol.fqName)
        assertEquals(file.toString(), success.filePath)
        assertEquals(true, success.ok)
    }

    @Test
    fun `skill discover symbol dispatches ranked candidates`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastSymbolDiscoveryResponse>(
            method = "skill/discover-symbol",
            params = json.encodeToJsonElement(
                KastSymbolDiscoveryRequest.serializer(),
                KastSymbolDiscoveryRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    filePath = file.toString(),
                    maxResults = 5,
                ),
            ),
        )

        val success = result as KastSymbolDiscoverySuccessResponse
        assertEquals(true, success.ok)
        assertEquals(1, success.candidates.size)
        assertEquals("sample.greet", success.candidates.first().symbol.fqName)
        assertEquals(file.toString(), success.candidates.first().disambiguation.fileHint)
    }

    @Test
    fun `skill discover symbol prefers hinted file with one search`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val primary = runBlocking {
            delegate.workspaceSymbolSearch(WorkspaceSymbolQuery(pattern = "greet").parsed()).symbols.single { it.fqName == "sample.greet" }
        }
        val alternate = primary.copy(
            fqName = "other.greet",
            location = Location(
                filePath = tempDir.resolve("src").resolve("Other.kt").toString(),
                startOffset = 5,
                endOffset = 10,
                startLine = 2,
                startColumn = 5,
                preview = "fun greet()",
            ),
            containingDeclaration = "other",
        )
        val backend = CountingBackend(
            delegate = delegate,
            searchSymbols = listOf(alternate, primary),
        )

        val result = dispatchSuccess<KastSymbolDiscoveryResponse>(
            method = "skill/discover-symbol",
            params = json.encodeToJsonElement(
                KastSymbolDiscoveryRequest.serializer(),
                KastSymbolDiscoveryRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    filePath = sampleFile().toString(),
                    maxResults = 5,
                ),
            ),
            backend = backend,
        )

        val success = result as KastSymbolDiscoverySuccessResponse
        assertEquals(sampleFile().toString(), success.candidates.first().symbol.location.filePath)
        assertEquals(1, backend.workspaceSymbolSearchCalls)
        assertEquals(0, backend.resolveSymbolCalls)
    }

    @Test
    fun `skill resolve includes requested context fields`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "skill/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                    includeDeclarationScope = true,
                    includeDocumentation = true,
                    includeSurroundingMembers = true,
                    surroundingLines = 2,
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        assertTrue(success.symbol.documentation != null)
        assertTrue(success.symbol.declarationScope != null)
        assertEquals(1, success.symbol.surroundingMembers!!.size)
        assertEquals(2, success.symbol.surroundingLines!!.focusLine)
    }

    @Test
    fun `skill discover symbol ranks by line and snippet context`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val base = runBlocking {
            delegate.workspaceSymbolSearch(WorkspaceSymbolQuery(pattern = "greet").parsed()).symbols.single { it.fqName == "sample.greet" }
        }
        val backend = CountingBackend(
            delegate = delegate,
            searchSymbols = listOf(
                base.copy(location = base.location.copy(startLine = 12, preview = "fun greet() = \"hi\"")),
                base.copy(location = base.location.copy(startLine = 30, preview = "fun greet(user: String) = user")),
                base.copy(location = base.location.copy(startLine = 42, preview = "fun greet(name: String) = name")),
            ),
        )

        val result = dispatchSuccess<KastSymbolDiscoveryResponse>(
            method = "skill/discover-symbol",
            params = json.encodeToJsonElement(
                KastSymbolDiscoveryRequest.serializer(),
                KastSymbolDiscoveryRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    filePath = sampleFile().toString(),
                    line = 30,
                    codeSnippet = "fun greet(user: String)",
                    maxResults = 5,
                ),
            ),
            backend = backend,
        )

        val success = result as KastSymbolDiscoverySuccessResponse
        assertEquals(30, success.candidates.first().symbol.location.startLine)
        assertTrue(success.candidates.first().rankingSignals.contains("nearby-line"))
        assertTrue(success.candidates.first().rankingSignals.contains("snippet-overlap"))
        assertEquals(1, backend.workspaceSymbolSearchCalls)
        assertEquals(0, backend.resolveSymbolCalls)
    }

    @Test
    fun `skill rename dispatches rename apply and diagnostics`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastRenameResponse>(
            method = "skill/rename",
            params = json.encodeToJsonElement(
                KastRenameRequest.serializer(),
                KastRenameBySymbolRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                    newName = "hello",
                ),
            ),
        )

        val success = result as KastRenameSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(1, success.affectedFiles.size)
        assertTrue(file.readText().contains("fun hello()"))
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
    fun `call hierarchy dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<CallHierarchyResult>(
            method = "call-hierarchy",
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
            method = "type-hierarchy",
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
            method = "semantic-insertion-point",
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
    fun `imports optimize dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ImportOptimizeResult>(
            method = "imports/optimize",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(
                    filePaths = listOf(file.toString()),
                ),
            ),
        )

        assertTrue(result.edits.isEmpty())
        assertTrue(result.affectedFiles.isEmpty())
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
    fun `apply edits validates absolute file operation paths`() {
        val response = dispatchRaw(
            method = "edits/apply",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = "relative/New.kt",
                            content = "class New",
                        ),
                    ),
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
    fun `imports optimize validates absolute file paths`() {
        val response = dispatchRaw(
            method = "imports/optimize",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(filePaths = listOf("relative/File.kt")),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace refresh dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RefreshResult>(
            method = "workspace/refresh",
            params = json.encodeToJsonElement(
                RefreshQuery.serializer(),
                RefreshQuery(filePaths = listOf(file.toString())),
            ),
        )

        assertEquals(listOf(file.toString()), result.refreshedFiles)
        assertTrue(result.removedFiles.isEmpty())
        assertEquals(false, result.fullRefresh)
    }

    @Test
    fun `file outline dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<FileOutlineResult>(
            method = "file-outline",
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
            method = "file-outline",
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
    fun `workspace files dispatches without HTTP`() {
        val result = dispatchSuccess<WorkspaceFilesResult>(
            method = "workspace/files",
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
            method = "workspace/files",
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
            method = "workspace/files",
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
            method = "workspace/files",
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
            method = "workspace/files",
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
            method = "workspace-symbol",
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
            method = "workspace-symbol",
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
            method = "workspace-symbol",
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
            method = "workspace/search",
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
            method = "workspace/search",
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

    @Test
    fun `implementations dispatches without HTTP`() {
        dispatcher()
        val file = sampleTypeFile()
        val offset = file.readText().indexOf("FriendlyGreeter")
        val result = dispatchSuccess<ImplementationsResult>(
            method = "implementations",
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
            method = "code-actions",
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
            method = "completions",
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

    @Test
    fun `invalid call hierarchy max total calls returns rpc error payload`() {
        val file = sampleFile()
        val response = dispatchRaw(
            method = "call-hierarchy",
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
            method = "type-hierarchy",
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

    private fun sampleFile(): Path = tempDir.resolve("src").resolve("Sample.kt")

    private fun sampleTypeFile(): Path = tempDir.resolve("src").resolve("Types.kt")

    private fun dispatcher(backend: AnalysisBackend = FakeAnalysisBackend.sample(tempDir)): AnalysisDispatcher = AnalysisDispatcher(
        backend = backend,
        config = AnalysisServerConfig(),
    )

    private inline fun <reified T> dispatchSuccess(
        method: String,
        params: JsonElement? = null,
        backend: AnalysisBackend = FakeAnalysisBackend.sample(tempDir),
    ): T {
        val response = dispatchRaw(method, params, backend)
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
        backend: AnalysisBackend = FakeAnalysisBackend.sample(tempDir),
    ): JsonObject {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = method,
            params = params,
        )
        val raw = runBlocking {
            dispatcher(backend).dispatch(request)
        }
        return json.parseToJsonElement(raw).jsonObject
    }

    private class CountingBackend(
        private val delegate: AnalysisBackend,
        private val searchSymbols: List<Symbol>,
    ) : AnalysisBackend by delegate {
        var workspaceSymbolSearchCalls: Int = 0
        var resolveSymbolCalls: Int = 0

        override suspend fun workspaceSymbolSearch(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult {
            workspaceSymbolSearchCalls += 1
            return WorkspaceSymbolResult(symbols = searchSymbols)
        }

        override suspend fun resolveSymbol(query: ParsedSymbolQuery): SymbolResult {
            resolveSymbolCalls += 1
            return delegate.resolveSymbol(query)
        }
    }

    private fun classFileText(clazz: Class<*>): String =
        checkNotNull(clazz.getResourceAsStream("${clazz.simpleName}.class")) {
            "Missing class file resource for ${clazz.name}"
        }.use { input ->
            String(input.readBytes(), StandardCharsets.ISO_8859_1)
        }
}
