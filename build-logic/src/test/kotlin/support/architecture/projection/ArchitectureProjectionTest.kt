package support.architecture.projection

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
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
        assertEquals(architecture.modules.size, root.modules.size)
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

        val encodedModules = architectureProjectionJson.parseToJsonElement(
            ArchitectureProjection.render(architecture),
        ).jsonObject.getValue("modules").jsonArray
        encodedModules.forEach { encodedModule ->
            val encodedConvention = encodedModule.jsonObject.getValue("roleConvention").jsonObject
            assertEquals(setOf("kind", "pluginId"), encodedConvention.keys)
            assertEquals("REQUIRED", encodedConvention.getValue("kind").jsonPrimitive.content)
        }
    }

    @Test
    fun `retired unmarked convention cannot decode`() {
        assertThrows<SerializationException> {
            architectureProjectionJson.decodeFromString(
                ModuleRoleConventionDocument.serializer(),
                architectureProjectionJson.encodeToString(
                    RetiredConventionFixture.serializer(),
                    RetiredConventionFixture(kind = "UNMARKED_LEGACY"),
                ),
            )
        }
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
        assertEquals(15, filesystem.callerClasses.size)
        assertTrue("io/github/amichne/kast/cli/PosixRuntimeEndpointArtifacts" in filesystem.callerClasses)
        assertTrue("io/github/amichne/kast/cli/ide/FilesystemBrokerTrustRegistrar" in filesystem.callerClasses)
        assertTrue(filesystem.callerClasses.all { it.startsWith("io/github/amichne/kast/cli/") })
    }

    @Serializable
    private data class RetiredConventionFixture(val kind: String)
}
