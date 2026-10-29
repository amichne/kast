package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.rpc.QueryDeliveryFixtures
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicShellExitTest {
    @Test
    fun `empty ordinary result succeeds only when original proof is complete`() {
        val rows = (BoundedProtocolList.create<QueryResultItemDocument>(emptyList()) as Refinement.Refined).value
        val failures = (BoundedProtocolList.create<QueryItemFailureDocument>(emptyList()) as Refinement.Refined).value
        val result =
            sourceRelationshipsResult(rows, failures)
                .copy(interpretation = QueryResultInterpretationDocument.QueryResult)
        val basis = EvidenceBasis.Published((EvidenceGeneration.parse(1) as Refinement.Refined).value)
        val evidence = EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis, result)
        val complete =
            CanonicalQueryCliDocuments.project(OperationOutcome.Complete(evidence))
                as ProjectedOperationOutcome.Complete
        val qualification =
            (QueryRunQualification.create(
                    (QueryKnownMinimum.parse(0) as Refinement.Refined).value,
                    listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                ) as Refinement.Refined)
                .value
        val qualified =
            CanonicalQueryCliDocuments.project(OperationOutcome.Qualified(evidence, qualification))
                as ProjectedOperationOutcome.Qualified
        assertEquals(
            0,
            classifyPublicReply(presentToolRpcExit("query_symbols", CliExit.Complete(complete.document))).code,
        )
        assertEquals(
            10,
            classifyPublicReply(presentToolRpcExit("query_symbols", CliExit.Qualified(qualified.document))).code,
        )
    }

    @Test
    fun `retained evidence never becomes successful on its final page`() {
        val cases = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
        for (name in listOf("proof", "proofTail", "rejected")) {
            assertEquals(10, classifyPublicReply(cases.getValue(name)).code, name)
        }
    }

    @Test
    fun `successor cursor is ordinary pagination`() {
        val cases = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
        for (name in listOf("inline", "prefix", "rows", "tail")) {
            assertEquals(0, classifyPublicReply(cases.getValue(name)).code, name)
        }
    }

    @Test
    fun `headers preserve original proof before delivery status`() {
        assertEquals(
            listOf(11, 10, 10),
            QueryDeliveryFixtures.presentationHeaders().map { classifyPublicReply(it).code },
        )
    }

    @Test
    fun `explicit elapsed grant alone permits typed expiry code`() {
        val cases = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
        assertEquals(124, classifyPublicReply(cases.getValue("rejected"), PublicElapsedGrant.EXPLICIT).code)
        assertEquals(10, classifyPublicReply(cases.getValue("rejected"), PublicElapsedGrant.ABSENT).code)
    }

    @Test
    fun `source relationships evidence-only effects preserve complete proof`() {
        val empty = (BoundedProtocolList.create<QueryResultItemDocument>(emptyList()) as Refinement.Refined).value
        val failures = (BoundedProtocolList.create<QueryItemFailureDocument>(emptyList()) as Refinement.Refined).value
        val result = sourceRelationshipsResult(empty, failures)
        val generation = (EvidenceGeneration.parse(1) as Refinement.Refined).value
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    EvidenceBasis.Published(generation),
                    result,
                )
            )
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        val reply = presentToolRpcExit("query_symbols", CliExit.Complete(projected.document))
        assertEquals(0, classifyPublicReply(reply).code)
    }

    private fun sourceRelationshipsResult(
        empty: BoundedProtocolList<QueryResultItemDocument>,
        failures: BoundedProtocolList<QueryItemFailureDocument>,
    ): QueryRunResult =
        QueryRunResult(
            QueryQuestionDocument(
                QueryFromDocument.Location(
                    (ProtocolText.parse("Fixture.kt") as Refinement.Refined).value,
                    (ProtocolOffset.parse(0) as Refinement.Refined).value,
                ),
                (BoundedProtocolList.create(listOf<QueryStepDocument>(QueryStepDocument.Trace())) as Refinement.Refined)
                    .value,
                QueryOutputDocument.Symbols(
                    (BoundedProtocolList.create<QuerySymbolFieldDocument>(emptyList()) as Refinement.Refined).value
                ),
            ),
            empty,
            failures,
            interpretation = QueryResultInterpretationDocument.SourceRelationships(),
        )

    @Test
    fun `private semantic exits remain zero and public defects are nonzero`() {
        assertEquals(
            1,
            classifyPublicReply(ToolRpcReply.Rejected(ToolRpcFailure.INVOCATION_FAILED)).code,
        )
        assertEquals(
            6,
            classifyPublicReply(ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)).code,
        )
        val document =
            (CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady))
                    as ProjectedOperationOutcome.Rejected)
                .document
        assertEquals(0, CliExit.OperationRejected(document).code)
        assertEquals(0, CliExit.Qualified(document).code)
    }
}
