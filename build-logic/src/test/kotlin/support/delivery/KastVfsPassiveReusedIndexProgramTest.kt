package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastVfsPassiveReusedIndexProgramTest {
    private val validated = KastVfsPassiveReusedIndexProgram.validated

    @Test fun `program targets requested origin main`() {
        assertEquals("78262728313c90bb847e73425dc1a76d704397db", validated.program.targetHead)
    }

    @Test fun `requirement trace carries derived program identity`() {
        val programFingerprint = validated.projection().getValue("programFingerprint")
        val trace = validated.requirementTraceProjection()
        assertEquals(programFingerprint, trace.getValue("programFingerprint"))
        assertEquals(27, (trace.getValue("entries") as List<*>).size)
    }

    @Test fun `waves are derived and terminal is last`() {
        assertEquals(TaskId("KVP-043"), validated.order.last())
        assertEquals(validated.waves.values.maxOrNull(), validated.waves.getValue(TaskId("KVP-043")))
    }

    @Test fun `final plugin layout follows hosted runtime and compatibility remains actionable`() {
        val tasks = validated.program.tasks.associateBy { it.id }
        val layout = tasks.getValue(TaskId("KVP-011"))
        val compatibility = tasks.getValue(TaskId("KVP-012"))
        val staticSafety = tasks.getValue(TaskId("KVP-032"))

        assertEquals(
            setOf(TaskId("KVP-010"), TaskId("KVP-025"), TaskId("KVP-031")),
            layout.dependencies.taskIds,
        )
        assertEquals(
            setOf(TaskId("KVP-002"), TaskId("KVP-010")),
            compatibility.dependencies.taskIds,
        )
        assertEquals(
            setOf(
                TaskId("KVP-009"),
                TaskId("KVP-011"),
                TaskId("KVP-023"),
                TaskId("KVP-027"),
                TaskId("KVP-031"),
            ),
            staticSafety.dependencies.taskIds,
        )
        assertEquals(
            setOf("kvp.010.proof", "kvp.025.proof", "kvp.031.proof"),
            layout.taskOutputInputIds(),
        )
        assertEquals(setOf("kvp.002.proof", "kvp.010.proof"), compatibility.taskOutputInputIds())
        assertEquals(
            setOf(
                "kvp.009.proof",
                "kvp.011.proof",
                "kvp.023.proof",
                "kvp.027.proof",
                "kvp.031.proof",
            ),
            staticSafety.taskOutputInputIds(),
        )
        assertEquals(
            setOf("KVP-010-COMPLETE", "KVP-025-COMPLETE", "KVP-031-COMPLETE"),
            layout.completionReceipt.dependencyReceiptIds,
        )
        assertEquals(
            setOf("KVP-002-COMPLETE", "KVP-010-COMPLETE"),
            compatibility.completionReceipt.dependencyReceiptIds,
        )
        assertEquals(
            setOf(
                "KVP-009-COMPLETE",
                "KVP-011-COMPLETE",
                "KVP-023-COMPLETE",
                "KVP-027-COMPLETE",
                "KVP-031-COMPLETE",
            ),
            staticSafety.completionReceipt.dependencyReceiptIds,
        )
        assertTrue(validated.waves.getValue(TaskId("KVP-011")) > validated.waves.getValue(TaskId("KVP-031")))
        assertFalse(RequirementId("KVP-REQ-021") in layout.provesRequirements)
        assertTrue(
            RequirementId("KVP-REQ-021") in
                tasks.getValue(TaskId("KVP-035")).provesRequirements,
        )
    }

    private fun TaskNode.taskOutputInputIds(): Set<String> = inputs
        .filter { it["kind"] == "taskOutput" }
        .mapTo(linkedSetOf()) { it.getValue("id") }

    @Test fun `no task or projection has manual status`() {
        val projection = canonicalJson(validated.projection())
        assertFalse("\"status\"" in projection)
        assertFalse("completed" in projection.lowercase())
    }

    @Test fun `default read graph has no privileged effect owner`() {
        val forbidden = setOf("PROCESS_START", "GRADLE_IMPORT", "VFS_REFRESH", "SOURCE_WRITE", "JDBC", "TOPOLOGY_BUILD", "NETWORK_READ", "RUNTIME_ARCHIVE_READ")
        validated.program.effects.filter { it.id.value in forbidden }.forEach { assertTrue(it.owners.isEmpty(), it.id.value) }
    }

    @Test fun `every task owns one closed proof protocol`() {
        validated.program.tasks.forEach { task ->
            when (val proof = task.proof) {
                is TaskProofProtocol.Legacy -> {
                    assertTrue(proof.red.command.startsWith("./gradlew "))
                    assertTrue(proof.green.command.startsWith("./gradlew "))
                    assertEquals(
                        setOf(proof.red.gateId, proof.green.gateId),
                        proof.completion.requiredGateIds,
                    )
                }
                is TaskProofProtocol.Atomic -> {
                    assertTrue(proof.command.command.startsWith("./gradlew proveKVP"))
                    assertEquals(1, proof.gates.size)
                }
            }
        }
    }

    @Test fun `canonical graph closes every owner and reaches one terminal`() {
        val admitted = assertInstanceOf(
            CanonicalProgramAdmission.Complete::class.java,
            admitCanonicalProgram(KastVfsPassiveReusedIndexProgram.definition),
        ).program
        val sinks = admitted.program.tasks.map { it.id }.filter { candidate ->
            admitted.program.tasks.none { candidate in it.dependencies.taskIds }
        }

        assertEquals(listOf(admitted.program.terminalTask), sinks)
        val expectedGateCount = admitted.program.tasks.sumOf { task ->
            when (task.proof) {
                is TaskProofProtocol.Legacy -> 3
                is TaskProofProtocol.Atomic -> 1
            }
        }
        assertEquals(expectedGateCount, admitted.program.gates.size)
        assertEquals(
            admitted.program.authorities.size,
            admitted.program.authorities.map { it.id }.toSet().size,
        )
        assertEquals(
            admitted.program.requirements.map { it.id }.toSet(),
            admitted.program.tasks.flatMap { it.provesRequirements }.toSet(),
        )
    }
}

