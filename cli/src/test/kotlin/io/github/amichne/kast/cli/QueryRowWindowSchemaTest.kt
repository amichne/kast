package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryRowWindowSchemaTest {
    @Test
    fun `both public query outcome schemas retain required closed row window shape`() {
        for (serializer in
            listOf(CanonicalQueryCliDocuments.completeSerializer, CanonicalQueryCliDocuments.qualifiedSerializer)) {
            val schema =
                generatedOutputPropertySchema(serializer, "row_window").getValue("anyOf").jsonArray.first().jsonObject
            val properties = schema.getValue("properties").jsonObject
            assertEquals(setOf("type", "start", "end", "total"), properties.keys)
            assertEquals(
                setOf("type", "start", "end", "total"),
                schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
            assertEquals(
                listOf("MORE", "FINAL"),
                properties.getValue("type").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
            )
            for (name in listOf("start", "end", "total")) {
                val position = properties.getValue(name).jsonObject
                assertEquals("integer", position.getValue("type").jsonPrimitive.content)
                assertEquals("0", position.getValue("minimum").jsonPrimitive.content)
                assertEquals("1000000", position.getValue("maximum").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `advertised canonical and facade schemas admit actual row windows and reject malformed windows`() {
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val validators =
            listOf(
                    installedSemanticResultSchema(CanonicalOperation.QUERY_RUN),
                    installedPublicToolSemanticResultSchema(PublicToolIdentity.QUERY_SYMBOLS),
                )
                .map { registry.getSchema(it.toString()) }
        for (total in listOf(0, 1)) {
            val result = result(total)
            val kind = if (total == 0) "FINAL" else "MORE"
            val envelope =
                EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, EvidenceGeneration.parse(1).value(), result)
            val qualification =
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(0).value(),
                        listOf(QueryLimitationDocument.RELATION_INCOMPLETE),
                        QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                    )
                    .value()
            val outcomes =
                listOf(OperationOutcome.Complete(envelope), OperationOutcome.Qualified(envelope, qualification))
            for (outcome in outcomes) {
                val projected = CanonicalQueryCliDocuments.project(outcome)
                val document =
                    when (projected) {
                        is ProjectedOperationOutcome.Complete -> projected.document
                        is ProjectedOperationOutcome.Qualified -> projected.document
                        is ProjectedOperationOutcome.Rejected -> error("Unexpected row projection rejection")
                    }
                for (detail in ToolOutputDetail.entries) {
                    assertWindowSchemas(validators, document.present(detail).value, kind)
                }
            }
        }
    }

    private fun assertWindowSchemas(validators: List<Schema>, encoded: String, kind: String) {
        val window = Json.parseToJsonElement(encoded).jsonObject.getValue("row_window").toString()
        for (validator in validators) {
            assertTrue(validator.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
            for (malformed in
                listOf(
                    window.replace("\"$kind\"", "\"UNKNOWN\""),
                    window.replace("\"type\":\"$kind\",", ""),
                    window.replace("\"start\":0", "\"start\":-1"),
                    window.replace("\"end\":0", "\"end\":1000001"),
                    window.replace("\"end\":0", "\"end\":null"),
                    window.replace("\"end\":0", "\"end\":\"0\""),
                    window.replace("\"end\":0", "\"end\":0,\"unknown\":true"),
                )) {
                assertTrue(
                    validator.validate(encoded.replace(window, malformed), InputFormat.JSON).isNotEmpty(),
                    malformed,
                )
            }
        }
    }

    private fun result(total: Int): QueryRunResult {
        val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").value()
        val window =
            QueryRetainedPresentationWindow.create(
                    reference,
                    QueryResultCursor.Start,
                    QueryResultCursor.Start,
                    QueryResultCursor.parse(total).value(),
                )
                .value()
        return QueryRunResult(
            question =
                QueryQuestionDocument(
                    QueryFromDocument.Location(ProtocolText.parse("File.kt").value(), ProtocolOffset.parse(0).value()),
                    bounded(emptyList()),
                    QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
            items = bounded(emptyList()),
            failures = bounded(emptyList()),
            retention = QueryResultRetention.Retained(reference),
            presentationWindow = window,
            nextCursor = window.nextCursor,
        )
    }

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
