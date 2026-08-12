package support.architecture

import support.architecture.process.MutationRuntimeProcessId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class KastArchitecturePolicyTest {
    @Test
    fun `canonical policy is typed acyclic and migration aware`() {
        val validation = KastArchitecturePolicy.validate()

        val valid = assertInstanceOf<ArchitecturePolicyValidation.Valid>(validation)
        assertEquals(
            ModuleLifecycle.ACTIVE,
            valid.architecture.modules.getValue(ModuleId.ANALYSIS_API).lifecycle,
        )
        assertEquals(
            ModuleLifecycle.PLANNED,
            valid.architecture.modules.getValue(ModuleId.CHANGE_APPLY_INTELLIJ).lifecycle,
        )
        assertTrue(
            valid.architecture.moduleOrder.indexOf(ModuleId.CHANGE_APPLY_SPI) <
                valid.architecture.moduleOrder.indexOf(ModuleId.CHANGE_APPLY_INTELLIJ),
        )
        assertTrue(
            valid.architecture.mutationDeliveryOrder.indexOf(MutationDeliveryTaskId.F02) <
                valid.architecture.mutationDeliveryOrder.indexOf(MutationDeliveryTaskId.F03),
        )
        assertTrue(
            valid.architecture.mutationRuntimeProcessOrder.indexOf(MutationRuntimeProcessId.RP10) <
                valid.architecture.mutationRuntimeProcessOrder.indexOf(MutationRuntimeProcessId.RP11S),
        )
        assertEquals(74, valid.architecture.legacyAllowances.size)
    }

    @Test
    fun `active modules must exist and planned modules must be explicitly activated`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val observation = ObservedArchitecture(
            modules = setOf(
                ModuleId.ANALYSIS_API,
                ModuleId.ANALYSIS_SERVER,
                ModuleId.INDEX_STORE,
                ModuleId.CHANGE_CONTRACT,
            ),
            projectDependencies = emptySet(),
            effects = emptySet(),
        )

        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(architecture, observation),
        )

        assertTrue(rejected.violations.contains(ArchitectureViolation.ActiveModuleMissing(ModuleId.INDEXER)))
        assertTrue(
            rejected.violations.contains(
                ArchitectureViolation.PlannedModuleMaterialized(ModuleId.CHANGE_CONTRACT),
            ),
        )
    }

    @Test
    fun `unapproved direct project dependency is rejected`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val unexpected = ProjectDependencyObservation(ModuleId.ANALYSIS_API, ModuleId.INDEXER)

        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                activeObservation(projectDependencies = setOf(unexpected)),
            ),
        )

        assertEquals(
            setOf(
                ArchitectureViolation.UnbaselinedLegacyViolation(
                    LegacyViolationKey.UnapprovedProjectDependency(unexpected),
                ),
            ),
            rejected.violations,
        )
    }

    @Test
    fun `legacy allowance is exact and subtraction only`() {
        val observedEffect = EffectObservation(
            module = ModuleId.INDEXER,
            effect = ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
            caller = JvmMember.of("example/LegacyWriter", "apply", "()V"),
            target = JvmMember.of("java/nio/file/Files", "delete", "(Ljava/nio/file/Path;)V"),
        )
        val allowance = LegacyAllowance(
            violation = LegacyViolationKey.ForbiddenEffectUse(observedEffect),
            retirementTask = MutationDeliveryTaskId.A06,
        )
        val definition = KastArchitecturePolicy.definition().copy(legacyAllowances = listOf(allowance))
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(definition),
        ).architecture

        assertInstanceOf<ArchitectureAdmission.Accepted>(
            ArchitectureAdmission.evaluate(
                architecture,
                activeObservation(effects = setOf(observedEffect)),
            ),
        )
        val obsolete = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(architecture, activeObservation()),
        )
        assertEquals(setOf(ArchitectureViolation.ObsoleteLegacyAllowance(allowance)), obsolete.violations)

        val wildcard = observedEffect.copy(
            target = JvmMember.of("java/nio/file/*", "delete", "(Ljava/nio/file/Path;)V"),
        )
        val wildcardDefinition = definition.copy(
            legacyAllowances = listOf(allowance.copy(violation = LegacyViolationKey.ForbiddenEffectUse(wildcard))),
        )
        val wildcardRejected = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(wildcardDefinition),
        )
        assertTrue(
            wildcardRejected.failures.contains(
                ArchitecturePolicyFailure.NonExactLegacyAllowance(wildcardDefinition.legacyAllowances.single()),
            ),
        )
    }

    @Test
    fun `missing graph references are closed policy failures`() {
        val definition = KastArchitecturePolicy.definition().copy(
            modules = KastArchitecturePolicy.definition().modules
                .filterNot { it.id == ModuleId.KERNEL },
        )

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )

        assertTrue(
            invalid.failures.any {
                it == ArchitecturePolicyFailure.MissingModuleDependency(
                    ModuleId.PROTOCOL_REGISTRY,
                    ModuleId.KERNEL,
                )
            },
        )
    }

    private fun canonicalWithoutLegacyAllowances(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(
                KastArchitecturePolicy.definition().copy(legacyAllowances = emptyList()),
            ),
        ).architecture

    private fun activeObservation(
        projectDependencies: Set<ProjectDependencyObservation> = emptySet(),
        effects: Set<EffectObservation> = emptySet(),
    ): ObservedArchitecture = ObservedArchitecture(
        modules = setOf(
            ModuleId.ANALYSIS_API,
            ModuleId.ANALYSIS_SERVER,
            ModuleId.INDEX_STORE,
            ModuleId.INDEXER,
        ),
        projectDependencies = projectDependencies,
        effects = effects,
    )
}
