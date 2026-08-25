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

    @Test fun `no task or projection has manual status`() {
        val projection = canonicalJson(validated.projection())
        assertFalse("\"status\"" in projection)
        assertFalse("completed" in projection.lowercase())
    }

    @Test fun `default read graph has no privileged effect owner`() {
        val forbidden = setOf("PROCESS_START", "GRADLE_IMPORT", "VFS_REFRESH", "SOURCE_WRITE", "JDBC", "TOPOLOGY_BUILD", "NETWORK_READ", "RUNTIME_ARCHIVE_READ")
        validated.program.effects.filter { it.id.value in forbidden }.forEach { assertTrue(it.owners.isEmpty(), it.id.value) }
    }

    @Test fun `every task owns focused red and green proof`() {
        validated.program.tasks.forEach { task ->
            assertTrue(task.red.command.startsWith("./gradlew "))
            assertTrue(task.green.command.startsWith("./gradlew "))
            assertEquals(setOf(task.red.gateId, task.green.gateId), task.completionReceipt.requiredGateIds)
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
        assertEquals(admitted.program.tasks.size * 3, admitted.program.gates.size)
        assertEquals(
            admitted.program.authorities.size,
            admitted.program.authorities.map { it.id }.toSet().size,
        )
        assertEquals(
            admitted.program.requirements.map { it.id }.toSet(),
            admitted.program.tasks.flatMap { it.provesRequirements }.toSet(),
        )
        val proof = assertInstanceOf(
            Kvp004ProgramProofResult.Complete::class.java,
            deriveKvp004ProgramProof(),
        ).proof
        val decoded = assertInstanceOf(
            Kvp004ProgramProofResult.Complete::class.java,
            decodeKvp004ProgramProof(encodeKvp004ProgramProof(proof)),
        ).proof
        assertEquals(admitted.order, decoded.program.order)
        assertEquals(admitted.waves, decoded.program.waves)
        val changed = encodeKvp004ProgramProof(proof)
            .replace("\"terminalTaskId\": \"KVP-043\"", "\"terminalTaskId\": \"KVP-042\"")
        assertEquals(
            Kvp004ProgramProofResult.Rejected(Kvp004ProgramProofFailure.TERMINAL_MISMATCH),
            decodeKvp004ProgramProof(changed),
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
