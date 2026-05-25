package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
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
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.testing.FakeAnalysisBackend
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
        val classFileText = classFileText(RpcAnalysisDispatcher::class.java)

        assertFalse(classFileText.contains("kotlin/time/Duration"))
        assertFalse(classFileText.contains("fromRawValue-UwyO8pc"))
    }

    @Test
    fun `symbol resolve dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<SymbolResult>(
            method = "raw/resolve",
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
            method = "raw/resolve",
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
    fun `symbol resolve dispatches named-symbol orchestration`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "symbol/resolve",
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
    fun `symbol discover ranks contextual candidates and returns resolve requests`() {
        val file = sampleTypeFile()

        val result = dispatchSuccess<KastDiscoverResponse>(
            method = "symbol/discover",
            params = json.encodeToJsonElement(
                KastDiscoverRequest.serializer(),
                KastDiscoverRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "Greeter",
                    fileHint = file.toString(),
                    line = 4,
                    codeSnippet = "open class FriendlyGreeter",
                    kind = WrapperNamedSymbolKind.CLASS,
                    maxResults = 2,
                ),
            ),
        )

        val success = result as KastDiscoverSuccessResponse
        assertEquals(true, success.ok)
        assertEquals(2, success.candidates.size)
        assertEquals("sample.FriendlyGreeter", success.candidates.first().symbol.fqName)
        assertEquals(1, success.candidates.first().rank)
        assertEquals("symbol/resolve", success.candidates.first().nextRequest.method)
        assertEquals(WrapperNamedSymbolKind.CLASS, success.candidates.first().resolveParams.kind)
        assertEquals(file.toString(), success.candidates.first().resolveParams.fileHint)
        assertEquals(true, success.page?.truncated)
    }

    @Test
    fun `symbol discover rejects non positive max results`() {
        val response = dispatchRaw(
            method = "symbol/discover",
            params = json.encodeToJsonElement(
                KastDiscoverRequest.serializer(),
                KastDiscoverRequest(symbol = "greet", maxResults = 0),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `symbol query returns exact fq name lookup with separate evidence`() {
        val fixture = seedSymbolQueryIndex()

        val result = dispatchSuccess<KastSymbolQueryResponse>(
            method = "symbol/query",
            params = json.encodeToJsonElement(
                KastSymbolQueryRequest.serializer(),
                KastSymbolQueryRequest(
                    workspaceRoot = tempDir.toString(),
                    query = "com.acme.billing.InvoiceValidator.validateLineItem",
                    modes = listOf(SymbolQueryMode.EXACT, SymbolQueryMode.SEMANTIC),
                    limit = 5,
                    includeEvidence = true,
                    includeNextRequests = true,
                ),
            ),
        )

        val success = result as KastSymbolQuerySuccessResponse
        assertEquals(true, success.ok)
        assertEquals("com.acme.billing.InvoiceValidator.validateLineItem", success.results.first().declaration.fqName)
        assertEquals(fixture.invoiceValidatorFile, success.results.first().declaration.file.path)
        assertTrue(success.results.first().signals.exact.matched)
        assertFalse(success.results.first().signals.semantic.available)
        assertEquals("raw/resolve", success.results.first().nextRequests?.rawResolve?.method)
    }

    @Test
    fun `symbol query enforces scoped filters symbolically`() {
        seedSymbolQueryIndex()

        val result = dispatchSuccess<KastSymbolQueryResponse>(
            method = "symbol/query",
            params = json.encodeToJsonElement(
                KastSymbolQueryRequest.serializer(),
                KastSymbolQueryRequest(
                    workspaceRoot = tempDir.toString(),
                    query = "validation validate validator invalid",
                    modes = listOf(SymbolQueryMode.LEXICAL, SymbolQueryMode.STRUCTURAL),
                    filters = KastSymbolQueryFilters(
                        kinds = listOf(SymbolQueryDeclarationKind.FUNCTION),
                        modulePath = ":billing",
                        sourceSet = "main",
                    ),
                    limit = 20,
                    includeEvidence = true,
                ),
            ),
        )

        val success = result as KastSymbolQuerySuccessResponse
        assertTrue(success.results.isNotEmpty())
        assertTrue(success.results.all { it.declaration.kind == "FUNCTION" })
        assertTrue(success.results.all { it.declaration.modulePath == ":billing" && it.declaration.sourceSet == "main" })
        assertTrue(success.hardFilters.all(HardFilter::satisfiedSymbolically))
        assertFalse(success.results.any { it.declaration.fqName.contains("OtherValidator") })
    }

    @Test
    fun `symbol query returns incoming call graph evidence from sqlite`() {
        seedSymbolQueryIndex()

        val result = dispatchSuccess<KastSymbolQueryResponse>(
            method = "symbol/query",
            params = json.encodeToJsonElement(
                KastSymbolQueryRequest.serializer(),
                KastSymbolQueryRequest(
                    workspaceRoot = tempDir.toString(),
                    query = "validateLineItem",
                    modes = listOf(SymbolQueryMode.EXACT, SymbolQueryMode.GRAPH),
                    anchor = KastSymbolQueryAnchor(fqName = "com.acme.billing.InvoiceValidator.validateLineItem"),
                    graph = KastSymbolQueryGraph(
                        direction = SymbolQueryGraphDirection.INCOMING,
                        edgeKinds = listOf(SymbolQueryEdgeKind.CALL),
                        depth = 1,
                        maxEdgesPerResult = 10,
                    ),
                    limit = 10,
                ),
            ),
        )

        val success = result as KastSymbolQuerySuccessResponse
        val invoiceService = success.results.single { it.declaration.fqName == "com.acme.billing.InvoiceService.createInvoice" }
        assertTrue(invoiceService.signals.graph.matched)
        assertTrue(invoiceService.signals.graph.paths.all { it.edgeKind == "CALL" })
        assertEquals("com.acme.billing.InvoiceValidator.validateLineItem", invoiceService.signals.graph.paths.first().toFqName)
    }

    @Test
    fun `symbol query prefilters subtypes with inheritance evidence`() {
        seedSymbolQueryIndex()

        val result = dispatchSuccess<KastSymbolQueryResponse>(
            method = "symbol/query",
            params = json.encodeToJsonElement(
                KastSymbolQueryRequest.serializer(),
                KastSymbolQueryRequest(
                    workspaceRoot = tempDir.toString(),
                    query = "PaymentValidator",
                    modes = listOf(SymbolQueryMode.EXACT, SymbolQueryMode.GRAPH),
                    filters = KastSymbolQueryFilters(kinds = listOf(SymbolQueryDeclarationKind.CLASS)),
                    anchor = KastSymbolQueryAnchor(fqName = "com.acme.payments.PaymentValidator"),
                    graph = KastSymbolQueryGraph(
                        direction = SymbolQueryGraphDirection.INCOMING,
                        edgeKinds = listOf(SymbolQueryEdgeKind.INHERITANCE, SymbolQueryEdgeKind.OVERRIDE),
                        depth = 1,
                        maxEdgesPerResult = 10,
                    ),
                    limit = 10,
                ),
            ),
        )

        val success = result as KastSymbolQuerySuccessResponse
        assertEquals(listOf("com.acme.payments.DefaultPaymentValidator"), success.results.map { it.declaration.fqName })
        assertTrue(success.results.single().signals.graph.paths.any { it.edgeKind == "INHERITANCE" })
    }

    @Test
    fun `symbol query can return likely test evidence without semantic availability`() {
        seedSymbolQueryIndex()

        val result = dispatchSuccess<KastSymbolQueryResponse>(
            method = "symbol/query",
            params = json.encodeToJsonElement(
                KastSymbolQueryRequest.serializer(),
                KastSymbolQueryRequest(
                    workspaceRoot = tempDir.toString(),
                    query = "InvoiceValidator Test",
                    modes = listOf(SymbolQueryMode.LEXICAL, SymbolQueryMode.STRUCTURAL, SymbolQueryMode.GRAPH, SymbolQueryMode.SEMANTIC),
                    filters = KastSymbolQueryFilters(sourceSet = "test"),
                    graph = KastSymbolQueryGraph(
                        direction = SymbolQueryGraphDirection.OUTGOING,
                        edgeKinds = listOf(SymbolQueryEdgeKind.CALL, SymbolQueryEdgeKind.TYPE_REF, SymbolQueryEdgeKind.ANNOTATION),
                        depth = 1,
                        maxEdgesPerResult = 10,
                    ),
                    semantic = KastSymbolQuerySemantic(enabled = true),
                    limit = 10,
                ),
            ),
        )

        val success = result as KastSymbolQuerySuccessResponse
        assertTrue(success.results.isNotEmpty())
        assertTrue(success.results.all { it.declaration.sourceSet == "test" })
        assertFalse(success.availableSignals.semantic)
        assertFalse(success.results.first().signals.semantic.available)
    }

    @Test
    fun `symbol resolve returns requested declaration documentation and surrounding text`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "greet",
                    fileHint = file.toString(),
                    includeDeclarationScope = true,
                    includeDocumentation = true,
                    surroundingLines = 2,
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        assertEquals("sample.greet", success.symbol.fqName)
        assertTrue(checkNotNull(success.symbol.declarationScope).sourceText!!.contains("fun greet"))
        assertTrue(checkNotNull(success.symbol.documentation).contains("Greets"))
        val context = checkNotNull(success.context)
        assertTrue(checkNotNull(context.surroundingText).text.contains("fun use() = greet()"))
    }

    @Test
    fun `symbol resolve returns lightweight surrounding members`() {
        val file = sampleTypeFile()

        val result = dispatchSuccess<KastResolveResponse>(
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "FriendlyGreeter",
                    fileHint = file.toString(),
                    includeSurroundingMembers = true,
                ),
            ),
        )

        val success = result as KastResolveSuccessResponse
        val memberNames = checkNotNull(success.context?.surroundingMembers).map { it.fqName }
        assertEquals(listOf("sample.Greeter", "sample.LoudGreeter"), memberNames)
        assertTrue(success.context!!.surroundingMembers!!.all { it.declarationScope == null })
    }

    @Test
    fun `symbol rename dispatches rename apply and diagnostics`() {
        val file = sampleFile()

        val result = dispatchSuccess<KastRenameResponse>(
            method = "symbol/rename",
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
    fun `legacy rpc method names are rejected`() {
        val response = dispatchRaw("skill/resolve")

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals(-32601, error.error.code)
        assertTrue(error.error.message.contains("skill/resolve"))
    }

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
    fun `rename dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RenameResult>(
            method = "raw/rename",
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
            method = "raw/optimize-imports",
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
            method = "raw/apply-edits",
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
            method = "raw/apply-edits",
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
            method = "raw/optimize-imports",
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
            method = "raw/workspace-refresh",
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

    private fun sampleFile(): Path = tempDir.resolve("src").resolve("Sample.kt")

    private fun sampleTypeFile(): Path = tempDir.resolve("src").resolve("Types.kt")

    private fun seedSymbolQueryIndex(): SymbolQueryFixture {
        val invoiceValidatorFile = tempDir.resolve("billing/src/main/kotlin/com/acme/billing/InvoiceValidator.kt").toString()
        val invoiceServiceFile = tempDir.resolve("billing/src/main/kotlin/com/acme/billing/InvoiceService.kt").toString()
        val invoiceValidatorTestFile = tempDir.resolve("billing/src/test/kotlin/com/acme/billing/InvoiceValidatorTest.kt").toString()
        val paymentValidatorFile = tempDir.resolve("payments/src/main/kotlin/com/acme/payments/PaymentValidator.kt").toString()
        val defaultPaymentValidatorFile = tempDir.resolve("payments/src/main/kotlin/com/acme/payments/DefaultPaymentValidator.kt").toString()
        val otherValidatorFile = tempDir.resolve("other/src/main/kotlin/com/acme/other/OtherValidator.kt").toString()
        val files = listOf(
            invoiceValidatorFile,
            invoiceServiceFile,
            invoiceValidatorTestFile,
            paymentValidatorFile,
            defaultPaymentValidatorFile,
            otherValidatorFile,
        )
        SqliteSourceIndexStore(tempDir.toAbsolutePath().normalize()).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(
                        path = invoiceValidatorFile,
                        identifiers = setOf("InvoiceValidator", "validateLineItem", "validation"),
                        packageName = "com.acme.billing",
                        modulePath = ":billing",
                        sourceSet = "main",
                    ),
                    fileUpdate(
                        path = invoiceServiceFile,
                        identifiers = setOf("InvoiceService", "createInvoice", "validateLineItem"),
                        packageName = "com.acme.billing",
                        modulePath = ":billing",
                        sourceSet = "main",
                    ),
                    fileUpdate(
                        path = invoiceValidatorTestFile,
                        identifiers = setOf("InvoiceValidatorTest", "validateLineItem"),
                        packageName = "com.acme.billing",
                        modulePath = ":billing",
                        sourceSet = "test",
                    ),
                    fileUpdate(
                        path = paymentValidatorFile,
                        identifiers = setOf("PaymentValidator"),
                        packageName = "com.acme.payments",
                        modulePath = ":payments",
                        sourceSet = "main",
                    ),
                    fileUpdate(
                        path = defaultPaymentValidatorFile,
                        identifiers = setOf("DefaultPaymentValidator", "PaymentValidator"),
                        packageName = "com.acme.payments",
                        modulePath = ":payments",
                        sourceSet = "main",
                    ),
                    fileUpdate(
                        path = otherValidatorFile,
                        identifiers = setOf("OtherValidator", "validateExternal"),
                        packageName = "com.acme.other",
                        modulePath = ":other",
                        sourceSet = "main",
                    ),
                ),
                manifest = files.associateWith { 1L },
            )
            store.replaceDeclarationsFromFiles(
                listOf(
                    invoiceValidatorFile to listOf(
                        declaration(
                            fqName = "com.acme.billing.InvoiceValidator.validateLineItem",
                            kind = DeclarationKind.FUNCTION,
                            visibility = DeclarationVisibility.INTERNAL,
                            filePath = invoiceValidatorFile,
                            modulePath = ":billing",
                            sourceSet = "main",
                            offset = 1824,
                        ),
                    ),
                    invoiceServiceFile to listOf(
                        declaration(
                            fqName = "com.acme.billing.InvoiceService.createInvoice",
                            kind = DeclarationKind.FUNCTION,
                            visibility = DeclarationVisibility.PUBLIC,
                            filePath = invoiceServiceFile,
                            modulePath = ":billing",
                            sourceSet = "main",
                            offset = 7441,
                        ),
                    ),
                    invoiceValidatorTestFile to listOf(
                        declaration(
                            fqName = "com.acme.billing.InvoiceValidatorTest.validatesLineItems",
                            kind = DeclarationKind.FUNCTION,
                            visibility = DeclarationVisibility.PUBLIC,
                            filePath = invoiceValidatorTestFile,
                            modulePath = ":billing",
                            sourceSet = "test",
                            offset = 320,
                        ),
                    ),
                    paymentValidatorFile to listOf(
                        declaration(
                            fqName = "com.acme.payments.PaymentValidator",
                            kind = DeclarationKind.INTERFACE,
                            visibility = DeclarationVisibility.PUBLIC,
                            filePath = paymentValidatorFile,
                            modulePath = ":payments",
                            sourceSet = "main",
                            offset = 100,
                        ),
                    ),
                    defaultPaymentValidatorFile to listOf(
                        declaration(
                            fqName = "com.acme.payments.DefaultPaymentValidator",
                            kind = DeclarationKind.CLASS,
                            visibility = DeclarationVisibility.PUBLIC,
                            filePath = defaultPaymentValidatorFile,
                            modulePath = ":payments",
                            sourceSet = "main",
                            offset = 150,
                            supertypes = listOf("com.acme.payments.PaymentValidator"),
                        ),
                    ),
                    otherValidatorFile to listOf(
                        declaration(
                            fqName = "com.acme.other.OtherValidator.validateExternal",
                            kind = DeclarationKind.FUNCTION,
                            visibility = DeclarationVisibility.PUBLIC,
                            filePath = otherValidatorFile,
                            modulePath = ":other",
                            sourceSet = "main",
                            offset = 500,
                        ),
                    ),
                ),
            )
            store.replaceReferencesFromFiles(
                listOf(
                    invoiceServiceFile to listOf(
                        reference(
                            sourcePath = invoiceServiceFile,
                            sourceOffset = 7441,
                            sourceFqName = "com.acme.billing.InvoiceService.createInvoice",
                            targetFqName = "com.acme.billing.InvoiceValidator.validateLineItem",
                            targetPath = invoiceValidatorFile,
                            edgeKind = EdgeKind.CALL,
                        ),
                    ),
                    invoiceValidatorTestFile to listOf(
                        reference(
                            sourcePath = invoiceValidatorTestFile,
                            sourceOffset = 333,
                            sourceFqName = "com.acme.billing.InvoiceValidatorTest.validatesLineItems",
                            targetFqName = "com.acme.billing.InvoiceValidator.validateLineItem",
                            targetPath = invoiceValidatorFile,
                            edgeKind = EdgeKind.CALL,
                        ),
                    ),
                    defaultPaymentValidatorFile to listOf(
                        reference(
                            sourcePath = defaultPaymentValidatorFile,
                            sourceOffset = 160,
                            sourceFqName = "com.acme.payments.DefaultPaymentValidator",
                            targetFqName = "com.acme.payments.PaymentValidator",
                            targetPath = paymentValidatorFile,
                            edgeKind = EdgeKind.INHERITANCE,
                        ),
                    ),
                ),
            )
        }
        return SymbolQueryFixture(invoiceValidatorFile = invoiceValidatorFile)
    }

    private fun fileUpdate(
        path: String,
        identifiers: Set<String>,
        packageName: String,
        modulePath: String,
        sourceSet: String,
    ): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = identifiers,
            packageName = packageName,
            modulePath = modulePath,
            sourceSet = sourceSet,
            imports = emptySet(),
            wildcardImports = emptySet(),
        )

    private fun declaration(
        fqName: String,
        kind: DeclarationKind,
        visibility: DeclarationVisibility,
        filePath: String,
        modulePath: String,
        sourceSet: String,
        offset: Int,
        supertypes: List<String> = emptyList(),
    ): DeclarationRow =
        DeclarationRow(
            fqName = fqName,
            kind = kind,
            visibility = visibility,
            filePath = filePath,
            declarationOffset = offset,
            modulePath = modulePath,
            sourceSet = sourceSet,
            supertypes = supertypes,
        )

    private fun reference(
        sourcePath: String,
        sourceOffset: Int,
        sourceFqName: String,
        targetFqName: String,
        targetPath: String,
        edgeKind: EdgeKind,
    ): SymbolReferenceRow =
        SymbolReferenceRow(
            sourcePath = sourcePath,
            sourceOffset = sourceOffset,
            sourceFqName = sourceFqName,
            targetFqName = targetFqName,
            targetPath = targetPath,
            targetOffset = 1,
            edgeKind = edgeKind,
        )

    private fun dispatcher(): RpcAnalysisDispatcher = RpcAnalysisDispatcher(
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

    private fun classFileText(clazz: Class<*>): String =
        checkNotNull(clazz.getResourceAsStream("${clazz.simpleName}.class")) {
            "Missing class file resource for ${clazz.name}"
        }.use { input ->
            String(input.readBytes(), StandardCharsets.ISO_8859_1)
        }

    private data class SymbolQueryFixture(
        val invoiceValidatorFile: String,
    )
}
