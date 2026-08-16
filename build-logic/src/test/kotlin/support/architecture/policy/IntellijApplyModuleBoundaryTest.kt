package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class IntellijApplyModuleBoundaryTest {
    @Test
    fun `materialized IntelliJ apply slice has only exact dependencies and effects`() {
        val architecture = canonical()
        val apply = architecture.modules.getValue(ModuleId.CHANGE_APPLY)
        val intellij = architecture.modules.getValue(ModuleId.CHANGE_INTELLIJ)

        assertEquals(ModuleLifecycle.ACTIVE, apply.lifecycle)
        assertEquals(ModuleRole.SERVICE, apply.role)
        assertEquals(
            setOf(
                ModuleId.CHANGE_CONTRACT,
                ModuleId.CHANGE_RECOVERY,
                ModuleId.EVIDENCE_CONTRACT,
            ),
            apply.allowedProjectDependencies,
        )
        assertTrue(apply.allowedEffects.isEmpty())

        assertEquals(ModuleLifecycle.ACTIVE, intellij.lifecycle)
        assertEquals(ModuleRole.INTELLIJ_WRITE_ADAPTER, intellij.role)
        assertEquals(
            setOf(
                ModuleId.CHANGE_APPLY,
                ModuleId.CHANGE_CONTRACT,
                ModuleId.CHANGE_RECOVERY,
                ModuleId.EVIDENCE_CONTRACT,
            ),
            intellij.allowedProjectDependencies,
        )
        assertEquals(
            setOf(ForbiddenEffect.INTELLIJ_PLATFORM, ForbiddenEffect.INTELLIJ_WRITE),
            intellij.allowedEffects,
        )
    }

    @Test
    fun `terminal IntelliJ apply policy preserves the same restricted authority`() {
        val target = canonical().targetModules

        assertEquals(
            setOf(ModuleId.CHANGE_CONTRACT, ModuleId.CHANGE_RECOVERY, ModuleId.EVIDENCE_CONTRACT),
            target.getValue(ModuleId.CHANGE_APPLY).allowedProjectDependencies,
        )
        assertEquals(
            setOf(
                ModuleId.CHANGE_APPLY,
                ModuleId.CHANGE_CONTRACT,
                ModuleId.CHANGE_RECOVERY,
                ModuleId.EVIDENCE_CONTRACT,
            ),
            target.getValue(ModuleId.CHANGE_INTELLIJ).allowedProjectDependencies,
        )
        assertEquals(
            setOf(ForbiddenEffect.INTELLIJ_PLATFORM, ForbiddenEffect.INTELLIJ_WRITE),
            target.getValue(ModuleId.CHANGE_INTELLIJ).allowedEffects,
        )
    }

    @Test
    fun `foreign service dependency remains a closed role failure`() {
        val definition = KastArchitecturePolicy.definition()
        val injected = definition.copy(
            modules = definition.modules.map { module ->
                if (module.id == ModuleId.CHANGE_INTELLIJ) {
                    module.copy(
                        allowedProjectDependencies = module.allowedProjectDependencies +
                            ModuleId.WORKSPACE_SERVICE,
                    )
                } else {
                    module
                }
            },
        )

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(injected),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.CHANGE_INTELLIJ,
                ModuleId.WORKSPACE_SERVICE,
                ModuleRole.SERVICE,
            ) in invalid.failures,
        )
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
}
