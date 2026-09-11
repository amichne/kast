package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnapshotEvidenceWireBindingTest {
    private val live = live("/workspace", "00000000-0000-0000-0000-000000000001", 7)
    private val published = EvidenceBasis.Published(EvidenceGeneration.parse(17).refined())
    private val source = CanonicalOperationWireBindings.sourceRead
    private val traversal = CanonicalOperationWireBindings.traversalRun
    private val invalidPayload = WireFailure.InvalidPayload(WireValueRole.RESULT)

    @Test
    fun `source complete and qualified decoding requires the exact repeated live evidence`() {
        val otherBases =
            listOf(
                published,
                live("/workspace", "00000000-0000-0000-0000-000000000002", 7),
                live("/workspace", "00000000-0000-0000-0000-000000000001", 8),
                live("/foreign", "00000000-0000-0000-0000-000000000001", 7),
            )
        for (outcome in sourceOutcomes(live, sourceResult(live))) {
            val document = source.encodeOutcome(outcome).encoded()
            assertEquals(WireDecoding.Decoded(outcome), source.decodeOutcome(document.toString()))
            for (basis in otherBases) {
                val snapshot =
                    source
                        .encodeOutcome(sourceOutcomes(basis, sourceResult(basis)).first())
                        .encoded()
                        .getValue("body")
                        .jsonObject
                        .getValue("result")
                        .jsonObject
                        .getValue("snapshot")
                assertEquals(
                    WireDecoding.Rejected(invalidPayload),
                    source.decodeOutcome(document.replaceSnapshot(snapshot).toString()),
                    basis.toString(),
                )
            }
        }
    }

    @Test
    fun `published source decoding requires matching generations and rejects live snapshots`() {
        val otherGeneration = EvidenceBasis.Published(EvidenceGeneration.parse(18).refined())
        for (outcome in sourceOutcomes(published, sourceResult(published))) {
            val document = source.encodeOutcome(outcome).encoded()
            assertEquals(WireDecoding.Decoded(outcome), source.decodeOutcome(document.toString()))
            for (basis in listOf(otherGeneration, live)) {
                val snapshot =
                    source
                        .encodeOutcome(sourceOutcomes(basis, sourceResult(basis)).first())
                        .encoded()
                        .getValue("body")
                        .jsonObject
                        .getValue("result")
                        .jsonObject
                        .getValue("snapshot")
                assertEquals(
                    WireDecoding.Rejected(invalidPayload),
                    source.decodeOutcome(document.replaceSnapshot(snapshot).toString()),
                )
            }
        }
    }

    @Test
    fun `source encoder refuses contradictory bases generations and live roots`() {
        val otherGeneration = EvidenceBasis.Published(EvidenceGeneration.parse(18).refined())
        val moved = live("/workspace", "00000000-0000-0000-0000-000000000001", 8)
        val foreignRoot =
            sourceResult(live).let { it.copy(snapshot = it.snapshot.copy(canonicalRoot = text("/foreign"))) }
        for ((basis, result) in
            listOf(
                live to sourceResult(published),
                published to sourceResult(live),
                published to sourceResult(otherGeneration),
                live to sourceResult(moved),
                live to foreignRoot,
            )) for (outcome in sourceOutcomes(basis, result)) {
            assertEquals(WireEncoding.Rejected(invalidPayload), source.encodeOutcome(outcome))
        }
    }

    @Test
    fun `live traversal wire root agrees with the envelope for complete and qualified outcomes`() {
        val qualification =
            TraversalRunQualification.terminalIncomplete(
                    listOf(TraversalLimitationDocument.DEPTH_LIMIT_REACHED),
                    emptyList(),
                )
                .refined()
        for (root in listOf("/workspace", "/foreign")) {
            val evidence = EvidenceEnvelope(traversal.operation.id, live, TraversalRunResult(text(root), empty()))
            for (outcome in
                listOf(OperationOutcome.Complete(evidence), OperationOutcome.Qualified(evidence, qualification))) {
                if (root == "/foreign") {
                    assertEquals(WireEncoding.Rejected(invalidPayload), traversal.encodeOutcome(outcome))
                } else {
                    val document = traversal.encodeOutcome(outcome).encoded()
                    assertEquals(WireDecoding.Decoded(outcome), traversal.decodeOutcome(document.toString()))
                    val body = document.getValue("body").jsonObject
                    val result = body.getValue("result").jsonObject.with("snapshotRoot", JsonPrimitive("/foreign"))
                    val conflicting = document.with("body", body.with("result", result))
                    assertEquals(WireDecoding.Rejected(invalidPayload), traversal.decodeOutcome(conflicting.toString()))
                }
            }
        }
    }

    private fun sourceOutcomes(
        basis: EvidenceBasis,
        result: SourceReadResult,
    ): List<OperationOutcome<SourceReadResult, SourceReadQualification, SourceReadRejection>> {
        val evidence = EvidenceEnvelope(source.operation.id, basis, result)
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCountDocument.parse(0).refined(),
                    listOf(SourceReadLimitationDocument.WORK_LIMIT_REACHED),
                    SourceReadContinuationStateDocument.Unavailable,
                )
                .refined()
        return listOf(OperationOutcome.Complete(evidence), OperationOutcome.Qualified(evidence, qualification))
    }

    private fun sourceResult(basis: EvidenceBasis): SourceReadResult {
        val snapshot =
            SourceSnapshotDocument(
                text(if (basis is EvidenceBasis.Live) basis.evidence.workspaceRoot else "/workspace"),
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
        val zero = ProtocolOffset.parse(0).refined()
        val selection =
            SourceSelectionDocument(
                text("source-selector-v1:fixture"),
                SourceSelectionRangeDocument.create(zero, zero).refined(),
            )
        return SourceReadResult(
            snapshot,
            SourceRegionDocument(SourceRegionKindDocument.FILE, selection),
            empty(),
            SourceTextProjectionDocument.NotRequested,
        )
    }

    private fun live(root: String, host: String, epoch: Long) =
        EvidenceBasis.Live(
            LiveReadEvidence.create(root, UUID.fromString(host), epoch, LiveReadContentView.SAVED_PSI_COMMITTED, 1)
                .refined()
        )

    private fun WireEncoding.encoded() = Json.parseToJsonElement((this as WireEncoding.Encoded).document).jsonObject

    private fun JsonObject.replaceSnapshot(snapshot: JsonElement): JsonObject {
        val body = getValue("body").jsonObject
        val result = body.getValue("result").jsonObject
        return with("body", body.with("result", result.with("snapshot", snapshot)))
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> empty(): BoundedProtocolList<T> = BoundedProtocolList.create(emptyList<T>()).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
