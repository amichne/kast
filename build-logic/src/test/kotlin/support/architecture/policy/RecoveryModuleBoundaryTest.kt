package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class RecoveryModuleBoundaryTest {
    @Test
    fun `materialized recovery service has only exact host-neutral dependencies`() {
        val architecture = canonical()
        val recovery = architecture.modules.getValue(ModuleId.CHANGE_RECOVERY)

        assertEquals(ModuleLifecycle.ACTIVE, recovery.lifecycle)
        assertEquals(ModuleRole.SERVICE, recovery.role)
        assertEquals(
            setOf(ModuleId.CHANGE_CONTRACT, ModuleId.EVIDENCE_CONTRACT),
            recovery.allowedProjectDependencies,
        )
        assertTrue(recovery.allowedEffects.isEmpty())
    }

    @Test
    fun `recovery service owns no persistence or mutation effect`() {
        val architecture = canonical()
        val recovery = architecture.modules.getValue(ModuleId.CHANGE_RECOVERY)
        val forbidden = setOf(
            ForbiddenEffect.JDBC,
            ForbiddenEffect.FILESYSTEM_WRITE,
            ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
            ForbiddenEffect.INTELLIJ_PLATFORM,
            ForbiddenEffect.INTELLIJ_WRITE,
            ForbiddenEffect.WORKSPACE_TRANSITION,
        )

        assertTrue(recovery.allowedEffects.intersect(forbidden).isEmpty())
        assertEquals(
            setOf(ForbiddenEffect.JDBC),
            architecture.modules.getValue(ModuleId.EVIDENCE_SQLITE).allowedEffects,
        )
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
}
