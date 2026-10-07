package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class RetainedTopologyBoundaryTest {
    @Test
    fun `host ships exact semantic fact owners while publication remains outside shipped graphs`() {
        val modules = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture.modules
        val topology = setOf(
            ModuleId.TOPOLOGY_CONTRACT,
            ModuleId.TOPOLOGY_BUILD,
            ModuleId.TOPOLOGY_SERVICE,
            ModuleId.TOPOLOGY_INTELLIJ,
        )
        topology.forEach { id ->
            assertEquals(ModuleLifecycle.ACTIVE, modules.getValue(id).lifecycle, id.projectPath)
        }
        val persistence = modules.getValue(ModuleId.EVIDENCE_TOPOLOGY_SQLITE)
        assertEquals(ModuleLifecycle.ACTIVE, persistence.lifecycle)
        assertEquals(ModuleRole.SQLITE_ADAPTER, persistence.role)
        assertTrue(ForbiddenEffect.TOPOLOGY_PUBLICATION in persistence.allowedEffects)

        fun dependencies(id: ModuleId): Set<ModuleId> =
            modules.getValue(id).allowedProjectDependencies.flatMapTo(mutableSetOf()) { dependency ->
                dependencies(dependency) + dependency
            }

        val semanticFacts = setOf(
            ModuleId.TOPOLOGY_CONTRACT,
            ModuleId.TOPOLOGY_BUILD,
            ModuleId.TOPOLOGY_INTELLIJ,
        )
        val expected = mapOf(
            ModuleId.RUNTIME_HOSTED to semanticFacts,
            ModuleId.CLI to emptySet(),
            ModuleId.APP_SERVER to emptySet(),
        )
        expected.forEach { (runtime, shipped) ->
            assertEquals(
                shipped,
                dependencies(runtime).intersect(topology + persistence.id),
                "${runtime.projectPath} must ship only its exact semantic fact owners",
            )
        }
    }
}
