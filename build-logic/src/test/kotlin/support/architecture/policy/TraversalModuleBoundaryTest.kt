package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class TraversalModuleBoundaryTest {
    @Test
    fun `materialized traversal family preserves pure inward dependency direction`() {
        val architecture = canonical()
        val expected = mapOf(
            ModuleId.TRAVERSAL_CONTRACT to ExpectedTraversalBoundary(
                role = ModuleRole.CONTRACT,
                dependencies = setOf(
                    ModuleId.KERNEL,
                    ModuleId.RELATION_CONTRACT,
                    ModuleId.SYMBOL_CONTRACT,
                    ModuleId.WORKSPACE_CONTRACT,
                ),
            ),
            ModuleId.TRAVERSAL_SERVICE to ExpectedTraversalBoundary(
                role = ModuleRole.SERVICE,
                dependencies = setOf(
                    ModuleId.RELATION_CONTRACT,
                    ModuleId.TRAVERSAL_CONTRACT,
                ),
            ),
        )

        expected.forEach { (id, boundary) ->
            val module = architecture.modules.getValue(id)
            assertEquals(ModuleLifecycle.ACTIVE, module.lifecycle, id.projectPath)
            assertEquals(boundary.role, module.role, id.projectPath)
            assertEquals(boundary.dependencies, module.allowedProjectDependencies, id.projectPath)
            assertEquals(emptySet<ForbiddenEffect>(), module.allowedEffects, id.projectPath)
        }
    }

    @Test
    fun `traversal family excludes platform persistence mutation and write authority`() {
        val architecture = canonical()
        val forbiddenDependencies = setOf(
            ModuleId.RELATION_INTELLIJ,
            ModuleId.SYMBOL_INTELLIJ,
            ModuleId.WORKSPACE_INTELLIJ,
            ModuleId.WORKSPACE_SERVICE,
            ModuleId.EVIDENCE_SQLITE,
            ModuleId.CHANGE_APPLY_INTELLIJ,
            ModuleId.CHANGE_APPLY_SERVICE,
        )

        setOf(ModuleId.TRAVERSAL_CONTRACT, ModuleId.TRAVERSAL_SERVICE).forEach { id ->
            val module = architecture.modules.getValue(id)
            assertTrue(
                module.allowedProjectDependencies.intersect(forbiddenDependencies).isEmpty(),
                id.projectPath,
            )
            assertEquals(emptySet<ForbiddenEffect>(), module.allowedEffects, id.projectPath)
        }
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

    private data class ExpectedTraversalBoundary(
        val role: ModuleRole,
        val dependencies: Set<ModuleId>,
    )
}
