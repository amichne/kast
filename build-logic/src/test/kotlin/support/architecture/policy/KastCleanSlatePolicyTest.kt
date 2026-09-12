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
                ":query:contract",
                ":query:protocol",
                ":query:service",
                ":topology:contract",
                ":topology:build",
                ":topology:service",
                ":topology:intellij",
                ":diagnostic:contract",
                ":diagnostic:service",
                ":diagnostic:intellij",
                ":change:contract",
                ":change:plan",
                ":change:protocol",
                ":change:apply",
                ":change:verify",
                ":change:recovery",
                ":change:intellij",
                ":evidence:contract",
                ":evidence:sqlite",
                ":runtime:server",
                ":runtime:hosted",
                ":runtime:telemetry",
                ":runtime:composition",
                ":app-server",
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
    fun `query family retains only read contracts and composition owns construction`() {
        val architecture = canonicalArchitecture()
        val contract = architecture.modules.getValue(ModuleId.QUERY_CONTRACT)
        val service = architecture.modules.getValue(ModuleId.QUERY_SERVICE)
        val composition = architecture.modules.getValue(ModuleId.RUNTIME_HOSTED)

        assertEquals(ModuleRole.CONTRACT, contract.role)
        assertEquals(
            setOf(
                ModuleId.KERNEL,
                ModuleId.RELATION_CONTRACT,
                ModuleId.SOURCE_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
                ModuleId.WORKSPACE_CONTRACT,
            ),
            contract.allowedProjectDependencies,
        )
        assertTrue(contract.allowedEffects.isEmpty())

        assertEquals(ModuleRole.SERVICE, service.role)
        assertEquals(
            setOf(
                ModuleId.KERNEL,
                ModuleId.QUERY_CONTRACT,
                ModuleId.RELATION_CONTRACT,
                ModuleId.SOURCE_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
            ),
            service.allowedProjectDependencies,
        )
        assertTrue(service.allowedEffects.isEmpty())
        assertTrue(ModuleId.QUERY_CONTRACT in composition.allowedProjectDependencies)
        assertTrue(ModuleId.QUERY_SERVICE in composition.allowedProjectDependencies)
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
                    ModuleId.SYMBOL_INTELLIJ,
                    ModuleId.SOURCE_INTELLIJ,
                    ModuleId.RELATION_INTELLIJ,
                    ModuleId.DIAGNOSTIC_INTELLIJ,
                    ModuleId.CHANGE_INTELLIJ,
                    ModuleId.WORKSPACE_INTELLIJ_READ,
                    ModuleId.RUNTIME_HOSTED,
                ),
                ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY to
                    setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
                ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY to
                    setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
                ForbiddenEffect.UDS_BIND to setOf(ModuleId.RUNTIME_HOSTED),
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE to setOf(ModuleId.RUNTIME_HOSTED),
                ForbiddenEffect.PROJECT_OPEN to emptySet(),
                ForbiddenEffect.INTELLIJ_WRITE to setOf(ModuleId.CHANGE_INTELLIJ),
                ForbiddenEffect.FILESYSTEM_WRITE to setOf(
                    ModuleId.APP_SERVER,
                    ModuleId.DISTRIBUTION_MANAGED,
                    ModuleId.EVIDENCE_SQLITE,
                    ModuleId.CLI,
                    ModuleId.RUNTIME_HOSTED,
                ),
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE to emptySet(),
                ForbiddenEffect.JDBC to setOf(ModuleId.EVIDENCE_SQLITE),
                ForbiddenEffect.GRADLE_PLATFORM to emptySet(),
                ForbiddenEffect.GRADLE_IMPORT to emptySet(),
                ForbiddenEffect.RECURSIVE_VFS_REFRESH to emptySet(),
                ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION to
                    emptySet(),
                ForbiddenEffect.INDEXING_CYCLE to emptySet(),
                ForbiddenEffect.REPOSITORY_TRAVERSAL to emptySet(),
                ForbiddenEffect.PHYSICAL_SOURCE_READ to setOf(ModuleId.RUNTIME_HOSTED),
                ForbiddenEffect.SOURCE_CONTENT_HASH to setOf(ModuleId.RUNTIME_HOSTED),
                ForbiddenEffect.NETWORK_ACCESS to emptySet(),
                ForbiddenEffect.BLOCKING_WAIT to emptySet(),
                ForbiddenEffect.WORKSPACE_TRANSITION to emptySet(),
                ForbiddenEffect.GRAPH_BUILD to emptySet(),
                ForbiddenEffect.PROCESS_CONTROL to setOf(ModuleId.APP_SERVER, ModuleId.CLI),
                ForbiddenEffect.ANALYSIS_BACKEND to emptySet(),
                ForbiddenEffect.MUTATION_AUTHORITY to emptySet(),
                ForbiddenEffect.TOPOLOGY_AUTHORITY to emptySet(),
                ForbiddenEffect.ISOLATED_RUNTIME to emptySet(),
                ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY to emptySet(),
                ForbiddenEffect.TOPOLOGY_PUBLICATION to emptySet(),
            ),
            owners,
        )
    }

    @Test
    fun `retired bootstrap and composition retain no filesystem authority or implementation graph`() {
        val architecture = canonicalArchitecture()
        for (id in setOf(ModuleId.WORKSPACE_INTELLIJ, ModuleId.RUNTIME_COMPOSITION)) {
            val retired = architecture.modules.getValue(id)
            assertEquals(ModuleLifecycle.RETIRED, retired.lifecycle)
            assertTrue(retired.allowedEffects.isEmpty())
            assertTrue(retired.allowedScopedEffectCallers.isEmpty())
            assertTrue(retired.allowedProjectDependencies.isEmpty())
        }
        assertTrue(architecture.modules.values.none { it.lifecycle == ModuleLifecycle.ACTIVE && it.role == ModuleRole.COMPOSITION })
        assertEquals(ModuleRole.IDE_HOST, architecture.modules.getValue(ModuleId.RUNTIME_HOSTED).role)
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
