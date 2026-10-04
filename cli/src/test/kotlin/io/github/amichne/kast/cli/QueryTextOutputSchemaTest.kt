package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocument
import io.github.amichne.kast.protocol.contract.SourceLineNumberDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTextOutputSchemaTest {
    @Test
    fun `installed output schema admits exact symbols with bounded typed lexical evidence`() {
        val outcome = completeOutcome(symbol(match()))
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(installedServerOutputSchema(CanonicalOperation.QUERY_RUN).toString())
        assertTrue(schema.validate(completedSchemaEnvelope(document), InputFormat.JSON).isEmpty())
        val matches = document.getValue("items").jsonArray.single().jsonObject.getValue("matches").jsonArray
        assertEquals("INDEXED_WORD", matches.single().jsonObject.getValue("type").jsonPrimitive.content)
        val valid = completedSchemaEnvelope(document)
        listOf(
                valid.replace("INDEXED_WORD", "OCCURRENCE"),
                valid.replace("launchd restart", "x".repeat(513)),
                valid.replace("\"line\":3", "\"line\":0"),
                valid.replace("\"word\":\"launchd\"", "\"word\":\"launchd|restart\""),
                valid.replace("/workspace/app-server/Lifecycle.kt", "app-server/Lifecycle.kt"),
                valid.replace("/workspace/app-server/Lifecycle.kt", "/workspace/app-server/./Lifecycle.kt"),
                valid.replace("/workspace/app-server/Lifecycle.kt", "/workspace/app-server/Lifecycle.kt/"),
            )
            .forEach { assertTrue(schema.validate(it, InputFormat.JSON).isNotEmpty(), it) }
    }

    @Test
    fun `canonical text request schema uses a required enum discriminator and closes source fields`() {
        val request = textRequest()
        val raw = Json.encodeToJsonElement(QueryRunRequest.serializer(), request).toString()
        val contract = generatedRequestSchema(QueryRunRequest.serializer())
        val sourceContract = generatedRequestSchema(QueryFromDocument.serializer())
        val variant =
            sourceContract
                .getValue("anyOf")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content == "TEXT_WORD"
                }
                .jsonObject
        assertEquals(
            "type",
            sourceContract.getValue("discriminator").jsonObject.getValue("propertyName").jsonPrimitive.content,
        )
        assertTrue(variant.getValue("required").jsonArray.any { it.jsonPrimitive.content == "type" })
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(contract.toString())
        assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty())
        listOf(
                raw.replace("TEXT_WORD", "UNKNOWN"),
                raw.replace("\"word\":\"launchd\"", "\"word\":\"launchd|restart\""),
                raw.replace("\"word\":", "\"regex\":true,\"word\":"),
            )
            .forEach { assertTrue(schema.validate(it, InputFormat.JSON).isNotEmpty()) }
    }

    private fun textRequest() =
        QueryRunRequest.Run(
            QueryFromDocument.TextWord(
                text("launchd"),
                QueryScopeDocument(
                    bounded(listOf(text("main"))),
                    QueryDirectoryScopeDocument(text("app-server"), QueryContainmentDocument.DESCENDANTS),
                    null,
                ),
                bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun match() =
        QueryTextMatchDocument.IndexedWord.create(
                text("launchd"),
                text("/workspace/app-server/Lifecycle.kt"),
                range(20, 27),
                ProtocolSourceText.parse("launchd restart").refined(),
                range(20, 35),
                SourceLineNumberDocument.parse(3).refined(),
            )
            .refined()

    private fun symbol(match: QueryTextMatchDocument) =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque")),
            SymbolKindDocument.FUNCTION,
            null,
            null,
            null,
            bounded(emptyList()),
            SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
            matches = bounded(listOf(match)),
        )

    private fun completeOutcome(item: QueryResultItemDocument.ExactSymbol) =
        OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.QUERY_RUN.id,
                EvidenceBasis.Live(
                    LiveReadEvidence.create(
                            "/workspace",
                            UUID.fromString("00000000-0000-0000-0000-000000000001"),
                            7,
                            LiveReadContentView.SAVED_PSI_COMMITTED,
                            1,
                        )
                        .refined()
                ),
                QueryRunResult(fixtureQueryQuestion(), bounded(listOf(item)), bounded(emptyList())),
            )
        )

    private fun range(start: Int, end: Int) =
        SourceRangeDocument.create(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined()).refined()

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}

private fun fixtureQueryQuestion(): io.github.amichne.kast.protocol.contract.QueryQuestionDocument {
    fun <Value, Failure> fixtureValue(value: io.github.amichne.kast.kernel.Refinement<Value, Failure>): Value =
        when (value) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value.value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Invalid question fixture: ${value.failure}")
        }
    return io.github.amichne.kast.protocol.contract.QueryQuestionDocument(
        io.github.amichne.kast.protocol.contract.QueryFromDocument.Location(
            fixtureValue(io.github.amichne.kast.protocol.contract.ProtocolText.parse("Fixture.kt")),
            fixtureValue(io.github.amichne.kast.protocol.contract.ProtocolOffset.parse(0)),
        ),
        fixtureValue(io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(emptyList())),
        io.github.amichne.kast.protocol.contract.QueryOutputDocument.Symbols(
            fixtureValue(
                io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                    listOf(io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument.NAME)
                )
            )
        ),
    )
}
