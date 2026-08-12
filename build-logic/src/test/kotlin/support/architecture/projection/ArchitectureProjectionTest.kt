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
        assertEquals(2, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("KOTLIN", root.getValue("policyAuthority").jsonPrimitive.content)
        assertEquals("REPOSITORY_WIDE", root.getValue("enforcementScope").jsonPrimitive.content)
        assertEquals("MUTATION", root.getValue("workflowScope").jsonPrimitive.content)
        assertEquals(31, root.getValue("modules").jsonArray.size)
        assertEquals(20, root.getValue("mutationRuntimeProcesses").jsonArray.size)
        assertEquals(33, root.getValue("mutationDeliveryTasks").jsonArray.size)
        assertEquals(74, root.getValue("legacyAllowances").jsonArray.size)
        assertTrue(first.endsWith("\n"))
    }
}
