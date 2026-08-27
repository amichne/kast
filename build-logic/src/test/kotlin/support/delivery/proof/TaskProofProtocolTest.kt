package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskProofProtocolTest {
    private val program = KastVfsPassiveReusedIndexProgram.validated

    @Test
    fun `legacy receipts stop at the admitted prefix and atomic proofs begin at KVP 025`() {
        val legacy = program.program.tasks.filter { it.id < TaskId("KVP-025") }
        val atomic = program.program.tasks.filter { it.id >= TaskId("KVP-025") }

        assertEquals(24, legacy.size)
        assertTrue(legacy.all { it.proof is TaskProofProtocol.Legacy })
        assertEquals(19, atomic.size)
        assertTrue(atomic.all { it.proof is TaskProofProtocol.Atomic })
        assertEquals(24 * 3 + 19, program.gates.size)
    }

    @Test
    fun `KVP 025 packet derives one command and one content scoped receipt from the graph`() {
        val frontier = program.program.tasks.single { it.id == TaskId("KVP-025") }
        val proof = assertInstanceOf(TaskProofProtocol.Atomic::class.java, frontier.proof)
        val packet = assertInstanceOf(
            TaskPacketAdmission.Complete::class.java,
            program.packet(frontier.id),
        ).packet

        assertEquals("./gradlew proveKVP025", proof.command.command)
        assertEquals(
            "physically replaced descriptor is preserved and retirement rejects its identity",
            proof.command.misuse.namedCase,
        )
        assertEquals(
            "READY retires owned artifacts exactly once and preserves a later generation",
            proof.command.legalPath.namedCase,
        )
        assertEquals(frontier.id, proof.command.gate.taskId)
        assertEquals(setOf(ReceiptId("KVP-024-COMPLETE")), proof.receipt.dependencies)
        assertEquals(ReceiptId("KVP-025-COMPLETE"), proof.receipt.receiptId)
        assertFalse(proof.receipt.requiresExactHead)
        assertEquals(frontier, packet.task)
        assertEquals(frontier.definitionDigest(), packet.taskDefinitionDigest)
        assertEquals(proof.command, packet.proofCommand)
        assertEquals(proof.receipt, packet.receipt)
    }

    @Test
    fun `only declared milestone proofs require exact head`() {
        val exactHeadTasks = program.program.tasks.mapNotNull { task ->
            val proof = task.proof as? TaskProofProtocol.Atomic ?: return@mapNotNull null
            task.id.takeIf { proof.receipt.requiresExactHead }
        }.toSet()

        assertEquals(
            setOf(TaskId("KVP-031"), TaskId("KVP-034"), TaskId("KVP-036"), TaskId("KVP-043")),
            exactHeadTasks,
        )
    }

    @Test
    fun `generated KVP 025 packet re admits only canonical graph bytes`() {
        val packet = assertInstanceOf(
            TaskPacketAdmission.Complete::class.java,
            program.packet(TaskId("KVP-025")),
        ).packet
        val version = TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
        val canonical = encodeTaskPacket(packet, version)

        assertInstanceOf(
            TaskPacketFileAdmission.Complete::class.java,
            admitTaskPacket(canonical, packet, version),
        )
        assertEquals(
            TaskPacketFileAdmission.Rejected(TaskPacketFileFailure.PACKET_MISMATCH),
            admitTaskPacket(
                canonical.replaceFirst("\"id\": \"KVP-025\"", "\"id\": \"KVP-026\""),
                packet,
                version,
            ),
        )
    }

    @Test
    fun `KVP 025 closure includes its proof implementation tests and build inputs`() {
        val task = program.program.tasks.single { it.id == TaskId("KVP-025") }

        assertTrue(
            task.allowedReads.containsAll(setOf(
                "build-logic",
                "ide-plugin/build.gradle.kts",
                "ide-plugin/src/main",
                "ide-plugin/src/test",
                "gradle/libs.versions.toml",
                "protocol",
            )),
        )
        assertTrue(
            program.program.requirements.single { it.id == RequirementId("KVP-REQ-004") }
                .statement.contains("content-scoped receipts remain reusable"),
        )
    }

    @Test
    fun `content scoped KVP 025 output is independent of later observed head`() {
        val (packet, version) = canonicalKvp025Packet()
        val admittedPacket = assertInstanceOf(
            TaskPacketFileAdmission.Complete::class.java,
            admitTaskPacket(encodeTaskPacket(packet, version), packet, version),
        ).admitted
        val predecessor = AdmittedLegacyReceiptPrefix(
            TaskId("KVP-024"),
            ReceiptId("KVP-024-COMPLETE"),
            TaskProofDependencyDigest(LEGACY_PREFIX_FRONTIER_RECEIPT_DIGEST),
            DeliveryGeneration("1".repeat(40)),
        )
        val cases = assertInstanceOf(
            Kvp025ProofCaseExpectationAdmission.Complete::class.java,
            expectedKvp025ProofCases(packet),
        ).expectation
        val base = Kvp025ProofReportContext(
            version,
            admittedPacket,
            predecessor,
            cases,
            AdmittedKvp025ImplementationScope(
                listOf(Kvp025ImplementationCommit(DeliveryGeneration("2".repeat(40)), listOf(
                    "ide-plugin/src/main/kotlin/Endpoint.kt",
                ))),
            ),
            RelevantInputDigest("3".repeat(64)),
            TaskProofCommandDigest("4".repeat(64)),
            ToolchainDigest("5".repeat(64)),
            DeliveryGeneration("6".repeat(40)),
        )

        assertEquals(
            canonicalKvp025ProofReport(base),
            canonicalKvp025ProofReport(
                base.copy(observedHead = DeliveryGeneration("7".repeat(40))),
            ),
        )
    }
}
