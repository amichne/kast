package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test fun `cycle rejects`() {
        val first = validated.program.tasks.first()
        val broken = validated.program.copy(tasks = validated.program.tasks.map { if (it.id == first.id) it.copy(dependencies = DependencyExpression(EdgeKind.REQUIRES_ALL, setOf(validated.program.terminalTask))) else it })
        assertThrows(IllegalArgumentException::class.java) { broken.validate() }
    }
}
