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
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySourceWindowDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolIdDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuerySourceWindowOutputSchemaTest {
    @Test
    fun `exact query source window satisfies the installed output schema`() {
        val source =
            QuerySourceWindowDocument(
                ProtocolSourceText.parse("fun payment() = 1\n").refined(),
                SourceLineRangeDocument.parse(1, 1).refined(),
            )
        val item =
            QueryResultItemDocument.ExactSymbol(
                QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v2:opaque").refined()),
                SymbolKindDocument.FUNCTION,
                null,
                null,
                null,
                BoundedProtocolList.create(emptyList<RelationFactDocument>()).refined(),
                SymbolIdDocument.parse("sym:" + "A".repeat(43)).refined(),
                source,
            )
        val result =
            QueryRunResult(
                BoundedProtocolList.create<QueryResultItemDocument>(listOf(item)).refined(),
                BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).refined(),
            )
        val live =
            EvidenceBasis.Live(
                LiveReadEvidence.create(
                        "/workspace",
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        7,
                        LiveReadContentView.SAVED_PSI_COMMITTED,
                        1,
                    )
                    .refined()
            )
        val outcome = OperationOutcome.Complete(EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, live, result))
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        val errors =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(installedServerOutputSchema(CanonicalOperation.QUERY_RUN).toString())
                .validate(completedSchemaEnvelope(document), InputFormat.JSON)
        assertTrue(errors.isEmpty(), "query output rejected its source window: $errors")
        val window = document.getValue("items").jsonArray.single().jsonObject.getValue("source").jsonObject
        assertEquals("fun payment() = 1\n", window.getValue("text").jsonPrimitive.content)
        assertEquals(1L, window.getValue("startLine").jsonPrimitive.long)
        assertEquals(1L, window.getValue("endLine").jsonPrimitive.long)
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
