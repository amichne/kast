package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.QueryRejectionCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactAccountingWireTest {
    @Test
    fun `required accounting shape is identical in wire and CLI`() {
        val result = result()
        val encoded = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        val expected = Json.parseToJsonElement(javaClass.getResource("/impact-accounting.expected.json")!!.readText())
        assertEquals(expected, encoded.value.jsonObject.getValue("impact_accounting"))
        assertEquals(
            result,
            (CanonicalQuerySerializers.result.decode(encoded.value, WireValueRole.RESULT) as WireDecoding.Decoded)
                .value,
        )
        val cli = CanonicalQueryCliDocuments.project(outcome(result)) as ProjectedOperationOutcome.Qualified
        val document = Json.parseToJsonElement(cli.document.present(ToolOutputDetail.VERBOSE).value)
        assertEquals(expected, document.jsonObject.getValue("impact_accounting"))
    }

    @Test
    fun `canonical encoding decoding and CLI preserve exact cross field rejection`() {
        val valid = result()
        val encoded =
            (CanonicalQuerySerializers.result.encode(valid, WireValueRole.RESULT) as WireValueEncoding.Encoded)
                .value
                .toString()
        val cases =
            listOf(
                valid.copy(impactAccounting = ImpactAccountingDocument.NotApplicable) to
                    ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING,
                valid.copy(impactAccounting = ImpactAccountingDocument.EvidenceOnly(count(2))) to
                    ImpactAccountingFailure.PAGE_COUNT_MISMATCH,
            )
        for ((invalid, cause) in cases) {
            assertEquals(
                WireValueEncoding.Rejected(WireFailure.InvalidImpactAccounting(cause)),
                CanonicalQuerySerializers.result.encode(invalid, WireValueRole.RESULT),
            )
            val malformed =
                when (cause) {
                    ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING ->
                        encoded.replace("\"type\":\"EVIDENCE_ONLY\",\"pagePathCount\":1", "\"type\":\"NOT_APPLICABLE\"")
                    else -> encoded.replace("\"pagePathCount\":1", "\"pagePathCount\":2")
                }
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidImpactAccounting(cause)),
                CanonicalQuerySerializers.result.decode(Json.parseToJsonElement(malformed), WireValueRole.RESULT),
            )
            val cli = CanonicalQueryCliDocuments.project(outcome(invalid)) as ProjectedOperationOutcome.Rejected
            val document = Json.parseToJsonElement(cli.document.present(ToolOutputDetail.VERBOSE).value).jsonObject
            val reason = document.getValue("rejection").jsonObject
            assertEquals("IMPACT_PRESENTATION_REJECTED", reason.getValue("type").jsonPrimitive.content)
            assertEquals("ACCOUNTING", reason.getValue("cause").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(cause.name, reason.getValue("cause").jsonObject.getValue("cause").jsonPrimitive.content)
        }
    }

    @Test
    fun `missing mandatory accounting and unknown discriminator fail raw decode`() {
        val encoded =
            (CanonicalQuerySerializers.result.encode(result(), WireValueRole.RESULT) as WireValueEncoding.Encoded)
                .value
                .toString()
        for (malformed in
            listOf(
                encoded.replace("\"impact_accounting\":{\"type\":\"EVIDENCE_ONLY\",\"pagePathCount\":1},", ""),
                encoded.replace("EVIDENCE_ONLY", "ASSUMED_COMPLETE"),
            )) {
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                CanonicalQuerySerializers.result.decode(Json.parseToJsonElement(malformed), WireValueRole.RESULT),
            )
        }
    }

    @Test
    fun `source admission and exact reference failure preserve typed causes through wire and CLI`() {
        val cases =
            listOf(
                QueryImpactSourceFailureDocument.Admission(QueryImpactSourceFailureCode.ANCHOR_MISMATCH, offset(2)),
                QueryImpactSourceFailureDocument.Reference(
                    QueryReferenceRejectionReason.REVALIDATION_EXPIRED,
                    offset(3),
                ),
            )
        val expected =
            Json.parseToJsonElement(javaClass.getResource("/impact-source-rejections.expected.json")!!.readText())
                .jsonArray
        for ((index, cause) in cases.withIndex()) {
            val rejection = QueryRunRejection.ImpactSourceRejected(cause)
            val encoded =
                CanonicalQuerySerializers.rejection.encode(rejection, WireValueRole.REJECTION)
                    as WireValueEncoding.Encoded
            assertEquals(expected[index], encoded.value)
            assertEquals(
                expected[index],
                Json.encodeToJsonElement(QueryRejectionCliDocument.serializer(), rejection.toCliDocument()),
            )
            assertEquals(
                rejection,
                (CanonicalQuerySerializers.rejection.decode(encoded.value, WireValueRole.REJECTION)
                        as WireDecoding.Decoded)
                    .value,
            )
        }
    }

    @Test
    fun `outer complete encode decode and CLI reject evidence only paths with exact cause`() {
        val result = result()
        val complete: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure> =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    EvidenceBasis.Published(EvidenceGeneration.parse(7).value()),
                    result,
                )
            )
        val failure = WireFailure.InvalidImpactAccounting(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED)
        assertEquals(WireEncoding.Rejected(failure), CanonicalOperationWireBindings.queryRun.encodeOutcome(complete))
        val payload =
            (CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded).value
        val envelope =
            WireEnvelopeDocument(
                CanonicalOperationWireBindings.queryRun.schema.value,
                CanonicalOperation.QUERY_RUN.id.value,
                WireBodyDocument.Complete(7, payload),
            )
        assertEquals(
            WireDecoding.Rejected(failure),
            CanonicalOperationWireBindings.queryRun.decodeOutcome(
                wireJson.encodeToString(WireEnvelopeDocument.serializer(), envelope)
            ),
        )
        val cli = CanonicalQueryCliDocuments.project(complete) as ProjectedOperationOutcome.Rejected
        val reason = Json.parseToJsonElement(cli.document.value).jsonObject.getValue("rejection").jsonObject
        assertEquals(
            "COMPLETION_NOT_CONSERVED",
            reason.getValue("cause").jsonObject.getValue("cause").jsonPrimitive.content,
        )
    }

    private fun result(): QueryRunResult {
        val site =
            ImpactValueSiteReferenceDocument(
                ImpactDeclarationReferenceDocument(
                    ImpactSemanticBasisDocument.Published(
                        text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(7).value(),
                    ),
                    text("/workspace/File.kt"),
                    ImpactSourceRangeDocument(offset(0), offset(100)),
                    text("canonical-signature-sha256-v1|" + "a".repeat(64)),
                ),
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val item =
            QueryResultItemDocument.ValuePath(
                ImpactPathDocument(
                    site,
                    bounded(emptyList()),
                    ImpactRepresentationEvidenceDocument.NotModeled,
                    ImpactPathTerminalDocument.UnresolvedFlow(site, ImpactFlowUnsupportedDocument.EXTERNAL_CALL),
                )
            )
        return QueryRunResult(
            QueryQuestionDocument(
                QueryFromDocument.Location(text("File.kt"), offset(0)),
                bounded(emptyList()),
                QueryOutputDocument.ValuePaths,
            ),
            bounded(listOf(item)),
            bounded(emptyList()),
            impactAccounting = ImpactAccountingDocument.EvidenceOnly(count(1)),
        )
    }

    private fun outcome(
        result: QueryRunResult
    ): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure> =
        OperationOutcome.Qualified(
            EvidenceEnvelope(
                CanonicalOperation.QUERY_RUN.id,
                EvidenceBasis.Published(EvidenceGeneration.parse(7).value()),
                result,
            ),
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(1).value(),
                    listOf(QueryLimitationDocument.IMPACT_COVERAGE_UNPROVEN),
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                )
                .value(),
        )

    private fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).value()

    private fun text(raw: String) = ProtocolText.parse(raw).value()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).value()

    private fun <T> bounded(raw: List<T>) = BoundedProtocolList.create(raw).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
