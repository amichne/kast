package support.architecture.projection

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
        val root = architectureProjectionJson.decodeFromString(
            ArchitectureProjectionDocument.serializer(),
            first,
        )

        assertEquals(first, second)
        assertEquals(1, root.schemaVersion)
        assertEquals(36, root.modules.size)
        assertTrue(first.endsWith("\n"))
    }

    @Test
    fun `module projection preserves validated cost and convention`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val root = architectureProjectionJson.decodeFromString(
            ArchitectureProjectionDocument.serializer(),
            ArchitectureProjection.render(architecture),
        )
        val module = root.modules.single { item -> item.projectPath == ":symbol:intellij" }

        assertEquals("BOUNDED_READ", module.cost)
        val convention = assertInstanceOf<ModuleRoleConventionDocument.Required>(module.roleConvention)
        assertEquals("kast.role.intellij-read", convention.pluginId)
    }
}
