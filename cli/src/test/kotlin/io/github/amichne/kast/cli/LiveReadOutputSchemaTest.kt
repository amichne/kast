package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.projection.CanonicalQueryCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSymbolCliDocuments
import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveReadOutputSchemaTest {
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
    fun `all seven actual complete projections satisfy their advertised published and live schemas`() {
        for (basis in listOf(published, live)) for ((operation, document) in completeDocuments(basis)) {
            assertAdmits(operation, document)
            assertEquals(basis is EvidenceBasis.Live, document.containsKey("live"), operation.name)
        }
    }

    @Test
    fun `all seven actual qualified projections retain compatible evidence`() {
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
    fun `source and traversal schemas reject mixed or missing snapshot bases`() {
        val publishedDocuments = completeDocuments(published).toMap()
        for ((operation, document) in
            completeDocuments(live).filter { (operation) ->
                operation == CanonicalOperation.SOURCE_READ || operation == CanonicalOperation.TRAVERSAL_RUN
            }) {
            val snapshot = snapshot(operation, document)
            assertEquals(document["live"], snapshot["live"])
            assertRejects(operation, document.withSnapshot(operation, snapshot.with("generation", JsonPrimitive(7))))
            assertRejects(
                operation,
                document.withSnapshot(operation, snapshot.with("sourceState", JsonPrimitive("fake"))),
            )
            assertRejects(operation, document.withSnapshot(operation, JsonObject(snapshot - "live")))
            assertRejects(operation, JsonObject(document - "live"))
            assertRejects(operation, publishedDocuments.getValue(operation).with("live", document.getValue("live")))
            assertRejects(
                operation,
                document.withSnapshot(operation, snapshot(operation, publishedDocuments.getValue(operation))),
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
            CanonicalOperation.RELATION_READ to
                CanonicalReadCliDocuments.projectRelation(
                        complete(CanonicalOperation.RELATION_READ, basis, RelationReadResult(empty()))
                    )
                    .document(),
            CanonicalOperation.TRAVERSAL_RUN to
                CanonicalReadCliDocuments.projectTraversal(
                        complete(
                            CanonicalOperation.TRAVERSAL_RUN,
                            basis,
                            traversalResult(),
                        )
                    )
                    .document(),
            CanonicalOperation.DIAGNOSTIC_CHECK to
                CanonicalReadCliDocuments.projectDiagnostics(
                        complete(CanonicalOperation.DIAGNOSTIC_CHECK, basis, DiagnosticCheckResult(empty()))
                    )
                    .document(),
        )

    @Test
    fun `resumable traversal output admits checkpoints for its actual evidence basis`() {
        for ((basis, version) in listOf(published to "v1", live to "v2")) {
            val payload = "{}".toByteArray()
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
            val digest =
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload))
            val continuation =
                TraversalContinuationDocument.parse("traversal-continuation:$version:$encoded:$digest").refined()
            val outcome =
                OperationOutcome.Qualified(
                    EvidenceEnvelope(CanonicalOperation.TRAVERSAL_RUN.id, basis, traversalResult()),
                    TraversalRunQualification.resumable(
                            listOf(TraversalLimitationDocument.RECORD_LIMIT_REACHED),
                            emptyList(),
                            continuation,
                        )
                        .refined(),
                )
            assertAdmits(
                CanonicalOperation.TRAVERSAL_RUN,
                CanonicalReadCliDocuments.projectTraversal(outcome).document(),
            )
        }
    }

    private fun qualifiedDocuments(basis: EvidenceBasis): List<Pair<CanonicalOperation, JsonObject>> =
        listOf(
            CanonicalOperation.QUERY_RUN to
                CanonicalQueryCliDocuments.project(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis, QueryRunResult(empty(), empty())),
                            QueryRunQualification.create(
                                    QueryKnownMinimum.parse(0).refined(),
                                    listOf(QueryLimitationDocument.DISCOVERY_INCOMPLETE),
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
                                    SourceReadContinuationStateDocument.Unavailable,
                                )
                                .refined(),
                        )
                    )
                    .document(),
            CanonicalOperation.RELATION_READ to
                CanonicalReadCliDocuments.projectRelation(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(CanonicalOperation.RELATION_READ.id, basis, RelationReadResult(empty())),
                            RelationReadQualification.terminalIncomplete(
                                    RelationKnownMinimumDocument.parse(0).refined(),
                                    listOf(RelationLimitationDocument.WORK_LIMIT_REACHED),
                                )
                                .refined(),
                        )
                    )
                    .document(),
            CanonicalOperation.TRAVERSAL_RUN to
                CanonicalReadCliDocuments.projectTraversal(
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(
                                CanonicalOperation.TRAVERSAL_RUN.id,
                                basis,
                                traversalResult(),
                            ),
                            TraversalRunQualification.terminalIncomplete(
                                    listOf(TraversalLimitationDocument.DEPTH_LIMIT_REACHED),
                                    emptyList(),
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

    private fun sourceResult(basis: EvidenceBasis): SourceReadResult {
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

    private fun traversalResult(): TraversalRunResult =
        TraversalRunResult(
            text("/workspace"),
            BoundedProtocolList.create(
                    listOf(
                        TraversalRecordDocument(
                            TraversalDepthDocument.parse(1).refined(),
                            RelationFactDocument(
                                meaning = RelationKindDocument.CALLERS,
                                source = symbol(),
                                target = symbol(),
                                occurrence =
                                    RelationOccurrenceDocument(
                                        text("candidate:occurrence"),
                                        text("src/Example.kt"),
                                        SourceRangeDocument.create(offset(0), offset(1)).refined(),
                                    ),
                                provenance = RelationProvenanceDocument.K2_AUTHORED_SOURCE,
                                coverage = RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
                            ),
                        )
                    )
                )
                .refined(),
        )

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

    private fun snapshot(operation: CanonicalOperation, document: JsonObject): JsonObject =
        if (operation == CanonicalOperation.SOURCE_READ) document.getValue("snapshot").jsonObject
        else document.getValue("graph").jsonObject.getValue("snapshot").jsonObject

    private fun JsonObject.withSnapshot(operation: CanonicalOperation, snapshot: JsonObject): JsonObject =
        if (operation == CanonicalOperation.SOURCE_READ) with("snapshot", snapshot)
        else with("graph", getValue("graph").jsonObject.with("snapshot", snapshot))

    private fun assertAdmits(operation: CanonicalOperation, document: JsonObject) {
        val errors = validate(operation, document)
        assertTrue(errors.isEmpty(), "$operation rejected its emitted document: $errors")
    }

    private fun assertRejects(operation: CanonicalOperation, document: JsonObject) =
        assertTrue(validate(operation, document).isNotEmpty(), "$operation admitted contradictory evidence: $document")

    private fun validate(operation: CanonicalOperation, document: JsonObject) =
        schemas
            .getSchema(installedServerOutputSchema(operation).toString())
            .validate(
                buildJsonObject {
                    put("status", "completed")
                    put("document", document)
                }
                    .toString(),
                InputFormat.JSON,
            )

    private fun ProjectedCliOutcome.document(): JsonObject =
        when (this) {
                is ProjectedCliOutcome.Complete -> document
                is ProjectedCliOutcome.Qualified -> document
                is ProjectedCliOutcome.Rejected -> document
            }
            .value
            .let(Json::parseToJsonElement)
            .jsonObject

    private fun <R> complete(operation: CanonicalOperation, basis: EvidenceBasis, result: R) =
        OperationOutcome.Complete(EvidenceEnvelope(operation.id, basis, result))

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun offset(value: Int) = ProtocolOffset.parse(value).refined()

    private fun <T> empty(): BoundedProtocolList<T> = BoundedProtocolList.create(emptyList<T>()).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
