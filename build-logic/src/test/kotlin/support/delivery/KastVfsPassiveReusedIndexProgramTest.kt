package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        writeProgramProof(admitted)
    }

    private fun writeProgramProof(admitted: ValidatedProgram) {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val root = if (Files.isDirectory(workingDirectory.resolve("gradle/delivery"))) {
            workingDirectory
        } else {
            workingDirectory.parent
        }
        require(Files.isDirectory(root.resolve("gradle/delivery")))
        val program = admitted.program
        val document = CanonicalProgramProofDocument(
            schemaVersion = 1,
            taskId = "KVP-004",
            outcome = "COMPLETE",
            taskCount = program.tasks.size,
            requirementCount = program.requirements.size,
            moduleCount = program.modules.size,
            authorityCount = program.authorities.size,
            effectCount = program.effects.size,
            processNodeCount = program.processNodes.size,
            processTransitionCount = program.processTransitions.size,
            gateCount = program.gates.size,
            terminalTaskId = program.terminalTask.value,
            taskOrder = admitted.order.map { it.value },
            waveCount = admitted.waves.values.max() + 1,
        )
        val output = root.resolve("build/reports/delivery/KVP-004-program.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, programProofJson.encodeToString(document) + "\n")
    }

    private companion object {
        val programProofJson = Json { prettyPrint = true }
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

@Serializable
private data class CanonicalProgramProofDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: String,
    val taskCount: Int,
    val requirementCount: Int,
    val moduleCount: Int,
    val authorityCount: Int,
    val effectCount: Int,
    val processNodeCount: Int,
    val processTransitionCount: Int,
    val gateCount: Int,
    val terminalTaskId: String,
    val taskOrder: List<String>,
    val waveCount: Int,
)
