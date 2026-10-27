package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicCommonCatalogTest {
    @Test
    fun `every generated common topic example is admitted by its advertised catalog`() {
        val examples = PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS, PublicToolCatalog.COMMON)
        assertEquals(6, examples.examples.size)
        examples.examples.forEach { (name, example) ->
            assertTrue(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, example.value, PublicToolCatalog.COMMON) is Refinement.Refined, name)
        }
    }
    @Test
    fun `common discovery preserves independently expected canonical scope fields and execution`() {
        val source = PublicToolSearchSource(
            text("payloadTarget"), declarationKinds = bounded(listOf(PublicToolDeclarationKinds.FUNCTION)),
            scope = PublicToolPackageScope(text("repro.payload"), false, bounded(listOf(text("main")))),
        )
        val input = PublicToolQuerySymbols(PublicToolRunAction(source, terminalStep = PublicToolEmit(
            PublicToolSymbolsOutput(bounded(listOf(PublicToolFields.NAME, PublicToolFields.LOCATION, PublicToolFields.SIGNATURE))))))
        val expected = QueryFromDocument.Symbols(
            QueryMatchDocument.Name(text("payloadTarget"), SymbolDiscoveryMatchDocument.EXACT_NAME),
            QueryScopeDocument(bounded(listOf(text("main"))), null,
                QueryPackageScopeDocument(text("repro.payload"), QueryContainmentDocument.DIRECT)),
            bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
        )
        for (catalog in PublicToolCatalog.entries) {
            val admitted = admit(input, catalog)
            assertEquals(catalog, admitted.catalog)
            val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(expected, run.from)
            assertEquals(emptyList<QueryStepDocument>(), run.steps.values)
            assertEquals(listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION, QuerySymbolFieldDocument.SIGNATURE),
                (run.output as QueryOutputDocument.Symbols).fields.values)
            assertEquals(QueryExecutionModeDocument.RUNNER_CONTROLLED, run.executionMode)
            assertEquals(QueryRetentionModeDocument.DISCARD, run.retention)
        }
    }

    @Test
    fun `common exact reference and trace preserve canonical identity and relation order`() {
        val reference = text("exact:v5:NON_ISSUED_SCHEMA_ONLY")
        val source = PublicToolReferenceSource(bounded(listOf(reference)))
        val step = PublicToolExpandRelation(PublicToolRelation.CALLERS)
        val input = PublicToolQuerySymbols(PublicToolRunAction(source, bounded(listOf(step)), PublicToolTrace()))
        for (catalog in PublicToolCatalog.entries) {
            val run = ((admit(input, catalog).canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run)
            assertEquals(QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(reference)))), run.from)
            assertEquals(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS, QueryExpansionScopeDocument.Workspace),
                QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace)), run.steps.values)
        }
    }

    @Test
    fun `common rejects advanced variants while complete preserves the grammar`() {
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolAllSource(), terminalStep = PublicToolEmit()))
        val raw = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        assertTrue(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw, PublicToolCatalog.COMMON) is Refinement.Rejected)
        assertTrue(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw, PublicToolCatalog.COMPLETE) is Refinement.Refined)
        for (malformed in listOf(
            """{"request":{"type":"RUN","source":{"type":"SYMBOL_REFS","symbolRefs":["result:v1:00000000-0000-0000-0000-000000000000"]},"terminalStep":{"type":"EMIT"}}}""",
            """{"request":{"type":"RUN","source":{"type":"AT_LOCATION","file":"Fixture.kt","offset":0},"terminalStep":{"type":"EMIT"},"unknown":true}}""",
            """{"request":{"type":"RUN","source":{"type":"AT_LOCATION","file":"Fixture.kt","offset":0},"terminalStep":{"type":"EMIT"},"executionMode":"BOUNDED"}}""",
        )) assertTrue(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(malformed), PublicToolCatalog.COMMON) is Refinement.Rejected)
    }

    @Test
    fun `validated common values cannot cross catalog schema identity`() {
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolLocationSource(text("Fixture.kt"), 0), terminalStep = PublicToolEmit()))
        val raw = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        val common = PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS, PublicToolCatalog.COMMON)
        val complete = PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS, PublicToolCatalog.COMPLETE)
        assertNotEquals(common.digest, complete.digest)
        val validated = (common.admit(raw) as Validation.Validated).value
        assertEquals(Refinement.Rejected(PublicToolInputFailure.SchemaMismatch), PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, validated, PublicToolCatalog.COMPLETE))
    }

    @Test
    fun `common encoding cannot advertise removed null budget fields`() {
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolLocationSource(text("Fixture.kt"), 0),
            terminalStep = PublicToolEmit(), executionBudget = PublicToolExecutionBudget(maxReturnedBytes = 4096)))
        val admitted = admit(input, PublicToolCatalog.COMMON)
        val encoded = PublicToolContract.encode(admitted)
        val readmitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded, PublicToolCatalog.COMMON)
        assertTrue(readmitted is Refinement.Refined)
        assertEquals(admitted.canonical, (readmitted as Refinement.Refined).value.canonical)
    }

    private fun admit(input: PublicToolQuerySymbols, catalog: PublicToolCatalog): AdmittedPublicTool =
        (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS,
            Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input), catalog) as Refinement.Refined).value

    private fun text(value: String): ProtocolText = (ProtocolText.parse(value) as Refinement.Refined).value
    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
