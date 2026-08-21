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
    fun `projection is deterministic valid JSON from clean slate policy`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

        val first = ArchitectureProjection.render(architecture)
        val second = ArchitectureProjection.render(architecture)
        val root = Json.parseToJsonElement(first).jsonObject

        assertEquals(first, second)
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(32, root.getValue("modules").jsonArray.size)
        assertEquals(setOf("schemaVersion", "modules"), root.keys)
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
}
