package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuerySourceEnumerationQualificationTest {
    @Test
    fun `source enumeration failure retains finite original qualification through wire and public projection`() {
        val reference = QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque"))
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCountDocument.parse(3).refined(),
                    listOf(SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE),
                    SourceQualifiedProgressDocument.TerminalIncomplete(
                        SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE
                    ),
                )
                .refined()
        val result =
            QueryRunResult(
                QueryQuestionDocument(
                    QueryFromDocument.References(bounded(listOf(reference))),
                    bounded(emptyList()),
                    QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                bounded(emptyList()),
                bounded(listOf(QueryItemFailureDocument.SourceEnumerationIncomplete(reference, qualification))),
            )
        val encoded = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        val failure = encoded.value.jsonObject.getValue("failures").jsonArray.single().jsonObject
        assertEquals("SOURCE_ENUMERATION_INCOMPLETE", failure.getValue("type").jsonPrimitive.content)
        assertQualification(failure)
        val decoded =
            CanonicalQuerySerializers.result.decode(encoded.value, WireValueRole.RESULT) as WireDecoding.Decoded
        assertEquals(result, decoded.value)
        val projected =
            CanonicalQueryCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.QUERY_RUN.id,
                        EvidenceBasis.Published(EvidenceGeneration.parse(1).refined()),
                        result,
                    )
                )
            ) as ProjectedOperationOutcome.Complete
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        val publicFailure = document.getValue("failures").jsonArray.single().jsonObject
        assertEquals("SOURCE_ENUMERATION_INCOMPLETE", publicFailure.getValue("type").jsonPrimitive.content)
        assertQualification(publicFailure)
    }

    private fun assertQualification(failure: kotlinx.serialization.json.JsonObject) {
        val progress = failure.getValue("qualification").jsonObject
        assertEquals("3", progress.getValue("knownMinimumEntityCount").jsonPrimitive.content)
        assertEquals(
            listOf("semantic-resolution-incomplete"),
            progress.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "terminal_incomplete",
            progress.getValue("progress").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun <V> bounded(values: List<V>) = BoundedProtocolList.create(values).refined()

    private fun <V> Refinement<V, *>.refined(): V = (this as Refinement.Refined).value
}
