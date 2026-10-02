package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryExactLocationDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocument
import io.github.amichne.kast.protocol.contract.SourceLineNumberDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTextMatchWireTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `exact symbol wire preserves lexical match evidence in its own closed variant`() {
        val item = symbol(bounded(listOf(match())))
        val wire = item.toWire()
        val encoded = json.encodeToJsonElement(QueryResultItemWireDocument.serializer(), wire).jsonObject
        val evidence = encoded.getValue("matches").jsonArray.single().jsonObject
        assertEquals(setOf("type", "word", "file", "range", "context", "contextRange", "line"), evidence.keys)
        assertEquals("INDEXED_WORD", evidence.getValue("type").jsonPrimitive.content)
        assertEquals("launchd", evidence.getValue("word").jsonPrimitive.content)
        assertEquals("/workspace/app-server/Lifecycle.kt", evidence.getValue("file").jsonPrimitive.content)
        assertEquals("launchd restart", evidence.getValue("context").jsonPrimitive.content)
        assertEquals("3", evidence.getValue("line").jsonPrimitive.content)
        assertEquals("20", evidence.getValue("range").jsonObject.getValue("startInclusive").jsonPrimitive.content)
        assertEquals("27", evidence.getValue("range").jsonObject.getValue("endExclusive").jsonPrimitive.content)
        assertEquals("35", evidence.getValue("contextRange").jsonObject.getValue("endExclusive").jsonPrimitive.content)
        assertEquals(item, (wire.toContract() as WireDocumentConversion.Converted).value)
        assertTrue("relation" !in evidence)
    }

    @Test
    fun `existing exact symbol rows omit lexical match evidence`() {
        val encoded =
            json.encodeToJsonElement(QueryResultItemWireDocument.serializer(), symbol(null).toWire()).jsonObject
        assertTrue("matches" !in encoded)
    }

    @Test
    fun `lexical evidence must belong to the projected exact declaration location`() {
        val evidence = match()
        val item =
            symbol(bounded(listOf(evidence))).copy(location = QueryExactLocationDocument(evidence.file, range(10, 40)))
        val wire = item.toWire() as QueryResultItemWireDocument.ExactSymbol
        assertEquals(item, (wire.toContract() as WireDocumentConversion.Converted).value)
        val exactBounds = item.copy(location = QueryExactLocationDocument(evidence.file, evidence.range))
        assertEquals(exactBounds, (exactBounds.toWire().toContract() as WireDocumentConversion.Converted).value)
        listOf(
                wire.copy(
                    location = QueryExactLocationWireDocument("/workspace/Other.kt", SourceRangeWireDocument(10, 40))
                ),
                wire.copy(
                    location = QueryExactLocationWireDocument(evidence.file.value, SourceRangeWireDocument(21, 40))
                ),
                wire.copy(
                    location = QueryExactLocationWireDocument(evidence.file.value, SourceRangeWireDocument(10, 26))
                ),
            )
            .forEach { invalid ->
                assertTrue(invalid.toContract() is WireDocumentConversion.Rejected, invalid.toString())
            }
    }

    @Test
    fun `malformed context ranges words and lines reject at wire refinement`() {
        val wire = match().toWireDocument()
        listOf(
                wire.copy(word = "launchd|restart"),
                wire.copy(word = "Launchd"),
                wire.copy(context = "x".repeat(513)),
                wire.copy(context = "launchd\nrestart"),
                wire.copy(contextRange = SourceRangeWireDocument(20, 36)),
                wire.copy(range = SourceRangeWireDocument(19, 27)),
                wire.copy(line = 0),
                wire.copy(file = "../Lifecycle.kt"),
                wire.copy(file = "app-server/Lifecycle.kt"),
                wire.copy(file = "/workspace/app-server/Lifecycle.kt/"),
                wire.copy(file = "/workspace/app-server/./Lifecycle.kt"),
                wire.copy(file = "/workspace/app-server/../Lifecycle.kt"),
                wire.copy(file = "/workspace//Lifecycle.kt"),
            )
            .forEach { assertTrue(it.toContract() is WireDocumentConversion.Rejected, it.toString()) }
    }

    @Test
    fun `evidence decoding rejects missing unknown discriminator and extra fields`() {
        val raw = json.encodeToJsonElement(QueryTextMatchWireDocument.serializer(), match().toWireDocument()).toString()
        listOf(
                raw.replace("\"type\":\"INDEXED_WORD\",", ""),
                raw.replace("INDEXED_WORD", "RELATION"),
                raw.replace("\"type\":", "\"relation\":\"CALLERS\",\"type\":"),
            )
            .forEach { malformed ->
                assertThrows(SerializationException::class.java) {
                    json.decodeFromString(QueryTextMatchWireDocument.serializer(), malformed)
                }
            }
    }

    @Test
    fun `text source encoding and observation preserve indexed word grammar`() {
        val source =
            QueryFromDocument.TextWord(text("launchd"), scope(), bounded(listOf(QueryDeclarationKindDocument.FUNCTION)))
        val request =
            QueryRunRequest.Run(
                source,
                bounded(emptyList()),
                QueryOutputDocument.Symbols(bounded(emptyList())),
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        val encoded = json.encodeToJsonElement(QueryRunRequest.serializer(), request).jsonObject
        val from = encoded.getValue("from").jsonObject
        assertEquals(setOf("type", "word", "scope", "declarationKinds"), from.keys)
        assertEquals("TEXT_WORD", from.getValue("type").jsonPrimitive.content)
        assertEquals("launchd", from.getValue("word").jsonPrimitive.content)
        val observation =
            json
                .encodeToJsonElement(QueryMatchDocument.serializer(), QueryMatchDocument.TextWord(text("launchd")))
                .jsonObject
        assertEquals(setOf("type", "word"), observation.keys)
        assertEquals("TEXT_WORD", observation.getValue("type").jsonPrimitive.content)
        assertTrue(CanonicalOperationWireBindings.queryRun.encodeRequest(request) is WireEncoding.Encoded)
        listOf("restart lifecycle", "AppServerAction.Disable", "launchd|restart", "a".repeat(257)).forEach { word ->
            val invalid = request.copy(from = source.copy(word = text(word)))
            assertTrue(CanonicalOperationWireBindings.queryRun.encodeRequest(invalid) is WireEncoding.Rejected, word)
        }
    }

    private fun match() =
        QueryTextMatchDocument.IndexedWord.create(
                text("launchd"),
                text("/workspace/app-server/Lifecycle.kt"),
                range(20, 27),
                (ProtocolSourceText.parse("launchd restart") as Refinement.Refined).value,
                range(20, 35),
                (SourceLineNumberDocument.parse(3) as Refinement.Refined).value,
            )
            .let { (it as Refinement.Refined).value }

    private fun symbol(matches: BoundedProtocolList<QueryTextMatchDocument>?) =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque")),
            SymbolKindDocument.FUNCTION,
            null,
            null,
            null,
            bounded(emptyList()),
            (SymbolIdDocument.parse("sym:" + "A".repeat(43)) as Refinement.Refined).value,
            matches = matches,
        )

    private fun scope() =
        QueryScopeDocument(
            bounded(listOf(text("main"))),
            QueryDirectoryScopeDocument(text("app-server"), QueryContainmentDocument.DESCENDANTS),
            null,
        )

    private fun range(start: Int, end: Int) =
        (SourceRangeDocument.create(
                (ProtocolOffset.parse(start) as Refinement.Refined).value,
                (ProtocolOffset.parse(end) as Refinement.Refined).value,
            ) as Refinement.Refined)
            .value

    private fun text(raw: String) = (ProtocolText.parse(raw) as Refinement.Refined).value

    private fun <Value> bounded(values: List<Value>) = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
