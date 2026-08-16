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
        assertEquals(8, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("KOTLIN", root.getValue("policyAuthority").jsonPrimitive.content)
        assertEquals("REPOSITORY_WIDE", root.getValue("enforcementScope").jsonPrimitive.content)
        assertEquals("CLEAN_SLATE_DELIVERY", root.getValue("workflowScope").jsonPrimitive.content)
        assertEquals(30, root.getValue("targetModules").jsonArray.size)
        assertEquals(47, root.getValue("modules").jsonArray.size)
        assertEquals(20, root.getValue("mutationRuntimeProcesses").jsonArray.size)
        assertEquals(34, root.getValue("mutationDeliveryTasks").jsonArray.size)
        assertEquals(1, root.getValue("legacyMigrationEdges").jsonArray.size)
        assertEquals(1, root.getValue("legacyImplementationBridges").jsonArray.size)
        assertEquals(53, root.getValue("legacyAllowances").jsonArray.size)
        assertTrue(first.endsWith("\n"))
    }

    @Test
    fun `module projection preserves validated cost and convention`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val root = Json.parseToJsonElement(ArchitectureProjection.render(architecture)).jsonObject
        val module = root.getValue("modules").jsonArray
            .single { item ->
                item.jsonObject.getValue("projectPath").jsonPrimitive.content == ":symbol:intellij"
            }
            .jsonObject

        assertEquals("BOUNDED_READ", module.getValue("cost").jsonPrimitive.content)
        val convention = module.getValue("roleConvention").jsonObject
        assertEquals("REQUIRED", convention.getValue("kind").jsonPrimitive.content)
        assertEquals("kast.role.intellij-read", convention.getValue("pluginId").jsonPrimitive.content)
    }

    @Test
    fun `migration projection preserves lifecycle and retirement task`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val root = Json.parseToJsonElement(ArchitectureProjection.render(architecture)).jsonObject
        val migration = root.getValue("legacyMigrationEdges").jsonArray.single().jsonObject
        val retirementTask = root.getValue("mutationDeliveryTasks").jsonArray
            .single { task -> task.jsonObject.getValue("id").jsonPrimitive.content == "F04" }
            .jsonObject

        assertEquals(":analysis-server", migration.getValue("consumer").jsonPrimitive.content)
        assertEquals(":runtime:bindings", migration.getValue("dependency").jsonPrimitive.content)
        assertEquals("PLANNED", migration.getValue("lifecycle").jsonPrimitive.content)
        assertEquals("F04", migration.getValue("retirementTask").jsonPrimitive.content)
        assertEquals("OPEN", retirementTask.getValue("lifecycle").jsonPrimitive.content)
    }

    @Test
    fun `implementation bridge projection preserves active lifecycle and retirement owner`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val root = Json.parseToJsonElement(ArchitectureProjection.render(architecture)).jsonObject
        val bridge = root.getValue("legacyImplementationBridges").jsonArray.single().jsonObject
        val retirementTask = root.getValue("mutationDeliveryTasks").jsonArray
            .single { task -> task.jsonObject.getValue("id").jsonPrimitive.content == "M04" }
            .jsonObject

        assertEquals(":evidence:sqlite", bridge.getValue("consumer").jsonPrimitive.content)
        assertEquals(":index-store", bridge.getValue("dependency").jsonPrimitive.content)
        assertEquals("ACTIVE", bridge.getValue("lifecycle").jsonPrimitive.content)
        assertEquals("M04", bridge.getValue("retirementTask").jsonPrimitive.content)
        assertEquals("MIGRATION", retirementTask.getValue("phase").jsonPrimitive.content)
        assertEquals(
            listOf(":evidence:sqlite"),
            retirementTask.getValue("owner").jsonObject
                .getValue("modules").jsonArray
                .map { it.jsonPrimitive.content },
        )
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
        assertEquals(
            setOf("RP10", "RP11S", "RP11E", "RP12", "RP13", "RP14", "RP15", "RP16", "RP17", "RP18"),
            recovery.getValue("failurePoints").jsonArray.mapTo(mutableSetOf()) { failurePoint ->
                failurePoint.jsonPrimitive.content
            },
        )
        assertEquals(
            setOf("ROLLED_BACK", "RECOVERY_REQUIRED"),
            recovery.getValue("terminalOutcomes").jsonArray.mapTo(mutableSetOf()) { terminal ->
                terminal.jsonPrimitive.content
            },
        )
        assertTrue(processes.values.none { "dependsOn" in it.jsonObject })
    }
}
