package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeliveryProgramModelNegativeTest {
    @Test
    fun `blank task identity is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskId("")
        }
    }

    @Test
    fun `invalid boundary values return finite failures`() {
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_TASK_ID),
            refineTaskId("task-2"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_GENERATION),
            refineDeliveryGeneration("main"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_AUTHORITY_ID),
            refineAuthorityId("delivery authority"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_EFFECT_ID),
            refineEffectId("filesystem-read"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_COST_ID),
            refineCostId("build policy"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_GATE_ID),
            refineGateId("KVP-002"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.INVALID_RECEIPT_ID),
            refineReceiptId("KVP-002-RECEIPT"),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.EMPTY_EVIDENCE),
            EvidenceSet.refine(emptyList()),
        )
        assertEquals(
            DeliveryRefinement.Rejected(DeliveryModelFailure.EMPTY_LIMITATIONS),
            NonEmptyLimitations.refine(listOf("")),
        )
    }

    @Test
    fun `checked authoring constructors reject invalid classifications`() {
        assertThrows(IllegalArgumentException::class.java) { AuthorityId("authority") }
        assertThrows(IllegalArgumentException::class.java) { EffectId("effect") }
        assertThrows(IllegalArgumentException::class.java) { CostId("cost") }
        assertThrows(IllegalArgumentException::class.java) { Sha256("0") }
        assertThrows(IllegalArgumentException::class.java) {
            TaskProgression.Blocked(TaskId("KVP-002"), emptySet())
        }
    }
}

class DeliveryProgramModelTest {
    @Test
    fun `domain families preserve their established proofs`() {
        val taskId = complete(refineTaskId("KVP-002"))
        val generation = complete(refineDeliveryGeneration("78262728313c90bb847e73425dc1a76d704397db"))
        val authority = complete(refineAuthorityId("DELIVERY_PROGRAM"))
        val effect = complete(refineEffectId("BUILD_POLICY_WRITE"))
        val cost = complete(refineCostId("BUILD_POLICY"))
        val gate = complete(refineGateId("KVP-002-GREEN"))
        val receipt = complete(refineReceiptId("KVP-002-COMPLETE"))
        val dependency = DependencyExpression(EdgeKind.REQUIRES_ALL, setOf(TaskId("KVP-001")))
        val evidence = complete(
            EvidenceSet.refine(
                listOf(Evidence(EvidenceKind.PROOF_ARTIFACT, Sha256("a".repeat(64)))),
            ),
        )
        val limitations = complete(NonEmptyLimitations.refine(listOf("bounded qualification")))
        val progression: TaskProgression = TaskProgression.Proven(taskId, receipt)
        val outcomes: List<Outcome<String, DeliveryModelFailure>> = listOf(
            Outcome.Complete("complete", evidence),
            Outcome.Qualified("qualified", evidence, limitations),
            Outcome.Rejected(DeliveryModelFailure.INVALID_TASK_ID, evidence),
        )

        assertEquals("KVP-002", taskId.value)
        assertEquals(KastVfsPassiveReusedIndexProgram.validated.program.generation, generation)
        assertEquals("DELIVERY_PROGRAM", authority.value)
        assertEquals("BUILD_POLICY_WRITE", effect.value)
        assertEquals("BUILD_POLICY", cost.value)
        assertEquals("KVP-002-GREEN", gate.value)
        assertEquals("KVP-002-COMPLETE", receipt.value)
        assertEquals(setOf(TaskId("KVP-001")), dependency.taskIds)
        assertInstanceOf(TaskProgression.Proven::class.java, progression)
        assertEquals(listOf("complete", "qualified", "INVALID_TASK_ID"), outcomes.map(::projectOutcome))
        assertTrue(
            KastVfsPassiveReusedIndexProgram.validated.program.tasks
                .single { it.id == taskId }
                .costClassifications
                .contains(cost),
        )
        assertFalse(
            TaskProgression::class.sealedSubclasses
                .flatMap { it.members }
                .any { it.name == "status" },
        )

        writeProofReport(
            Kvp002TypeProofDocument(
                taskId = taskId.value,
                outcome = "COMPLETE",
                provedTypeFamilies = provedTypeFamilies,
            ),
        )
    }

    private fun <T> complete(refinement: DeliveryRefinement<T>): T = when (refinement) {
        is DeliveryRefinement.Complete -> refinement.value
        is DeliveryRefinement.Rejected -> error("unexpected rejection: ${refinement.failure}")
    }

    private fun projectOutcome(outcome: Outcome<String, DeliveryModelFailure>): String =
        when (outcome) {
            is Outcome.Complete -> outcome.value
            is Outcome.Qualified -> outcome.value
            is Outcome.Rejected -> outcome.failure.name
        }

    private fun writeProofReport(document: Kvp002TypeProofDocument) {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val repositoryRoot = if (Files.isDirectory(workingDirectory.resolve("gradle/delivery"))) {
            workingDirectory
        } else {
            workingDirectory.parent
        }
        require(Files.isDirectory(repositoryRoot.resolve("gradle/delivery")))
        val output = repositoryRoot.resolve("build/reports/delivery/KVP-002-types.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, proofJson.encodeToString(Kvp002TypeProofDocument.serializer(), document) + "\n")
    }

    private companion object {
        val proofJson = Json {
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        }
        val provedTypeFamilies = listOf(
            "identity",
            "generation",
            "dependency",
            "authority",
            "effect",
            "cost",
            "evidence",
            "gate",
            "receipt",
            "progression",
            "closedOutcome",
        )
    }
}

@Serializable
private data class Kvp002TypeProofDocument(
    val taskId: String,
    val outcome: String,
    val provedTypeFamilies: List<String>,
)
