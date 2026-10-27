package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailKind
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSection
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentAuthorityLiveDigest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryWorkspaceDigest
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderStatus
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExistingIdeQueryPresentationHeaderTest {
    @Test
    fun `typed header binds a multibyte workspace and preserves the original complete outcome`() {
        val fixture = Fixture("/" + "λ".repeat(600))
        val bytes = fixture.encode(fixture.header())
        assertTrue(bytes.size <= 4096)
        val received = ExistingIdeDocuments.response(bytes, fixture.root, fixture.operation, fixture.descriptor)
        assertInstanceOf(ExistingIdeExchange.Semantic::class.java, received)
        assertInstanceOf(
            ProjectedOperationOutcome.Complete::class.java,
            (received as ExistingIdeExchange.Semantic).outcome,
        )
    }

    @Test
    fun `wrong workspace digest and foreign host cannot acquire native header authority`() {
        val fixture = Fixture("/workspace")
        val original = fixture.header()
        val binding = original.binding as QuerySemanticHeaderDocumentAuthorityLiveDigest
        val foreignWorkspace =
            original.copy(binding = binding.copy(workspaceDigest = QueryWorkspaceDigest.fromRoot("/other")))
        val foreignHost = original.copy(binding = binding.copy(host = text("00000000-0000-0000-0000-000000000099")))
        for (header in listOf(foreignWorkspace, foreignHost)) {
            val received =
                ExistingIdeDocuments.response(
                    fixture.encode(header),
                    fixture.root,
                    fixture.operation,
                    fixture.descriptor,
                )
            assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), received)
        }
    }

    @Test
    fun `a header cannot strengthen the original outcome through an inconsistent public status`() {
        val fixture = Fixture("/workspace")
        val header = fixture.header().copy(status = QueryPresentationHeaderStatus.QUALIFIED)
        assertEquals(
            ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
            ExistingIdeDocuments.response(fixture.encode(header), fixture.root, fixture.operation, fixture.descriptor),
        )
    }

    @Test
    fun `produced detail continuation admits both catalogs without conflicting presentation cursors`() {
        val fixture = Fixture("/workspace")
        val retained = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").proven()
        val selector =
            QueryEntryDetailSelectorDocument(
                retained,
                QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
                QueryEntryDetailTarget.ROW,
                QueryResultCursor.parse(2).proven(),
                QueryEntryDetailKind.EXACT_SYMBOL,
                text("a".repeat(64)),
                ProtocolOffset.parse(0).proven(),
            )
        val fragment =
            QueryEntryDetailDocument(
                selector,
                ProtocolOffset.parse(3).proven(),
                ProtocolOffset.parse(9).proven(),
                text("abc"),
            )
        val header = fixture.header(fragment)
        val next = requireNotNull(header.nextQuery)
        val raw = Json {
            encodeDefaults = true
            explicitNulls = true
        }
            .encodeToJsonElement(QueryCompletionPublicReadQuery.serializer(), next)
        val request = raw.jsonObject.getValue("request").jsonObject
        assertFalse(request.containsKey("cursor"))
        assertFalse(request.containsKey("evidence_cursor"))
        for (catalog in PublicToolCatalog.entries) {
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw, catalog)
            assertInstanceOf(Refinement.Refined::class.java, admitted)
            val canonical =
                ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
                    as QueryRunRequest.ReadResult
            assertEquals(retained, canonical.result)
            assertEquals(selector.copy(offset = ProtocolOffset.parse(3).proven()), canonical.detail)
            assertEquals(QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME))), canonical.output)
        }
        val finalFragment = fragment.copy(end = ProtocolOffset.parse(9).proven(), text = text("abcdefghi"))
        assertNull(fixture.header(finalFragment).nextQuery)
    }

    private class Fixture(path: String) {
        val root = CanonicalRoot(Path.of(path))
        val descriptor =
            ExistingIdeDescriptor(
                HostedEndpointOwnerPid.parse("123").proven(),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
            )
        private val question =
            QueryQuestionDocument(
                QueryFromDocument.Location(text("Fixture.kt"), ProtocolOffset.parse(0).proven()),
                bounded(emptyList()),
                QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME))),
            )
        val operation =
            ExistingIdeOperation.Read.admit(
                    (canonicalCliRequestPreparers()
                            .queryRun
                            .prepare(
                                QueryRunRequest.Run(
                                    question.from,
                                    question.steps,
                                    question.output,
                                    QueryExecutionDocument(
                                        QueryExecutionKindDocument.EXHAUSTIVE,
                                        QueryExecutionBudgetDocument.INTERACTIVE,
                                    ),
                                )
                            ) as OperationPreparation.Prepared)
                        .request
                )
                .proven()

        fun header(detail: QueryEntryDetailDocument? = null): QueryPresentationHeaderDocument {
            val basis =
                EvidenceBasis.Live(
                    LiveReadEvidence.create(
                            root.path.toString(),
                            descriptor.host,
                            7,
                            LiveReadContentView.SAVED_PSI_COMMITTED,
                            1,
                        )
                        .proven()
                )
            val semantic =
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperationWireBindings.queryRun.operation.id,
                        basis,
                        QueryRunResult(
                            question,
                            bounded(emptyList()),
                            bounded(emptyList()),
                            retention =
                                QueryResultRetention.Retained(
                                    QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001")
                                        .proven()
                                ),
                        ),
                    )
                )
            return QueryPresentationHeaderDocument.from(semantic, detail)
        }

        fun encode(header: QueryPresentationHeaderDocument): ByteArray =
            CanonicalJsonDocument.generated(QueryPresentationHeaderDocument.serializer())
                .create(header)
                .value
                .toByteArray(Charsets.UTF_8)
    }
}

private fun text(value: String): ProtocolText = ProtocolText.parse(value).proven()

private fun <T> bounded(values: List<T>): BoundedProtocolList<T> = BoundedProtocolList.create(values).proven()

private fun <T, E> Refinement<T, E>.proven(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid header fixture: $failure")
    }
