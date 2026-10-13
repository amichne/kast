package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryBlockDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryNanosecondsDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryProgressDocument
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSamplesDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.AgentInterfaceDiscoveryFixture
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactDiscoveryPresentationTest {
    @Test
    fun `compact omits only the seven measurements from a nonzero discovery observation`() {
        val document = AgentInterfaceDiscoveryFixture.complete().document
        val full = json(document.present(ToolOutputDetail.VERBOSE))
        val compact = json(document.present(ToolOutputDetail.COMPACT))
        println("MVP_BASELINE_OR_CANDIDATE_COMPACT=" + document.present(ToolOutputDetail.COMPACT).value)
        println("MVP_VERBOSE=" + document.present(ToolOutputDetail.VERBOSE).value)
        assertMeasurementValues(observation(full), 100)
        assertSemanticObservation(observation(compact), "exhausted")
        assertEquals(semanticFields, observation(compact).keys, "Compact discovery leaked operational measurements")
        assertTrue(
            document.present(ToolOutputDetail.COMPACT).value.toByteArray().size < document.value.toByteArray().size
        )
    }

    @Test
    fun `measurement-only variants produce identical compact bytes and exact verbose values`() {
        for (project in
            listOf<(Long) -> CanonicalJsonDocument>(
                { AgentInterfaceDiscoveryFixture.complete(it).document },
                { AgentInterfaceDiscoveryFixture.qualified(it).document },
            )) {
            val documents =
                listOf(0L, 100L, 100000L).map { value ->
                    val document = project(value)
                    assertMeasurementValues(observation(json(document)), value)
                    document
                }
            val expected = documents.first().present(ToolOutputDetail.COMPACT).value
            for (document in documents) {
                assertEquals(expected, document.present(ToolOutputDetail.COMPACT).value)
                assertEquals(
                    expected.toByteArray(Charsets.UTF_8).toList(),
                    document.present(ToolOutputDetail.COMPACT).value.toByteArray(Charsets.UTF_8).toList(),
                )
                assertEquals(
                    document.value,
                    document.present(ToolOutputDetail.COMPACT).present(ToolOutputDetail.VERBOSE).value,
                )
            }
        }
    }

    @Test
    fun `declaration identities signatures and repeated occurrence descriptions keep all four rows`() {
        for (detail in ToolOutputDetail.entries) {
            val document = json(AgentInterfaceDiscoveryFixture.complete().document.present(detail))
            val rows = document.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(4, rows.size)
            assertEquals(
                listOf("exact-symbol", "exact-symbol", "occurrence", "occurrence"),
                rows.map { it.text("type") },
            )
            assertEquals(
                listOf(
                    "exact:v5:fixture-alpha",
                    "exact:v5:fixture-beta",
                    "exact:v5:fixture-alpha",
                    "exact:v5:fixture-alpha",
                ),
                rows.map { it.text("ref") },
            )
            assertEquals(
                (1..4).map { "result-row:v1:00000000-0000-0000-0000-00000000000$it" },
                rows.map { it.text("row_id") },
            )
            for ((index, name) in listOf("alpha", "beta").withIndex()) {
                val row = rows[index]
                assertEquals(name, row.text("name"))
                assertEquals("function", row.text("kind"))
                assertEquals("src/Fixture.kt", row.getValue("location").jsonObject.text("file"))
                assertRange(
                    row.getValue("location").jsonObject.getValue("range").jsonObject,
                    if (index == 0) 10 else 30,
                )
                val signature = row.getValue("signature").jsonObject
                assertEquals("function", signature.text("type"))
                assertEquals("fixture.$name", signature.text("qualifiedIdentity"))
                assertEquals(
                    listOf("kotlin.String"),
                    signature.getValue("valueParameters").jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals("0", signature.text("typeParameterCount"))
                assertEquals("absent", signature.getValue("receiver").jsonObject.text("type"))
                assertEquals(JsonArray(emptyList()), signature.getValue("contextReceivers"))
                assertEquals(JsonArray(emptyList()), row.getValue("connections"))
                assertFalse("matches" in row)
            }
            for ((index, start) in listOf(50, 70).withIndex()) {
                val relation = rows[index + 2].getValue("relation").jsonObject
                assertEquals("callers", relation.text("meaning"))
                assertEquals("beta", relation.getValue("source").jsonObject.text("name"))
                assertEquals("alpha", relation.getValue("target").jsonObject.text("name"))
                assertEquals("k2-authored-source", relation.text("provenance"))
                assertEquals("exact-compiler-confirmed", relation.text("coverage"))
                val occurrence = relation.getValue("occurrence").jsonObject
                assertEquals("candidate:fixture:beta", occurrence.text("candidateSelector"))
                assertEquals("src/Fixture.kt", occurrence.text("file"))
                assertEquals(start.toString(), occurrence.getValue("range").jsonObject.text("startInclusive"))
                assertEquals((start + 5).toString(), occurrence.getValue("range").jsonObject.text("endExclusive"))
            }
            assertAuthority(document)
            assertEquals(
                listOf("name", "location", "signature"),
                document
                    .getValue("question")
                    .jsonObject
                    .getValue("output")
                    .jsonObject
                    .getValue("fields")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `exhausted resumable and blocked evidence-only pages keep semantic progress and required arrays`() {
        val variants =
            listOf(
                QueryDiscoveryProgressDocument.Exhausted to "exhausted",
                AgentInterfaceDiscoveryFixture.resumableProgress() to "resumable",
                QueryDiscoveryProgressDocument.Blocked(QueryDiscoveryBlockDocument.PARTITION_CAPACITY) to "blocked",
            )
        for ((progress, kind) in variants) {
            val document =
                if (kind == "exhausted")
                    AgentInterfaceDiscoveryFixture.complete(progress = progress, evidenceOnly = true).document
                else AgentInterfaceDiscoveryFixture.qualified(progress = progress, evidenceOnly = true).document
            for (detail in ToolOutputDetail.entries) {
                val encoded = json(document.present(detail))
                for (field in listOf("items", "walk_observations", "reference_observations", "relation_observations")) {
                    assertEquals(JsonArray(emptyList()), encoded.getValue(field), field)
                }
                assertEquals(1, encoded.getValue("discovery_observations").jsonArray.size)
                assertSemanticObservation(observation(encoded), kind)
                if (kind == "resumable") {
                    val actual = observation(encoded).getValue("progress").jsonObject
                    assertEquals(
                        setOf(
                            "kind",
                            "completed_files",
                            "next_offset",
                            "discovered_files",
                            "pending_partitions",
                            "retained_bytes",
                        ),
                        actual.keys,
                    )
                    assertEquals(
                        listOf("2", "30", "5", "3", "512"),
                        listOf(
                                "completed_files",
                                "next_offset",
                                "discovered_files",
                                "pending_partitions",
                                "retained_bytes",
                            )
                            .map { actual.text(it) },
                    )
                }
                if (kind == "blocked")
                    assertEquals(
                        "partition-capacity",
                        observation(encoded).getValue("progress").jsonObject.text("cause"),
                    )
                if (kind != "exhausted") {
                    val qualification = encoded.getValue("qualification").jsonObject
                    assertEquals("4", qualification.text("knownMinimum"))
                    assertEquals(
                        listOf("work-limit-reached"),
                        qualification.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
                    )
                    val recovery = qualification.getValue("progress").jsonObject
                    assertEquals("resume", recovery.text("next_action"))
                    assertEquals(
                        AgentInterfaceDiscoveryFixture.CONTINUATION,
                        recovery.getValue("checkpoint").jsonObject.text("token"),
                    )
                }
                assertAuthority(encoded)
                assertEquals(
                    if (kind == "exhausted") "true" else "false",
                    encoded.getValue("coverage").jsonObject.text("exhaustive"),
                )
                assertEquals("0", encoded.getValue("page_progress").jsonObject.text("rows"))
                if (detail == ToolOutputDetail.COMPACT) {
                    assertEquals(semanticFields, observation(encoded).keys)
                    assertFalse("failures" in encoded)
                    assertFalse("omissions" in encoded)
                } else {
                    assertEquals(JsonArray(emptyList()), encoded.getValue("failures"))
                    assertEquals(JsonArray(emptyList()), encoded.getValue("omissions"))
                }
            }
        }
    }

    @Test
    fun `empty observation arrays remain present when the result has no discovery evidence`() {
        val document =
            project(
                AgentInterfaceDiscoveryFixture.result(evidenceOnly = true)
                    .copy(discoveryObservations = QueryDiscoveryObservationDocument.Empty)
            )
        for (detail in ToolOutputDetail.entries) {
            val encoded = json(document.present(detail))
            for (field in
                listOf(
                    "items",
                    "walk_observations",
                    "reference_observations",
                    "discovery_observations",
                    "relation_observations",
                )) {
                assertEquals(JsonArray(emptyList()), encoded.getValue(field), field)
            }
        }
    }

    @Test
    fun `verbose keeps each distinct admitted measurement in its original field`() {
        val original =
            AgentInterfaceDiscoveryFixture.observation()
                .copy(
                    examinedWorkUnits = QueryDiscoveryCountDocument.parse(11).value(),
                    inventoryFiles = QueryDiscoveryCountDocument.parse(13).value(),
                    reacquiredFiles = QueryDiscoveryCountDocument.parse(17).value(),
                    examinedLeaves = QueryDiscoveryCountDocument.parse(19).value(),
                    inventoryNanoseconds = QueryDiscoveryNanosecondsDocument.parse(23).value(),
                    declarationScanNanoseconds = QueryDiscoveryNanosecondsDocument.parse(29).value(),
                    projectionNanoseconds = QueryDiscoveryNanosecondsDocument.parse(31).value(),
                )
        val document =
            project(
                AgentInterfaceDiscoveryFixture.result()
                    .copy(discoveryObservations = BoundedProtocolList.create(listOf(original)).value())
            )
        assertEquals(
            listOf("11", "13", "17", "19", "23", "29", "31"),
            measurementFields.map { observation(json(document)).text(it) },
        )
        assertEquals(semanticFields, observation(json(document.present(ToolOutputDetail.COMPACT))).keys)
        assertEquals(document.value, document.present(ToolOutputDetail.COMPACT).present(ToolOutputDetail.VERBOSE).value)
    }

    @Test
    fun `nonempty failure and omission evidence survives compact projection without empty sample removal`() {
        val ref = QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v5:fixture-alpha").value())
        val result =
            AgentInterfaceDiscoveryFixture.result()
                .copy(
                    failures =
                        BoundedProtocolList.create(
                                listOf<QueryItemFailureDocument>(
                                    QueryItemFailureDocument.ExactReference(
                                        ref,
                                        QueryExactFailureDocument.DECLARATION_MOVED_OR_CHANGED,
                                    )
                                )
                            )
                            .value(),
                    omissions =
                        BoundedProtocolList.create(
                                listOf(
                                    QueryRelationOmissionDocument(
                                        ref,
                                        RelationKindDocument.CALLERS,
                                        RelationOmissionDocument.create(
                                                RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                                                RelationLimitationDocument.UNRESOLVED_TARGET,
                                                RelationOmissionMeasurementDocument.UnmeasuredOnPage,
                                                RelationOmissionSamplesDocument.complete(
                                                        BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument>()).value()
                                                    )
                                                    .value(),
                                            )
                                            .value(),
                                    )
                                )
                            )
                            .value(),
                )
        val document = project(result)
        for (detail in ToolOutputDetail.entries) {
            val encoded = json(document.present(detail))
            val failure = encoded.getValue("failures").jsonArray.single().jsonObject
            assertEquals(setOf("type", "ref", "reason"), failure.keys)
            assertEquals("exact-reference", failure.text("type"))
            assertEquals("exact:v5:fixture-alpha", failure.text("ref"))
            assertEquals("declaration-moved-or-changed", failure.text("reason"))
            val omission = encoded.getValue("omissions").jsonArray.single().jsonObject
            assertEquals("exact:v5:fixture-alpha", omission.text("subject"))
            assertEquals("callers", omission.text("relation"))
            val evidence = omission.getValue("evidence").jsonObject
            assertEquals("INTELLIJ_REFERENCES_V2", evidence.text("provider"))
            assertEquals("UNRESOLVED_TARGET", evidence.text("reason"))
            assertEquals("RESOLVE_SOURCE_ERRORS", evidence.text("remediation"))
            assertEquals("unmeasured_on_page", evidence.getValue("measurement").jsonObject.text("type"))
            assertEquals("complete", evidence.getValue("samples").jsonObject.text("type"))
            assertEquals(JsonArray(emptyList()), evidence.getValue("samples").jsonObject.getValue("locations"))
        }
    }

    @Test
    fun `rejected result preserves its finite recovery and has no fabricated observation`() {
        val document = AgentInterfaceDiscoveryFixture.rejected().document
        val compact = json(document.present(ToolOutputDetail.COMPACT))
        assertEquals(document.value, document.present(ToolOutputDetail.COMPACT).value)
        assertEquals("rejected", compact.text("status"))
        assertEquals("workspace-not-ready", compact.getValue("rejection").jsonObject.text("type"))
        assertEquals("wait_for_workspace", compact.text("next_action"))
        assertFalse("discovery_observations" in compact)
    }

    private fun project(result: io.github.amichne.kast.protocol.contract.QueryRunResult): CanonicalJsonDocument =
        (CanonicalQueryCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.QUERY_RUN.id,
                        AgentInterfaceDiscoveryFixture.basis(),
                        result,
                    )
                )
            ) as ProjectedOperationOutcome.Complete)
            .document

    private fun <T> Refinement<T, *>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid characterization fixture: $failure")
        }

    private fun assertSemanticObservation(actual: JsonObject, progress: String) {
        val universe = actual.getValue("universe").jsonObject
        assertEquals(
            setOf(
                "declaration_language",
                "match",
                "declaration_kinds",
                "source_policy",
                "generated_sources",
                "libraries",
                "source_sets",
                "directory",
                "package_name",
            ),
            universe.keys,
        )
        assertEquals("kotlin", universe.text("declaration_language"))
        assertEquals("all", universe.getValue("match").jsonObject.text("type"))
        assertEquals(
            listOf("function"),
            universe.getValue("declaration_kinds").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("production-only", universe.text("source_policy"))
        assertEquals("exclude", universe.text("generated_sources"))
        assertEquals("exclude", universe.text("libraries"))
        assertEquals("exact", universe.getValue("source_sets").jsonObject.text("kind"))
        assertEquals(
            listOf("main"),
            universe.getValue("source_sets").jsonObject.getValue("names").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("src", universe.getValue("directory").jsonObject.text("path"))
        assertEquals("descendants", universe.getValue("directory").jsonObject.text("containment"))
        assertEquals("fixture", universe.getValue("package_name").jsonObject.text("name"))
        assertEquals("direct", universe.getValue("package_name").jsonObject.text("containment"))
        assertEquals("kotlin-file-source-v2", actual.text("ordering"))
        assertEquals(progress, actual.getValue("progress").jsonObject.text("kind"))
        assertEquals(
            if (progress == "exhausted") emptyList<String>() else listOf("work-limit-reached"),
            actual.getValue("observed_stops").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun assertAuthority(document: JsonObject) {
        val live = document.getValue("live").jsonObject
        assertEquals(setOf("root", "host", "epoch", "contentView", "version"), live.keys)
        assertEquals("/workspace", live.text("root"))
        assertEquals(AgentInterfaceDiscoveryFixture.HOST, live.text("host"))
        assertEquals("7", live.text("epoch"))
        assertEquals("SAVED_PSI_COMMITTED", live.text("contentView"))
        assertEquals("1", live.text("version"))
        assertEquals("retained", document.getValue("retention").jsonObject.text("kind"))
        assertEquals(AgentInterfaceDiscoveryFixture.RESULT, document.getValue("retention").jsonObject.text("reference"))
        assertEquals("4", document.text("next_cursor"))
    }

    private fun assertRange(actual: JsonObject, start: Int) {
        assertEquals(start.toString(), actual.text("startInclusive"))
        assertEquals((start + 10).toString(), actual.text("endExclusive"))
    }

    private fun assertMeasurementValues(actual: JsonObject, value: Long) {
        assertEquals(semanticFields + measurementFields, actual.keys)
        for (field in measurementFields) assertEquals(value.toString(), actual.text(field), field)
    }

    private fun observation(document: JsonObject) =
        document.getValue("discovery_observations").jsonArray.single().jsonObject

    private fun json(document: CanonicalJsonDocument) = Json.parseToJsonElement(document.value).jsonObject

    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content

    private val semanticFields = setOf("universe", "ordering", "progress", "observed_stops")
    private val measurementFields =
        setOf(
            "examined_work_units",
            "inventory_files",
            "reacquired_files",
            "examined_leaves",
            "inventory_nanoseconds",
            "declaration_scan_nanoseconds",
            "projection_nanoseconds",
        )
}
