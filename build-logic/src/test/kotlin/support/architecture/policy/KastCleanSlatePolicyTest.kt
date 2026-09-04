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
                ":source:contract",
                ":source:service",
                ":source:intellij",
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
                ":runtime:telemetry",
                ":runtime:composition",
                ":cli",
                ":indexer",
            ),
            architecture.modules.values.mapTo(linkedSetOf()) { it.id.projectPath },
        )
        assertEquals(
            ModuleLifecycle.ACTIVE,
            architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ).lifecycle,
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
                .filter { module ->
                    effect in module.allowedEffects || effect in module.allowedScopedEffectCallers
                }
                .mapTo(linkedSetOf(), ValidatedModulePolicy::id)
        }

        assertEquals(
            mapOf(
                ForbiddenEffect.INTELLIJ_PLATFORM to setOf(
                    ModuleId.WORKSPACE_INTELLIJ,
                    ModuleId.SYMBOL_INTELLIJ,
                    ModuleId.SOURCE_INTELLIJ,
                    ModuleId.RELATION_INTELLIJ,
                    ModuleId.TOPOLOGY_INTELLIJ,
                    ModuleId.DIAGNOSTIC_INTELLIJ,
                    ModuleId.CHANGE_INTELLIJ,
                    ModuleId.INDEXER,
                    ModuleId.WORKSPACE_INTELLIJ_READ,
                ),
                ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY to
                    setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
                ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY to
                    setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
                ForbiddenEffect.UDS_BIND to setOf(ModuleId.INDEXER),
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE to setOf(ModuleId.INDEXER),
                ForbiddenEffect.PROJECT_OPEN to emptySet(),
                ForbiddenEffect.INTELLIJ_WRITE to setOf(ModuleId.CHANGE_INTELLIJ),
                ForbiddenEffect.FILESYSTEM_WRITE to setOf(
                    ModuleId.DISTRIBUTION_MANAGED,
                    ModuleId.EVIDENCE_SQLITE,
                    ModuleId.CLI,
                    ModuleId.RUNTIME_TELEMETRY,
                    ModuleId.INDEXER,
                    ModuleId.WORKSPACE_INTELLIJ,
                ),
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE to emptySet(),
                ForbiddenEffect.JDBC to setOf(ModuleId.EVIDENCE_SQLITE),
                ForbiddenEffect.GRADLE_PLATFORM to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.GRADLE_IMPORT to setOf(ModuleId.WORKSPACE_INTELLIJ),
                ForbiddenEffect.RECURSIVE_VFS_REFRESH to emptySet(),
                ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION to
                    setOf(ModuleId.TOPOLOGY_INTELLIJ),
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
    fun `workspace bootstrap receives only its exact filesystem write scope`() {
        val workspace = canonicalArchitecture().modules.getValue(ModuleId.WORKSPACE_INTELLIJ)

        assertEquals(
            setOf(ForbiddenEffect.FILESYSTEM_WRITE),
            workspace.allowedScopedEffectCallers.keys,
        )
        assertEquals(
            setOf(
                JvmClassName(
                    "io/github/amichne/kast/workspace/intellij/InstalledIndexBootstrap\$Companion",
                ),
                JvmClassName(
                    "io/github/amichne/kast/workspace/intellij/InstalledIndexBootstrapKt",
                ),
            ),
            workspace.allowedScopedEffectCallers.getValue(ForbiddenEffect.FILESYSTEM_WRITE),
        )
        assertTrue(ForbiddenEffect.FILESYSTEM_WRITE !in workspace.allowedEffects)
    }

    @Test
    fun `one canonical composition retains the complete implementation graph`() {
        val architecture = canonicalArchitecture()
        val composition = architecture.modules.getValue(ModuleId.RUNTIME_COMPOSITION)
        val excluded = setOf(
            ModuleId.CLI,
            ModuleId.INDEXER,
            ModuleId.RUNTIME_COMPOSITION,
        )

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
