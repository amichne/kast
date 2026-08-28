package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class DiagnosticModuleBoundaryTest {
    @Test
    fun `materialized diagnostic family preserves exact read-only dependency direction`() {
        val architecture = canonical()
        val expected = mapOf(
            ModuleId.DIAGNOSTIC_CONTRACT to ExpectedDiagnosticBoundary(
                role = ModuleRole.CONTRACT,
                dependencies = setOf(ModuleId.KERNEL, ModuleId.WORKSPACE_CONTRACT),
                effects = emptySet(),
            ),
            ModuleId.DIAGNOSTIC_SERVICE to ExpectedDiagnosticBoundary(
                role = ModuleRole.SERVICE,
                dependencies = setOf(
                    ModuleId.DIAGNOSTIC_CONTRACT,
                    ModuleId.WORKSPACE_CONTRACT,
                ),
                effects = emptySet(),
            ),
            ModuleId.DIAGNOSTIC_INTELLIJ to ExpectedDiagnosticBoundary(
                role = ModuleRole.INTELLIJ_READ_ADAPTER,
                dependencies = setOf(
                    ModuleId.PROTOCOL_CONTRACT,
                    ModuleId.DIAGNOSTIC_CONTRACT,
                    ModuleId.WORKSPACE_CONTRACT,
                    ModuleId.WORKSPACE_INTELLIJ_READ,
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
    fun `diagnostic family excludes transition mutation persistence and write authority`() {
        val architecture = canonical()
        val forbiddenDependencies = setOf(
            ModuleId.WORKSPACE_SERVICE,
            ModuleId.WORKSPACE_INTELLIJ,
            ModuleId.EVIDENCE_SQLITE,
            ModuleId.TRAVERSAL_SERVICE,
            ModuleId.CHANGE_PLAN,
            ModuleId.CHANGE_APPLY,
            ModuleId.CHANGE_INTELLIJ,
            ModuleId.CHANGE_VERIFY,
        )

        setOf(
            ModuleId.DIAGNOSTIC_CONTRACT,
            ModuleId.DIAGNOSTIC_SERVICE,
            ModuleId.DIAGNOSTIC_INTELLIJ,
        ).forEach { id ->
            val module = architecture.modules.getValue(id)
            assertTrue(
                module.allowedProjectDependencies.intersect(forbiddenDependencies).isEmpty(),
                id.projectPath,
            )
            assertTrue(
                module.allowedEffects.none { effect ->
                    effect in setOf(
                        ForbiddenEffect.GRADLE_PLATFORM,
                        ForbiddenEffect.GRADLE_IMPORT,
                        ForbiddenEffect.JDBC,
                        ForbiddenEffect.FILESYSTEM_WRITE,
                        ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
                        ForbiddenEffect.INTELLIJ_WRITE,
                        ForbiddenEffect.WORKSPACE_TRANSITION,
                    )
                },
                id.projectPath,
            )
        }
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

    private data class ExpectedDiagnosticBoundary(
        val role: ModuleRole,
        val dependencies: Set<ModuleId>,
        val effects: Set<ForbiddenEffect>,
    )
}
