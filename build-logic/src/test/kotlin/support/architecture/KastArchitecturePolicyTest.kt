package support.architecture

import support.architecture.process.MutationRuntimeProcessId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import kotlin.collections.filter

class KastArchitecturePolicyTest {
    @Test
    fun `indexer composes exactly the durable planning owners introduced by KIP 032`() {
        val dependencies = canonicalWithoutLegacyAllowances()
            .modules
            .getValue(ModuleId.INDEXER)
            .allowedProjectDependencies
        val durablePlanningOwners = setOf(
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.CHANGE_JOURNAL_SQLITE,
            ModuleId.CHANGE_PLAN_SERVICE,
        )

        assertEquals(durablePlanningOwners, dependencies.intersect(durablePlanningOwners))
    }

    @Test
    fun `durable approval activates only service contract and sqlite evidence owners`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val expectedOwners = setOf(
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.CHANGE_JOURNAL_SQLITE,
            ModuleId.CHANGE_PLAN_SERVICE,
        )

        assertTrue(
            expectedOwners.all { owner ->
                architecture.modules.getValue(owner).lifecycle == ModuleLifecycle.ACTIVE
            },
        )
        assertEquals(
            setOf(ForbiddenEffect.JDBC),
            architecture.modules.getValue(ModuleId.CHANGE_JOURNAL_SQLITE).allowedEffects,
        )
        assertTrue(
            setOf(ModuleId.CHANGE_JOURNAL_CONTRACT, ModuleId.CHANGE_PLAN_SERVICE).all { owner ->
                architecture.modules.getValue(owner).allowedEffects.isEmpty()
            },
        )
        assertTrue(
            expectedOwners.all { owner ->
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE !in
                    architecture.modules.getValue(owner).allowedEffects
            },
        )
        val durablePlanWrite = architecture.mutationRuntimeProcesses.getValue(
            MutationRuntimeProcessId.RP05,
        )
        assertEquals(
            setOf(ModuleId.CHANGE_PLAN_SERVICE, ModuleId.CHANGE_JOURNAL_CONTRACT),
            durablePlanWrite.owners,
        )
    }

    @Test
    fun `plan-only add declaration activates narrow owners without source-write authority`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val expectedOwners = setOf(
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_PLAN_SPI,
            ModuleId.CHANGE_PLAN_INTELLIJ,
        )

        assertTrue(
            expectedOwners.all { owner ->
                architecture.modules.getValue(owner).lifecycle == ModuleLifecycle.ACTIVE
            },
        )
        assertTrue(
            expectedOwners.all { owner ->
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE !in
                    architecture.modules.getValue(owner).allowedEffects
            },
        )
        assertTrue(
            expectedOwners.all { owner ->
                owner in architecture.modules.getValue(ModuleId.INDEXER).allowedProjectDependencies
            },
        )
    }

    @Test
    fun `workspace transition extraction activates narrow owners and retires legacy Gradle authority`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val expectedOwners = setOf(
            ModuleId.EVIDENCE_CONTRACT,
            ModuleId.EVIDENCE_SPI,
            ModuleId.WORKSPACE_SERVICE,
            ModuleId.WORKSPACE_INTELLIJ,
        )

        assertTrue(
            expectedOwners.all { owner ->
                architecture.modules.getValue(owner).lifecycle == ModuleLifecycle.ACTIVE
            },
        )
        assertTrue(
            KastArchitecturePolicy.definition().legacyAllowances.none { allowance ->
                val effect = allowance.violation as? LegacyViolationKey.ForbiddenEffectUse
                effect?.observation?.module == ModuleId.INDEXER &&
                effect.observation.effect == ForbiddenEffect.GRADLE_IMPORT
            },
        )
        assertEquals(
            setOf(ModuleId.WORKSPACE_INTELLIJ),
            architecture.modules.values
                .filter { module -> ForbiddenEffect.GRADLE_IMPORT in module.allowedEffects }
                .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
        )
    }

    @Test
    fun `native read slice keeps its final inward dependencies while modules activate`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val modulesByPath = architecture.modules.values.associateBy { it.id.projectPath }
        val expected = mapOf(
            ":workspace:spi" to (ModuleRole.SPI to setOf(":workspace:contract")),
            ":evidence:contract" to (
                ModuleRole.CONTRACT to setOf(":kernel", ":workspace:contract")
                                    ),
            ":evidence:spi" to (
                ModuleRole.SPI to setOf(":evidence:contract", ":workspace:contract")
                               ),
            ":evidence:sqlite" to (
                ModuleRole.SQLITE_ADAPTER to
                    setOf(":evidence:contract", ":evidence:spi", ":workspace:contract")
                                  ),
            ":workspace:service" to (
                ModuleRole.SERVICE to
                    setOf(":evidence:spi", ":workspace:contract", ":workspace:spi")
                                    ),
            ":workspace:intellij" to (
                ModuleRole.WORKSPACE_ADAPTER to setOf(":workspace:contract", ":workspace:spi")
                                     ),
            ":symbol:contract" to (
                ModuleRole.CONTRACT to setOf(":kernel", ":workspace:contract")
                                  ),
            ":symbol:intellij" to (
                ModuleRole.INTELLIJ_READ_ADAPTER to
                    setOf(":symbol:contract", ":workspace:contract", ":workspace:spi")
                                  ),
            ":protocol:continuation" to (
                ModuleRole.SERVICE to setOf(":kernel", ":workspace:contract")
                                        ),
            ":runtime:bindings" to (
                ModuleRole.CONTRACT to
                    setOf(":change:contract", ":kernel", ":symbol:contract", ":workspace:contract")
                                   ),
        )

        assertEquals(expected.keys, expected.keys.intersect(modulesByPath.keys))
        assertTrue(":runtime:bindings:contract" !in modulesByPath)
        expected.forEach { (path, expectedPolicy) ->
            val module = modulesByPath.getValue(path)
            assertTrue(
                module.lifecycle in setOf(ModuleLifecycle.PLANNED, ModuleLifecycle.ACTIVE),
                path,
            )
            assertEquals(expectedPolicy.first, module.role, path)
            assertEquals(
                expectedPolicy.second,
                module.allowedProjectDependencies.mapTo(mutableSetOf()) { it.projectPath },
                path,
            )
        }
    }

    @Test
    fun `canonical services and physical adapters depend only on inward boundaries`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val inwardRoles = setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT, ModuleRole.SPI)
        val consumers = setOf(
            ModuleRole.SERVICE,
            ModuleRole.INTELLIJ_READ_ADAPTER,
            ModuleRole.INTELLIJ_WRITE_ADAPTER,
            ModuleRole.FILESYSTEM_WRITE_ADAPTER,
            ModuleRole.SQLITE_ADAPTER,
            ModuleRole.WORKSPACE_ADAPTER,
        )
        val outwardDependencies = architecture.modules.values
            .filter { it.role in consumers }
            .flatMap { module ->
                module.allowedProjectDependencies
                    .filter { architecture.modules.getValue(it).role !in inwardRoles }
                    .map { ProjectDependencyObservation(module.id, it) }
            }
            .toSet()
        val featureContractsDependingOnRegistry = architecture.modules.values
            .filter { module ->
                module.role == ModuleRole.CONTRACT &&
                module.id != ModuleId.PROTOCOL_REGISTRY &&
                ModuleId.PROTOCOL_REGISTRY in module.allowedProjectDependencies
            }
            .mapTo(mutableSetOf(), ValidatedModulePolicy::id)

        assertEquals(emptySet<ProjectDependencyObservation>(), outwardDependencies)
        assertEquals(emptySet<ModuleId>(), featureContractsDependingOnRegistry)
    }

    @Test
    fun `runtime composition is the sole complete implementation owner`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val compositions = architecture.modules.values.filter { it.role == ModuleRole.COMPOSITION }
        val composition = compositions.single()
        val legacyHosts = architecture.modules.values
            .filter { it.role == ModuleRole.LEGACY_HOST }
            .mapTo(mutableSetOf(), ValidatedModulePolicy::id)

        assertEquals(ModuleId.RUNTIME_COMPOSITION, composition.id)
        assertEquals(
            architecture.modules.keys - legacyHosts - ModuleId.RUNTIME_COMPOSITION,
            composition.allowedProjectDependencies,
        )
    }

    @Test
    fun `canonical policy is typed acyclic and migration aware`() {
        val validation = KastArchitecturePolicy.validate()

        val valid = assertInstanceOf<ArchitecturePolicyValidation.Valid>(validation)
        assertEquals(
            ModuleLifecycle.ACTIVE,
            valid.architecture.modules.getValue(ModuleId.ANALYSIS_API).lifecycle,
        )
        assertEquals(
            ModuleLifecycle.ACTIVE,
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
        assertEquals(53, valid.architecture.legacyAllowances.size)
    }

    @Test
    fun `active modules must exist and planned modules must be explicitly activated`() {
        val architecture = canonicalWithoutLegacyAllowances()
        val observation = ObservedArchitecture(
            modules = setOf(
                ModuleId.ANALYSIS_API,
                ModuleId.ANALYSIS_SERVER,
                ModuleId.INDEX_STORE,
                ModuleId.WORKSPACE_MUTATION_CONTRACT,
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
                ArchitectureViolation.PlannedModuleMaterialized(ModuleId.WORKSPACE_MUTATION_CONTRACT),
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
        modules = KastArchitecturePolicy.definition().modules
            .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .map(ModulePolicy::id)
            .toSet(),
        projectDependencies = projectDependencies + canonicalActiveImplementationBridges,
        effects = effects,
    )

    private companion object {
        val canonicalActiveImplementationBridges =
            KastArchitecturePolicy.definition().legacyImplementationBridges
                .filter { it.lifecycle == LegacyImplementationBridgeLifecycle.ACTIVE }
                .map(LegacyImplementationBridgePolicy::dependency)
                .toSet()
    }
}
