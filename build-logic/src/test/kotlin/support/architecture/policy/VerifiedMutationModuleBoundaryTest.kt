package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class VerifiedMutationModuleBoundaryTest {
    private val exactDependencies = setOf(
        ModuleId.CHANGE_APPLY,
        ModuleId.CHANGE_CONTRACT,
        ModuleId.DIAGNOSTIC_CONTRACT,
        ModuleId.RELATION_CONTRACT,
        ModuleId.WORKSPACE_CONTRACT,
    )

    @Test
    fun `materialized verifier is an exact host neutral service`() {
        val verifier = canonical().modules.getValue(ModuleId.CHANGE_VERIFY)

        assertEquals(ModuleLifecycle.ACTIVE, verifier.lifecycle)
        assertEquals(ModuleRole.SERVICE, verifier.role)
        assertEquals(exactDependencies, verifier.allowedProjectDependencies)
        assertTrue(verifier.allowedEffects.isEmpty())
    }

    @Test
    fun `terminal verifier policy preserves the materialized boundary`() {
        val verifier = canonical().modules.getValue(ModuleId.CHANGE_VERIFY)

        assertEquals(ModuleRole.SERVICE, verifier.role)
        assertEquals(exactDependencies, verifier.allowedProjectDependencies)
        assertTrue(verifier.allowedEffects.isEmpty())
    }

    @Test
    fun `foreign service dependency remains a closed role failure`() {
        val injected = definitionWith(ModuleId.CHANGE_VERIFY) { module ->
            module.copy(
                allowedProjectDependencies = module.allowedProjectDependencies +
                                             ModuleId.WORKSPACE_SERVICE,
            )
        }

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(injected),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.CHANGE_VERIFY,
                ModuleId.WORKSPACE_SERVICE,
                ModuleRole.SERVICE,
            ) in invalid.failures,
        )
    }

    @Test
    fun `platform effect remains a closed service failure`() {
        val injected = definitionWith(ModuleId.CHANGE_VERIFY) { module ->
            module.copy(allowedEffects = setOf(ForbiddenEffect.INTELLIJ_PLATFORM))
        }

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(injected),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(
                ModuleId.CHANGE_VERIFY,
                ForbiddenEffect.INTELLIJ_PLATFORM,
            ) in invalid.failures,
        )
    }

    private fun definitionWith(
        id: ModuleId,
        transform: (ModulePolicy) -> ModulePolicy,
    ): ArchitecturePolicyDefinition {
        val definition = KastArchitecturePolicy.definition()
        return definition.copy(
            modules = definition.modules.map { module ->
                if (module.id == id) transform(module) else module
            },
        )
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
}
