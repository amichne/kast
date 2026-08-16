package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class RelationModuleBoundaryTest {
    @Test
    fun `materialized relation family preserves exact one hop dependency direction`() {
        val architecture = canonical()
        val expected = mapOf(
            ModuleId.RELATION_CONTRACT to ExpectedRelationBoundary(
                role = ModuleRole.CONTRACT,
                dependencies = setOf(
                    ModuleId.KERNEL,
                    ModuleId.SYMBOL_CONTRACT,
                    ModuleId.WORKSPACE_CONTRACT,
                ),
                effects = emptySet(),
            ),
            ModuleId.RELATION_SERVICE to ExpectedRelationBoundary(
                role = ModuleRole.SERVICE,
                dependencies = setOf(
                    ModuleId.RELATION_CONTRACT,
                    ModuleId.SYMBOL_CONTRACT,
                    ModuleId.WORKSPACE_CONTRACT,
                ),
                effects = emptySet(),
            ),
            ModuleId.RELATION_INTELLIJ to ExpectedRelationBoundary(
                role = ModuleRole.INTELLIJ_READ_ADAPTER,
                dependencies = setOf(
                    ModuleId.RELATION_CONTRACT,
                    ModuleId.SYMBOL_CONTRACT,
                    ModuleId.WORKSPACE_CONTRACT,
                ),
                effects = setOf(ForbiddenEffect.INTELLIJ_PLATFORM),
            ),
        )

        expected.forEach { (id, boundary) ->
            val module = architecture.modules.getValue(id)
            assertEquals(ModuleLifecycle.ACTIVE, module.lifecycle, id.projectPath)
            assertEquals(boundary.role, module.role, id.projectPath)
            assertEquals(boundary.dependencies, module.allowedProjectDependencies, id.projectPath)
            assertEquals(boundary.effects, module.allowedEffects, id.projectPath)
        }
    }

    @Test
    fun `relation family excludes traversal mutation persistence and hidden effects`() {
        val architecture = canonical()
        val forbiddenDependencies = setOf(
            ModuleId.TRAVERSAL_CONTRACT,
            ModuleId.TRAVERSAL_SERVICE,
            ModuleId.WORKSPACE_MUTATION_CONTRACT,
            ModuleId.WORKSPACE_MUTATION_SERVICE,
            ModuleId.EVIDENCE_SQLITE,
        )
        val forbiddenEffects = setOf(
            ForbiddenEffect.JDBC,
            ForbiddenEffect.WORKSPACE_TRANSITION,
            ForbiddenEffect.GRADLE_IMPORT,
            ForbiddenEffect.GRAPH_BUILD,
            ForbiddenEffect.FILESYSTEM_WRITE,
            ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
            ForbiddenEffect.INTELLIJ_WRITE,
        )

        setOf(
            ModuleId.RELATION_CONTRACT,
            ModuleId.RELATION_SERVICE,
            ModuleId.RELATION_INTELLIJ,
        ).forEach { id ->
            val module = architecture.modules.getValue(id)
            assertTrue(
                module.allowedProjectDependencies.intersect(forbiddenDependencies).isEmpty(),
                id.projectPath,
            )
            assertTrue(module.allowedEffects.intersect(forbiddenEffects).isEmpty(), id.projectPath)
        }
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

    private data class ExpectedRelationBoundary(
        val role: ModuleRole,
        val dependencies: Set<ModuleId>,
        val effects: Set<ForbiddenEffect>,
    )
}