class KastVfsPassiveProgramNegativeTest {
    private val program = KastVfsPassiveReusedIndexProgram.definition

    @Test fun `missing task contract rejects as finite failure`() {
        val first = program.tasks.first()
        val incomplete = program.copy(
            tasks = program.tasks.map { if (it.id == first.id) it.copy(title = "") else it },
        )
        assertEquals(
            CanonicalProgramAdmission.Rejected(
                CanonicalProgramFailure.INCOMPLETE_TASK_CONTRACT,
            ),
            admitCanonicalProgram(incomplete),
        )
    }

    @Test fun `duplicate authority owner rejects as finite failure`() {
        val contradictory = program.copy(authorities = program.authorities + program.authorities.first())
        assertEquals(
            CanonicalProgramAdmission.Rejected(
                CanonicalProgramFailure.DUPLICATE_AUTHORITY_OWNER,
            ),
            admitCanonicalProgram(contradictory),
        )
    }

    @Test fun `untraced requirement rejects as finite failure`() {
        val untraced = program.copy(
            requirements = program.requirements +
                Requirement(RequirementId("KVP-REQ-999"), "Fixture must remain untraced."),
        )
        assertEquals(
            CanonicalProgramAdmission.Rejected(CanonicalProgramFailure.UNTRACED_REQUIREMENT),
            admitCanonicalProgram(untraced),
        )
    }

    @Test fun `cycle rejects as finite failure`() {
        val first = program.tasks.first()
        val cyclic = program.copy(
            tasks = program.tasks.map {
                if (it.id == first.id) {
                    it.copy(
                        dependencies = DependencyExpression(
                            EdgeKind.REQUIRES_ALL,
                            setOf(program.terminalTask),
                        ),
                    )
                } else {
                    it
                }
            },
        )
        assertEquals(
            CanonicalProgramAdmission.Rejected(CanonicalProgramFailure.CYCLE),
            admitCanonicalProgram(cyclic),
        )
    }
}

class DeliveryProjectionTest {
    @Test fun `independent generations admit with five exact artifacts`() {
        val first = DeterministicProgramProjection.generate(
            KastVfsPassiveReusedIndexProgram.validated,
        )
        val second = DeterministicProgramProjection.generate(
            KastVfsPassiveReusedIndexProgram.validated,
        )

        val admitted = assertInstanceOf(
            DeliveryProjectionAdmission.Admitted::class.java,
            admitDeterministicProgramProjection(first, second),
        ).projection

        assertEquals(ProjectionArtifactId.entries.toSet(), admitted.artifactDigests.keys)
        assertEquals(first, admitted.generation)
    }

    @Test fun `schema-invalid program rejects as finite failure`() {
        val canonical = DeterministicProgramProjection.generate(
            KastVfsPassiveReusedIndexProgram.validated,
        )
        val invalid = canonical.replacing(
            ProjectionArtifactId.PROGRAM,
            canonical.program.replace(
                KastVfsPassiveReusedIndexProgram.validated.program.targetHead,
                "invalid",
            ),
        )

        assertEquals(
            DeliveryProjectionAdmission.Rejected(
                DeliveryProjectionFailure.SCHEMA_VALIDATION_FAILED,
            ),
            admitDeterministicProgramProjection(invalid, invalid),
        )
    }

    @Test fun `generated proof report round-trips and tampering rejects`() {
        val proof = assertInstanceOf(
            Kvp005ProjectionProofResult.Complete::class.java,
            deriveKvp005ProjectionProof(),
        ).proof
        val encoded = encodeKvp005ProjectionProof(proof)
        assertInstanceOf(
            Kvp005ProjectionProofResult.Complete::class.java,
            decodeKvp005ProjectionProof(encoded),
        )

        val digest = proof.projection.artifactDigests.getValue(ProjectionArtifactId.PROGRAM).value
        assertEquals(
            Kvp005ProjectionProofResult.Rejected(
                Kvp005ProjectionProofFailure.ARTIFACT_DIGEST_MISMATCH,
            ),
            decodeKvp005ProjectionProof(encoded.replace(digest, "0".repeat(64))),
        )
    }

    @Test fun `negative projection cases refine to exact finite failures`() {
        val proof = assertInstanceOf(
            Kvp005ProjectionNegativeProofResult.Complete::class.java,
            deriveKvp005ProjectionNegativeProof(),
        ).proof

        assertEquals(DeliveryProjectionNegativeCase.entries, proof.cases)
        assertEquals(
            listOf(
                DeliveryProjectionFailure.NON_CANONICAL_JSON,
                DeliveryProjectionFailure.NON_REPEATABLE_GENERATION,
                DeliveryProjectionFailure.SCHEMA_VALIDATION_FAILED,
                DeliveryProjectionFailure.STATUS_FIELD_PRESENT,
            ),
            proof.failures,
        )
    }
}
