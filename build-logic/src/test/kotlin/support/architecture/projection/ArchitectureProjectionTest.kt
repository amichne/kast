package support.architecture.projection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import support.architecture.ArchitecturePolicyValidation
import support.architecture.KastArchitecturePolicy

class ArchitectureProjectionTest {
    @Test
    fun `projection is deterministic valid JSON from typed policy`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

        val first = ArchitectureProjection.render(architecture)
        val second = ArchitectureProjection.render(architecture)
        val root = Json.parseToJsonElement(first).jsonObject

        assertEquals(first, second)
        assertEquals(3, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("KOTLIN", root.getValue("policyAuthority").jsonPrimitive.content)
        assertEquals("REPOSITORY_WIDE", root.getValue("enforcementScope").jsonPrimitive.content)
        assertEquals("MUTATION", root.getValue("workflowScope").jsonPrimitive.content)
        assertEquals(37, root.getValue("modules").jsonArray.size)
        assertEquals(20, root.getValue("mutationRuntimeProcesses").jsonArray.size)
        assertEquals(33, root.getValue("mutationDeliveryTasks").jsonArray.size)
        assertEquals(74, root.getValue("legacyAllowances").jsonArray.size)
        assertTrue(first.endsWith("\n"))
    }

    @Test
    fun `runtime projection preserves lane selection and recovery interrupt semantics`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val processes = Json.parseToJsonElement(ArchitectureProjection.render(architecture))
            .jsonObject
            .getValue("mutationRuntimeProcesses")
            .jsonArray
            .associateBy { process -> process.jsonObject.getValue("id").jsonPrimitive.content }

        val semantic = processes.getValue("RP11S").jsonObject.getValue("admission").jsonObject
        val external = processes.getValue("RP11E").jsonObject.getValue("admission").jsonObject
        val join = processes.getValue("RP12").jsonObject.getValue("admission").jsonObject
        val recovery = processes.getValue("RP19").jsonObject.getValue("admission").jsonObject

        assertEquals("APPLY_LANE", semantic.getValue("kind").jsonPrimitive.content)
        assertEquals("SEMANTIC", semantic.getValue("lane").jsonPrimitive.content)
        assertEquals("APPLY_LANE", external.getValue("kind").jsonPrimitive.content)
        assertEquals("EXTERNAL", external.getValue("lane").jsonPrimitive.content)
        assertEquals("SELECTED_APPLY_LANE_JOIN", join.getValue("kind").jsonPrimitive.content)
        assertEquals(
            setOf("SEMANTIC" to "RP11S", "EXTERNAL" to "RP11E"),
            join.getValue("lanes").jsonArray.mapTo(mutableSetOf()) { lane ->
                val projectedLane = lane.jsonObject
                projectedLane.getValue("lane").jsonPrimitive.content to
                    projectedLane.getValue("process").jsonPrimitive.content
            },
        )
        assertEquals(
            "RECOVERY_INTERRUPT_AFTER_PREPARATION",
            recovery.getValue("kind").jsonPrimitive.content,
        )
        assertEquals("RP09", recovery.getValue("preparedBy").jsonPrimitive.content)
        assertTrue(processes.values.none { "dependsOn" in it.jsonObject })
    }
}
