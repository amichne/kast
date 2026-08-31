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
        assertEquals(2, root.schemaVersion)
        assertEquals(40, root.modules.size)
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

    @Test
    fun `projection preserves exact scoped effect callers`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val root = architectureProjectionJson.decodeFromString(
            ArchitectureProjectionDocument.serializer(),
            ArchitectureProjection.render(architecture),
        )
        val cli = root.modules.single { item -> item.projectPath == ":cli" }
        val filesystem = cli.allowedScopedEffects.single()

        assertEquals("FILESYSTEM_WRITE", filesystem.effect)
        assertEquals(8, filesystem.callerClasses.size)
        assertTrue(filesystem.callerClasses.all { it.startsWith("io/github/amichne/kast/cli/") })
    }
}
