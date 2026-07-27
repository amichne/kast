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

class AnalysisDispatcherSymbolResolutionTest : AnalysisDispatcherTestSupport() {
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
    fun `symbol resolve returns not found instead of a fuzzy candidate`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(lookupSymbol("sample.LegacyOrderService", SymbolKind.CLASS, "LegacyOrderService.kt")),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "MissingOrderService"),
            ),
        )

        assertInstanceOf(KastResolveNotFoundResponse::class.java, result)
    }

    @Test
    fun `symbol resolve returns ambiguous for overloaded exact members`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 10),
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 40),
            ),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "parse"),
            ),
        )

        val ambiguous = assertInstanceOf(KastResolveAmbiguousResponse::class.java, result)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `symbol resolve cardinality is independent of server presentation limit`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 10),
                lookupSymbol("sample.Parser.parse", SymbolKind.FUNCTION, "Parser.kt", startOffset = 40),
            ),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            config = AnalysisServerConfig(maxResults = 1),
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "parse"),
            ),
        )

        val ambiguous = assertInstanceOf(KastResolveAmbiguousResponse::class.java, result)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `symbol resolve matches backticked simple and qualified names exactly`() {
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(lookupSymbol("sample.when", SymbolKind.FUNCTION, "Keywords.kt")),
        )

        val simple = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "`when`"),
            ),
        )
        val qualified = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(workspaceRoot = tempDir.toString(), symbol = "sample.`when`"),
            ),
        )

        assertEquals("sample.when", assertInstanceOf(KastResolveSuccessResponse::class.java, simple).symbol.fqName)
        assertEquals("sample.when", assertInstanceOf(KastResolveSuccessResponse::class.java, qualified).symbol.fqName)
    }

    @Test
    fun `symbol resolve applies kind file and containing type as hard constraints`() {
        val fileName = "Parser.kt"
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(
                lookupSymbol(
                    fqName = "sample.Parser.parse",
                    kind = SymbolKind.FUNCTION,
                    fileName = fileName,
                    containingDeclaration = "sample.Parser",
                ),
            ),
        )
        val mismatches = listOf(
            KastResolveRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "parse",
                kind = WrapperNamedSymbolKind.CLASS,
            ),
            KastResolveRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "parse",
                fileHint = tempDir.resolve("Other.kt").toString(),
            ),
            KastResolveRequest(
                workspaceRoot = tempDir.toString(),
                symbol = "parse",
                containingType = "sample.OtherParser",
            ),
        )

        mismatches.forEach { request ->
            val result = dispatchSuccessWithBackend<KastResolveResponse>(
                backend = backend,
                method = "symbol/resolve",
                params = json.encodeToJsonElement(KastResolveRequest.serializer(), request),
            )
            assertInstanceOf(KastResolveNotFoundResponse::class.java, result)
        }
    }

    @Test
    fun `symbol resolve applies containing type using resolved compiler identity`() {
        val workspaceSymbol = lookupSymbol(
            fqName = "sample.Parser.parse",
            kind = SymbolKind.FUNCTION,
            fileName = "Parser.kt",
            containingDeclaration = null,
        )
        val resolvedSymbol = workspaceSymbol.copy(containingDeclaration = "sample.Parser")
        val backend = ExactLookupBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            symbols = listOf(workspaceSymbol),
            resolvedSymbols = listOf(resolvedSymbol),
        )

        val result = dispatchSuccessWithBackend<KastResolveResponse>(
            backend = backend,
            method = "symbol/resolve",
            params = json.encodeToJsonElement(
                KastResolveRequest.serializer(),
                KastResolveRequest(
                    workspaceRoot = tempDir.toString(),
                    symbol = "parse",
                    containingType = "sample.Parser",
                ),
            ),
        )

        val success = assertInstanceOf(KastResolveSuccessResponse::class.java, result)
        assertEquals("sample.Parser", success.symbol.containingDeclaration)
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
}
