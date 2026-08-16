package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class MutationTargetModuleBoundaryTest {
    @Test
    fun `materialized target admission preserves exact host-neutral dependency direction`() {
        val architecture = canonical()
        val changeContract = architecture.modules.getValue(ModuleId.CHANGE_CONTRACT)
        val changePlan = architecture.modules.getValue(ModuleId.CHANGE_PLAN)

        assertEquals(ModuleRole.CONTRACT, changeContract.role)
        assertEquals(
            setOf(
                ModuleId.KERNEL,
                ModuleId.WORKSPACE_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
            ),
            changeContract.allowedProjectDependencies,
        )
        assertTrue(changeContract.allowedEffects.isEmpty())

        assertEquals(ModuleLifecycle.ACTIVE, changePlan.lifecycle)
        assertEquals(ModuleRole.SERVICE, changePlan.role)
        assertEquals(setOf(ModuleId.CHANGE_CONTRACT), changePlan.allowedProjectDependencies)
        assertTrue(changePlan.allowedEffects.isEmpty())
    }

    @Test
    fun `target admission excludes all observation mutation and persistence effects`() {
        val architecture = canonical()
        val forbiddenEffects = setOf(
            ForbiddenEffect.INTELLIJ_PLATFORM,
            ForbiddenEffect.INTELLIJ_WRITE,
            ForbiddenEffect.FILESYSTEM_WRITE,
            ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
            ForbiddenEffect.JDBC,
            ForbiddenEffect.WORKSPACE_TRANSITION,
            ForbiddenEffect.GRADLE_PLATFORM,
            ForbiddenEffect.GRADLE_IMPORT,
            ForbiddenEffect.GRAPH_BUILD,
        )

        setOf(ModuleId.CHANGE_CONTRACT, ModuleId.CHANGE_PLAN).forEach { id ->
            assertTrue(
                architecture.modules.getValue(id).allowedEffects.intersect(forbiddenEffects).isEmpty(),
                id.projectPath,
            )
        }
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
}
