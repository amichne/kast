package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class KastCleanSlatePolicyTest {
    @Test
    fun `terminal policy contains exactly the clean slate project surface`() {
        val architecture = canonicalArchitecture()

        assertEquals(
            setOf(
                ":kernel",
                ":distribution:contract",
                ":distribution:managed",
                ":protocol:contract",
                ":protocol:registry",
                ":protocol:wire",
                ":workspace:contract",
                ":workspace:service",
                ":workspace:intellij",
                ":symbol:contract",
                ":symbol:service",
                ":symbol:intellij",
                ":relation:contract",
                ":relation:service",
                ":relation:intellij",
                ":traversal:contract",
                ":traversal:service",
                ":diagnostic:contract",
                ":diagnostic:service",
                ":diagnostic:intellij",
                ":change:contract",
                ":change:plan",
                ":change:apply",
                ":change:verify",
                ":change:recovery",
                ":change:intellij",
                ":evidence:contract",
                ":evidence:sqlite",
                ":runtime:server",
                ":runtime:composition",
                ":cli",
                ":indexer",
            ),
            architecture.modules.values.mapTo(linkedSetOf()) { it.id.projectPath },
        )
    }

    @Test
    fun `terminal policy assigns every privileged effect to its sole owner`() {
        val architecture = canonicalArchitecture()
        val owners = ForbiddenEffect.entries.associateWith { effect ->
            architecture.modules.values
                .filter { effect in it.allowedEffects }
                .mapTo(linkedSetOf(), ValidatedModulePolicy::id)
        }

        assertEquals(
            mapOf(
                ForbiddenEffect.INTELLIJ_PLATFORM to setOf(
                    ModuleId.WORKSPACE_INTELLIJ,
                    ModuleId.SYMBOL_INTELLIJ,
                    ModuleId.RELATION_INTELLIJ,
                    ModuleId.DIAGNOSTIC_INTELLIJ,
                    ModuleId.CHANGE_INTELLIJ,
                    ModuleId.INDEXER,
                ),
                ForbiddenEffect.INTELLIJ_WRITE to setOf(ModuleId.CHANGE_INTELLIJ),
                ForbiddenEffect.FILESYSTEM_WRITE to setOf(
                    ModuleId.DISTRIBUTION_MANAGED,
                    ModuleId.INDEXER,
                ),
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE to emptySet(),
                ForbiddenEffect.JDBC to setOf(ModuleId.EVIDENCE_SQLITE),
                ForbiddenEffect.GRADLE_PLATFORM to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.GRADLE_IMPORT to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.RECURSIVE_VFS_REFRESH to emptySet(),
                ForbiddenEffect.WORKSPACE_TRANSITION to setOf(ModuleId.WORKSPACE_SERVICE),
                ForbiddenEffect.GRAPH_BUILD to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.PROCESS_CONTROL to setOf(ModuleId.CLI),
                ForbiddenEffect.ANALYSIS_BACKEND to emptySet(),
            ),
            owners,
        )
    }

    @Test
    fun `runtime composition is the sole complete implementation graph owner`() {
        val architecture = canonicalArchitecture()
        val composition = architecture.modules.getValue(ModuleId.RUNTIME_COMPOSITION)
        val excluded = setOf(ModuleId.CLI, ModuleId.INDEXER, ModuleId.RUNTIME_COMPOSITION)

        assertEquals(ModuleRole.COMPOSITION, composition.role)
        assertEquals(architecture.modules.keys - excluded, composition.allowedProjectDependencies)
        assertEquals(
            setOf(ModuleId.RUNTIME_COMPOSITION),
            architecture.modules.values
                .filter { it.role == ModuleRole.COMPOSITION }
                .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
        )
    }

    @Test
    fun `injected outward edge and foreign effect are closed policy failures`() {
        val definition = KastArchitecturePolicy.definition()
        val traversal = definition.modules.single { it.id == ModuleId.TRAVERSAL_SERVICE }
        val cli = definition.modules.single { it.id == ModuleId.CLI }
        val injected = definition.copy(
            modules = definition.modules.map { module ->
                when (module.id) {
                    ModuleId.TRAVERSAL_SERVICE -> module.copy(
                        allowedProjectDependencies = module.allowedProjectDependencies +
                                                     ModuleId.DIAGNOSTIC_INTELLIJ,
                    )
                    ModuleId.CLI -> module.copy(
                        allowedEffects = cli.allowedEffects + ForbiddenEffect.JDBC,
                    )
                    else -> module
                }
            },
        )

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(injected),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                traversal.id,
                ModuleId.DIAGNOSTIC_INTELLIJ,
                ModuleRole.INTELLIJ_READ_ADAPTER,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(
                ModuleId.CLI,
                ForbiddenEffect.JDBC,
            ) in invalid.failures,
        )
    }

    private fun canonicalArchitecture(): ValidatedArchitecturePolicy =
        KastArchitecturePolicy.validate().let { validation ->
            assertInstanceOf<ArchitecturePolicyValidation.Valid>(validation, validation.toString()).architecture
        }
}
