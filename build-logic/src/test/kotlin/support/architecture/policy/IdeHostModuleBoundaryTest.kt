package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class IdeHostModuleBoundaryTest {
    @Test
    fun `hosted composition is narrow and the plugin owns only host publication effects`() {
        val architecture = canonical()
        val composition = architecture.modules.getValue(ModuleId.RUNTIME_IDE_HOST)
        val plugin = architecture.modules.getValue(ModuleId.IDE_PLUGIN)

        assertEquals(ModuleRole.COMPOSITION, composition.role)
        assertEquals(HOSTED_COMPOSITION_DEPENDENCIES, composition.allowedProjectDependencies)
        assertFalse(ModuleId.RUNTIME_COMPOSITION in composition.allowedProjectDependencies)
        assertTrue(composition.allowedEffects.isEmpty())

        assertEquals(ModuleRole.IDE_HOST, plugin.role)
        assertEquals(
            setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY,
                ForbiddenEffect.UDS_BIND,
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE,
            ),
            plugin.allowedEffects,
        )
        assertFalse(ForbiddenEffect.INTELLIJ_WRITE in plugin.allowedEffects)
        assertFalse(ForbiddenEffect.JDBC in plugin.allowedEffects)
        assertFalse(ForbiddenEffect.TOPOLOGY_PUBLICATION in plugin.allowedEffects)
    }

    @Test
    fun `ide read role cannot acquire host publication effects`() {
        val definition = KastArchitecturePolicy.definition()
        val widened = definition.copy(
            modules = definition.modules.map { module ->
                if (module.id == ModuleId.RUNTIME_IDE_READ) {
                    module.copy(
                        allowedEffects = module.allowedEffects + ForbiddenEffect.UDS_BIND,
                    )
                } else {
                    module
                }
            },
        )

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(widened),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(
                ModuleId.RUNTIME_IDE_READ,
                ForbiddenEffect.UDS_BIND,
            ) in invalid.failures,
        )
    }

    @Test
    fun `ide host scanning rejects source reads hashes and ambient project discovery`() {
        val caller = JvmMember.of("example/HostedFactory", "create", "()V")

        assertEquals(
            setOf(ForbiddenEffect.PHYSICAL_SOURCE_READ),
            EffectRules.classify(
                ModuleRole.IDE_HOST,
                caller,
                JvmMember.of("java/nio/file/Files", "readAllBytes", "()V"),
            ),
        )
        assertEquals(
            setOf(ForbiddenEffect.SOURCE_CONTENT_HASH),
            EffectRules.classify(
                ModuleRole.IDE_HOST,
                caller,
                JvmMember.of("java/security/MessageDigest", "update", "()V"),
            ),
        )
        assertEquals(
            setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.PROJECT_OPEN,
            ),
            EffectRules.classify(
                ModuleRole.IDE_HOST,
                caller,
                JvmMember.of(
                    "com/intellij/openapi/project/ProjectManager",
                    "getOpenProjects",
                    "()V",
                ),
            ),
        )
    }

    private fun canonical(): ValidatedArchitecturePolicy = assertInstanceOf<
        ArchitecturePolicyValidation.Valid,
        >(KastArchitecturePolicy.validate()).architecture

    private companion object {
        val HOSTED_COMPOSITION_DEPENDENCIES = setOf(
            ModuleId.RUNTIME_IDE_READ,
            ModuleId.RUNTIME_SERVER,
            ModuleId.RUNTIME_TELEMETRY,
            ModuleId.WORKSPACE_CONTRACT,
            ModuleId.WORKSPACE_SERVICE,
            ModuleId.TOPOLOGY_CONTRACT,
            ModuleId.TOPOLOGY_BUILD,
            ModuleId.TOPOLOGY_SERVICE,
            ModuleId.RELATION_CONTRACT,
            ModuleId.RELATION_SERVICE,
            ModuleId.TRAVERSAL_CONTRACT,
            ModuleId.TRAVERSAL_SERVICE,
            ModuleId.DIAGNOSTIC_CONTRACT,
            ModuleId.DIAGNOSTIC_SERVICE,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_PLAN,
            ModuleId.CHANGE_APPLY,
            ModuleId.CHANGE_VERIFY,
            ModuleId.CHANGE_RECOVERY,
            ModuleId.EVIDENCE_CONTRACT,
            ModuleId.EVIDENCE_SQLITE,
        )
    }
}
