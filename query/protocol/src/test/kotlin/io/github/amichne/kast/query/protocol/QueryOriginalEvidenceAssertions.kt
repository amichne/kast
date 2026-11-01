package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryEvidenceFailureKind
import io.github.amichne.kast.protocol.contract.QueryEvidenceSummaryState
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun assertAccumulatedOriginalEvidence(rejection: QueryRunRejection.CompletionUnproven) {
    val evidence = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, rejection.evidence)
    val summary =
        assertInstanceOf(QueryEvidenceSummaryState.Captured::class.java, evidence.originalEvidenceSummary).summary
    assertEquals(1100, summary.failureCount.value)
    assertEquals(1100, summary.evidenceCount.value)
    assertEquals(
        listOf(QueryEvidenceFailureKind.EXACT_REFERENCE, QueryEvidenceFailureKind.PREDICATE),
        summary.failureKinds.values,
    )
    assertEquals(listOf(QueryExactFailureDocument.COMPILER_IDENTITY_UNAVAILABLE), summary.exactReasons.values)
    assertEquals(listOf(QueryPredicateFailureDocument.PREDICATE_UNPROVEN), summary.predicateReasons.values)
    val serialized = rejectionEvidenceDocument(rejection).getValue("original_evidence_summary_state").jsonObject
    assertEquals("CAPTURED", serialized.getValue("type").jsonPrimitive.content)
    assertEquals("1100", serialized.getValue("summary").jsonObject.getValue("failure_count").jsonPrimitive.content)
}

internal fun assertUnavailableOriginalEvidence(rejection: QueryRunRejection.CompletionUnproven) {
    val unavailable = assertInstanceOf(QueryCompletionEvidenceDocument.Unavailable::class.java, rejection.evidence)
    assertEquals(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED, unavailable.cause)
    val summary =
        assertInstanceOf(QueryEvidenceSummaryState.Captured::class.java, unavailable.originalEvidenceSummary).summary
    assertEquals(100, summary.failureCount.value)
    assertEquals(100, summary.evidenceCount.value)
    assertEquals(0, summary.omissionCount.value)
    assertEquals(listOf(QueryPredicateFailureDocument.PREDICATE_UNPROVEN), summary.predicateReasons.values)
    val serialized = rejectionEvidenceDocument(rejection)
    assertEquals(
        "100",
        serialized
            .getValue("original_evidence_summary_state")
            .jsonObject
            .getValue("summary")
            .jsonObject
            .getValue("failure_count")
            .jsonPrimitive
            .content,
    )
    assertFalse(serialized.containsKey("result"))
    assertFalse(serialized.containsKey("nextQuery"))
}

internal fun assertObservationOriginalEvidence(state: QueryEvidenceSummaryState) {
    val summary = assertInstanceOf(QueryEvidenceSummaryState.Captured::class.java, state).summary
    assertEquals(1100, summary.evidenceCount.value)
    assertEquals(0, summary.failureCount.value)
    assertEquals(0, summary.omissionCount.value)
}

private fun rejectionEvidenceDocument(rejection: QueryRunRejection.CompletionUnproven): JsonObject {
    val encoded =
        CanonicalOperationWireBindings.queryRun.encodeOutcome(OperationOutcome.Rejected(rejection))
            as WireEncoding.Encoded
    val restored =
        (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document) as WireDecoding.Decoded).value
            as OperationOutcome.Rejected
    assertEquals(rejection.evidence, (restored.reason as QueryRunRejection.CompletionUnproven).evidence)
    val cli = CanonicalQueryCliDocuments.project(restored) as ProjectedOperationOutcome.Rejected
    return Json.parseToJsonElement(cli.document.value)
        .jsonObject
        .getValue("rejection")
        .jsonObject
        .getValue("detail")
        .jsonObject
        .getValue("evidence")
        .jsonObject
}
