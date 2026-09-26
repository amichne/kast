package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSymbolCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveReadOutputSchemaTest {
    @Test
    fun `every finite query reference rejection satisfies its installed schema`() {
        for (reason in QueryReferenceRejectionReason.entries) {
            val document =
                CanonicalQueryCliDocuments.project(
                        OperationOutcome.Rejected(QueryRunRejection.ReferenceRejected(offset(0), reason))
                    )
                    .document()
            assertAdmits(CanonicalOperation.QUERY_RUN, document)
            assertEquals(JsonPrimitive("rejected"), document["status"])
            val rejection = document.getValue("rejection").jsonObject
            assertEquals(JsonPrimitive("reference-rejected"), rejection["type"])
            assertEquals(JsonPrimitive("from.values[0]"), rejection["path"])
            assertRejects(
                CanonicalOperation.QUERY_RUN,
                document.with("rejection", rejection.with("reason", JsonPrimitive("unknown-authority"))),
            )
        }
    }

    @Test
    fun `every finite query execution rejection satisfies its installed schema`() {
        for (reason in QueryExecutionRejectionDocument.entries) {
            assertAdmits(
                CanonicalOperation.QUERY_RUN,
                CanonicalQueryCliDocuments.project(
                        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
                    )
                    .document(),
            )
        }
    }

    private val live =
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
    private val published = EvidenceBasis.Published(EvidenceGeneration.parse(7).refined())
    private val schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    @Test
    fun `all five actual complete projections satisfy their advertised published and live schemas`() {
        for (basis in listOf(published, live)) for ((operation, document) in completeDocuments(basis)) {
            assertAdmits(operation, document)
            assertEquals(basis is EvidenceBasis.Live, document.containsKey("live"), operation.name)
        }
    }

    @Test
    fun `all five actual qualified projections retain compatible evidence`() {
        for (basis in listOf(published, live)) for ((operation, document) in qualifiedDocuments(basis)) {
            assertAdmits(operation, document)
            assertEquals(JsonPrimitive("qualified"), document["status"])
        }
    }

    @Test
    fun `live metadata is closed versioned and never a synthetic generation`() {
        for ((operation, document) in completeDocuments(live)) {
            assertRejects(operation, document.with("generation", JsonPrimitive(7)))
            assertRejects(operation, document.with("sourceState", JsonPrimitive("fake")))
            val evidence = document.getValue("live").jsonObject
            for ((field, value) in
                listOf(
                    "root" to JsonPrimitive("relative"),
                    "host" to JsonPrimitive("unknown"),
                    "epoch" to JsonPrimitive(0),
                    "contentView" to JsonPrimitive("PUBLISHED"),
                    "version" to JsonPrimitive(2),
                    "generation" to JsonPrimitive(7),
                )) assertRejects(operation, document.with("live", evidence.with(field, value)))
        }
    }

    @Test
    fun `source schema rejects mixed or missing snapshot bases`() {
        val publishedDocuments = completeDocuments(published).toMap()
        for ((operation, document) in
            completeDocuments(live).filter { (operation) ->
                operation == CanonicalOperation.SOURCE_READ
            }) {
            val snapshot = snapshot(document)
            assertEquals(document["live"], snapshot["live"])
            assertRejects(operation, document.withSnapshot(snapshot.with("generation", JsonPrimitive(7))))
            assertRejects(
                operation,
                document.withSnapshot(snapshot.with("sourceState", JsonPrimitive("fake"))),
            )
            val malformedJson = Json { ignoreUnknownKeys = true }
            val snapshotWithoutLive = malformedJson.decodeFromJsonElement<SourceSnapshotWithoutLive>(snapshot)
            assertRejects(operation, document.withSnapshot(Json.encodeToJsonElement(snapshotWithoutLive).jsonObject))
            val documentWithoutLive = malformedJson.decodeFromJsonElement<SourceDocumentWithoutLive>(document)
            assertRejects(operation, Json.encodeToJsonElement(documentWithoutLive).jsonObject)
            assertRejects(operation, publishedDocuments.getValue(operation).with("live", document.getValue("live")))
            assertRejects(
                operation,
                document.withSnapshot(snapshot(publishedDocuments.getValue(operation))),
            )
        }
    }

    @Test
    fun `every hosted operation admits only packaged pre-authority rejection shapes`() {
        val endpoint = Json.parseToJsonElement("""{"type":"HOST_REJECTED","failure":"DEADLINE_EXCEEDED"}""").jsonObject
        val hosted =
            Json.parseToJsonElement(
                    """{"schemaVersion":1,"outcome":"rejected","failure":"DIRTY_DOCUMENTS","detail":"saved content required","stage":"EPOCH_OBSERVATION"}"""
                )
                .jsonObject
        val operations =
            completeDocuments(live).map { it.first } +
                listOf(
                    CanonicalOperation.CHANGE_PLAN,
                    CanonicalOperation.CHANGE_APPLY,
                    CanonicalOperation.CHANGE_RECOVER,
                )
        for (operation in operations) {
            assertAdmits(operation, endpoint)
            assertAdmits(operation, hosted)
            assertRejects(operation, endpoint.with("failure", JsonPrimitive("UNKNOWN")))
            assertRejects(operation, hosted.with("failure", JsonPrimitive("UNKNOWN")))
            assertRejects(operation, hosted.with("outcome", JsonPrimitive("published")))
            assertRejects(operation, hosted.with("live", completeDocuments(live).first().second.getValue("live")))
        }
        assertRejects(CanonicalOperation.TOPOLOGY_BUILD, endpoint)
        assertRejects(CanonicalOperation.TOPOLOGY_BUILD, hosted)
    }

    @Test
    fun `named source root rejection retains standalone referenced definitions`() {
        val hosted =
            Json.parseToJsonElement(
                    requireNotNull(javaClass.getResource("/live-read-schema/named-source-rejection.json")).readText()
                )
                .jsonObject
        val detail = hosted.getValue("detail").jsonObject
        val operations =
            completeDocuments(live).map { it.first } +
                listOf(
                    CanonicalOperation.CHANGE_PLAN,
                    CanonicalOperation.CHANGE_APPLY,
                    CanonicalOperation.CHANGE_RECOVER,
                )
        for (operation in operations) {
            assertAdmits(operation, hosted)
            assertRejects(operation, hosted.with("detail", detail.with("module", JsonNull)))
            assertRejects(operation, hosted.with("detail", detail.with("reason", JsonPrimitive("UNKNOWN_REASON"))))
        }
    }

    private fun completeDocuments(basis: EvidenceBasis): List<Pair<CanonicalOperation, JsonObject>> =
        listOf(
            CanonicalOperation.QUERY_RUN to
                CanonicalQueryCliDocuments.project(
                        complete(CanonicalOperation.QUERY_RUN, basis, QueryRunResult(empty(), empty()))
                    )
                    .document(),
            CanonicalOperation.SYMBOL_DISCOVER to
                CanonicalSymbolCliDocuments.projectDiscovery(
                        complete(CanonicalOperation.SYMBOL_DISCOVER, basis, SymbolDiscoverResult(empty()))
                    )
                    .document(),
            CanonicalOperation.SYMBOL_INSPECT to
                CanonicalSymbolCliDocuments.projectInspection(
                        complete(CanonicalOperation.SYMBOL_INSPECT, basis, SymbolInspectResult(symbol()))
                    )
                    .document(),
            CanonicalOperation.SOURCE_READ to
                CanonicalSourceReadCliDocuments.project(
                        complete(CanonicalOperation.SOURCE_READ, basis, sourceResult(basis))
                    )
                    .document(),
            CanonicalOperation.DIAGNOSTIC_CHECK to
                CanonicalReadCliDocuments.projectDiagnostics(
                        complete(CanonicalOperation.DIAGNOSTIC_CHECK, basis, DiagnosticCheckResult(empty()))
                    )
                    .document(),
        )

    private fun qualifiedDocuments(basis: EvidenceBasis): List<Pair<CanonicalOperation, JsonObject>> =
        listOf(
            CanonicalOperation.QUERY_RUN to
                CanonicalQueryCliDocuments.project(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis, QueryRunResult(empty(), empty())),
                            QueryRunQualification.create(
                                    QueryKnownMinimum.parse(0).refined(),
                                    listOf(QueryLimitationDocument.DISCOVERY_INCOMPLETE),
                                    io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
                                        .TerminalIncomplete(
                                            io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
                                                .UPSTREAM_INCOMPLETE
                                        ),
                                )
                                .refined(),
                        )
                    )
                    .document(),
            CanonicalOperation.SYMBOL_DISCOVER to
                CanonicalSymbolCliDocuments.projectDiscovery(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(
                                CanonicalOperation.SYMBOL_DISCOVER.id,
                                basis,
                                SymbolDiscoverResult(empty()),
                            ),
                            SymbolDiscoverQualification.from(setOf(SymbolDiscoverLimitation.WORK_LIMIT)).refined(),
                        )
                    )
                    .document(),
            CanonicalOperation.SYMBOL_INSPECT to
                CanonicalSymbolCliDocuments.projectInspection(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(
                                CanonicalOperation.SYMBOL_INSPECT.id,
                                basis,
                                SymbolInspectResult(symbol()),
                            ),
                            SymbolInspectQualification.EVIDENCE_INCOMPLETE,
                        )
                    )
                    .document(),
            CanonicalOperation.SOURCE_READ to
                CanonicalSourceReadCliDocuments.project(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, sourceResult(basis)),
                            SourceReadQualification.create(
                                    SourceEntityCountDocument.parse(0).refined(),
                                    listOf(SourceReadLimitationDocument.WORK_LIMIT_REACHED),
                                    SourceQualifiedProgressDocument.TerminalIncomplete(
                                        SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE
                                    ),
                                )
                                .refined(),
                        )
                    )
                    .document(),
            CanonicalOperation.DIAGNOSTIC_CHECK to
                CanonicalReadCliDocuments.projectDiagnostics(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(
                                CanonicalOperation.DIAGNOSTIC_CHECK.id,
                                basis,
                                DiagnosticCheckResult(empty()),
                            ),
                            DiagnosticCheckQualification.create(
                                    DiagnosticKnownCountDocument.parse(0).refined(),
                                    false,
                                    emptyList(),
                                    listOf(
                                        DiagnosticLimitationDocument(
                                            text("src/Example.kt"),
                                            DiagnosticLimitationReasonDocument.INDEXING,
                                        )
                                    ),
                                )
                                .refined(),
                        )
                    )
                    .document(),
        )

    fun sourceResult(basis: EvidenceBasis): SourceReadResult {
        val snapshot =
            SourceSnapshotDocument(
                text("/workspace"),
                when (basis) {
                    is EvidenceBasis.Published ->
                        SourceSnapshotContextDocument.Published(basis.generation, text("source-state"))
                    is EvidenceBasis.Live -> SourceSnapshotContextDocument.Live(basis.evidence)
                },
                text("src/Empty.kt"),
                text("text-digest"),
                SourceCoordinateUnitDocument.UTF16_CODE_UNIT,
                SourceLengthDocument.parse(0).refined(),
            )
        val selection =
            SourceSelectionDocument(
                text("source-selector-v1:payload:digest"),
                SourceSelectionRangeDocument.create(offset(0), offset(0)).refined(),
            )
        return SourceReadResult(
            snapshot,
            SourceRegionDocument(SourceRegionKindDocument.FILE, selection),
            empty(),
            SourceTextProjectionDocument.NotRequested,
        )
    }

    private fun symbol(): SymbolDocument =
        SymbolDocument.create(
                text("exact:v2:payload:digest"),
                SymbolKindDocument.CLASSLIKE,
                text("Example"),
                SymbolQualifiedIdentityDocument.Available(text("sample.Example")),
                text("src/Example.kt"),
                SourceRangeDocument.create(offset(0), offset(1)).refined(),
                CompilerSymbolEvidenceDocument.fromSignature(
                        CompilerSignatureDocument.ClassLike(text("sample.Example"))
                    )
                    .refined(),
            )
            .refined()

    /** Negative fixtures deliberately omit one required live evidence field. */
    @Serializable
    private data class SourceSnapshotWithoutLive(
        val canonicalRoot: String,
        val file: String,
        val textIdentity: String,
        val coordinateUnit: String,
        val length: Int,
    )

    @Serializable
    private data class SourceDocumentWithoutLive(
        val operation: String,
        val status: String,
        val snapshot: JsonElement,
        val region: JsonElement,
        val entities: JsonElement,
        val text: JsonElement,
    )

    private fun snapshot(document: JsonObject): JsonObject = document.getValue("snapshot").jsonObject

    internal fun JsonObject.withSnapshot(snapshot: JsonObject): JsonObject = with("snapshot", snapshot)

    internal fun qualifiedEnvelope(operation: CanonicalOperation): String =
        completedSchemaEnvelope(qualifiedDocuments(published).first { it.first == operation }.second)

    internal fun completeEnvelope(operation: CanonicalOperation): String =
        completedSchemaEnvelope(completeDocuments(published).first { it.first == operation }.second)

    internal fun diagnosticEnvelopeWithMessage(message: String): String {
        val finding =
            DiagnosticDocument(
                severity = DiagnosticSeverityDocument.WARNING,
                code = text("LONG_MESSAGE"),
                message = text(message),
                location =
                    DiagnosticLocationDocument(
                        candidateSelector = text("candidate:diagnostic"),
                        file = text("src/A.kt"),
                        range = DiagnosticRangeDocument.create(offset(0), offset(0)).refined(),
                    ),
            )
        val result = DiagnosticCheckResult(BoundedProtocolList.create(listOf(finding)).refined())
        return completedSchemaEnvelope(
            CanonicalReadCliDocuments.projectDiagnostics(
                    complete(CanonicalOperation.DIAGNOSTIC_CHECK, published, result)
                )
                .document()
        )
    }

    internal fun assertAdmits(operation: CanonicalOperation, document: JsonObject) {
        val errors = validate(operation, document)
        assertTrue(errors.isEmpty(), "$operation rejected its emitted document: $errors")
    }

    internal fun assertRejects(operation: CanonicalOperation, document: JsonObject) =
        assertTrue(validate(operation, document).isNotEmpty(), "$operation admitted contradictory evidence: $document")

    private fun validate(operation: CanonicalOperation, document: JsonObject) =
        schemas
            .getSchema(installedServerOutputSchema(operation).toString())
            .validate(
                completedSchemaEnvelope(document),
                InputFormat.JSON,
            )

    internal fun ProjectedOperationOutcome.document(): JsonObject =
        when (this) {
                is ProjectedOperationOutcome.Complete -> document
                is ProjectedOperationOutcome.Qualified -> document
                is ProjectedOperationOutcome.Rejected -> document
            }
            .value
            .let(Json::parseToJsonElement)
            .jsonObject

    private fun <R> complete(operation: CanonicalOperation, basis: EvidenceBasis, result: R) =
        OperationOutcome.Complete(EvidenceEnvelope(operation.id, basis, result))

    internal fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun offset(value: Int) = ProtocolOffset.parse(value).refined()

    private fun <T> empty(): BoundedProtocolList<T> = BoundedProtocolList.create(emptyList<T>()).refined()

    internal fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
