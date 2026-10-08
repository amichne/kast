package support.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class DistributionStateInspectionBoundaryTest {
    @Test
    fun `distribution reuses the mutation state owner without granting the general CLI SQLite authority`() {
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate())
        val definition = KastArchitecturePolicy.definition()
        val expanded = definition.copy(
            modules = definition.modules.map { module ->
                if (module.id == ModuleId.CLI) {
                    module.copy(allowedProjectDependencies = module.allowedProjectDependencies + ModuleId.EVIDENCE_SQLITE)
                } else {
                    module
                }
            },
        )
        val rejected = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(expanded),
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.CLI,
                ModuleId.EVIDENCE_SQLITE,
                ModuleRole.SQLITE_ADAPTER,
            ) in rejected.failures,
        )
    }
}
