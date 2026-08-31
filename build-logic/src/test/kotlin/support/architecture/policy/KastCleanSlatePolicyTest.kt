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
                ":workspace:intellij-read",
                ":symbol:contract",
                ":symbol:service",
                ":symbol:intellij",
                ":relation:contract",
                ":relation:service",
                ":relation:intellij",
                ":traversal:contract",
                ":traversal:service",
                ":topology:contract",
                ":topology:build",
                ":topology:service",
                ":topology:intellij",
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
                ":runtime:ide-read",
                ":runtime:ide-host",
                ":ide-plugin",
                ":cli",
                ":indexer",
            ),
            architecture.modules.values.mapTo(linkedSetOf()) { it.id.projectPath },
        )
        assertEquals(
            ModuleLifecycle.ACTIVE,
            architecture.modules.getValue(ModuleId.IDE_PLUGIN).lifecycle,
        )
        assertEquals(
            ModuleLifecycle.ACTIVE,
            architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ).lifecycle,
        )
        assertEquals(
            ModuleLifecycle.ACTIVE,
            architecture.modules.getValue(ModuleId.RUNTIME_IDE_READ).lifecycle,
        )
        assertTrue(
            ModuleId.SYMBOL_INTELLIJ in architecture.modules
                .getValue(ModuleId.RUNTIME_IDE_READ)
                .allowedProjectDependencies,
        )
        assertTrue(
            ModuleId.SYMBOL_CONTRACT in architecture.modules
                .getValue(ModuleId.PROTOCOL_CONTRACT)
                .allowedProjectDependencies,
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
                    ModuleId.TOPOLOGY_INTELLIJ,
                    ModuleId.DIAGNOSTIC_INTELLIJ,
                    ModuleId.CHANGE_INTELLIJ,
                    ModuleId.INDEXER,
                    ModuleId.WORKSPACE_INTELLIJ_READ,
                    ModuleId.RUNTIME_IDE_READ,
                    ModuleId.IDE_PLUGIN,
                ),
                ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY to
                    setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
                ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY to setOf(ModuleId.IDE_PLUGIN),
                ForbiddenEffect.UDS_BIND to setOf(ModuleId.IDE_PLUGIN),
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE to setOf(ModuleId.IDE_PLUGIN),
                ForbiddenEffect.PROJECT_OPEN to emptySet(),
                ForbiddenEffect.INTELLIJ_WRITE to setOf(ModuleId.CHANGE_INTELLIJ),
                ForbiddenEffect.FILESYSTEM_WRITE to setOf(
                    ModuleId.DISTRIBUTION_MANAGED,
                    ModuleId.EVIDENCE_SQLITE,
                    ModuleId.INDEXER,
                ),
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE to emptySet(),
                ForbiddenEffect.JDBC to setOf(ModuleId.EVIDENCE_SQLITE),
                ForbiddenEffect.GRADLE_PLATFORM to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.GRADLE_IMPORT to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.RECURSIVE_VFS_REFRESH to emptySet(),
                ForbiddenEffect.INDEXING_CYCLE to emptySet(),
                ForbiddenEffect.REPOSITORY_TRAVERSAL to emptySet(),
                ForbiddenEffect.PHYSICAL_SOURCE_READ to emptySet(),
                ForbiddenEffect.SOURCE_CONTENT_HASH to emptySet(),
                ForbiddenEffect.NETWORK_ACCESS to emptySet(),
                ForbiddenEffect.BLOCKING_WAIT to emptySet(),
                ForbiddenEffect.WORKSPACE_TRANSITION to setOf(ModuleId.WORKSPACE_SERVICE),
                ForbiddenEffect.GRAPH_BUILD to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.PROCESS_CONTROL to setOf(ModuleId.CLI),
                ForbiddenEffect.ANALYSIS_BACKEND to emptySet(),
                ForbiddenEffect.MUTATION_AUTHORITY to emptySet(),
                ForbiddenEffect.TOPOLOGY_AUTHORITY to emptySet(),
                ForbiddenEffect.ISOLATED_RUNTIME to emptySet(),
                ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY to setOf(ModuleId.TOPOLOGY_BUILD),
                ForbiddenEffect.TOPOLOGY_PUBLICATION to setOf(ModuleId.EVIDENCE_SQLITE),
            ),
            owners,
        )
    }

    @Test
    fun `full and hosted compositions retain distinct exact implementation graphs`() {
        val architecture = canonicalArchitecture()
        val composition = architecture.modules.getValue(ModuleId.RUNTIME_COMPOSITION)
        val excluded = setOf(
            ModuleId.CLI,
            ModuleId.INDEXER,
            ModuleId.RUNTIME_COMPOSITION,
            ModuleId.RUNTIME_IDE_HOST,
        )

        assertEquals(ModuleRole.COMPOSITION, composition.role)
        assertEquals(architecture.modules.keys - excluded, composition.allowedProjectDependencies)
        assertEquals(
            setOf(ModuleId.RUNTIME_COMPOSITION, ModuleId.RUNTIME_IDE_HOST),
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
