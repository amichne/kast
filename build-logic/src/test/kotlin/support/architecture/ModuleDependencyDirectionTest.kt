package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ModuleDependencyDirectionTest {
    @Test
    fun `materialized CLI is active inward only process boundary`() {
        val architecture = canonical()
        val module = architecture.modules.getValue(ModuleId.CLI)

        assertEquals(ModuleLifecycle.ACTIVE, module.lifecycle)
        assertEquals(ModuleRole.CLI, module.role)
        assertEquals(
            setOf(
                ModuleId.KERNEL,
                ModuleId.PROTOCOL_CONTRACT,
                ModuleId.PROTOCOL_REGISTRY,
                ModuleId.PROTOCOL_WIRE,
            ),
            module.allowedProjectDependencies,
        )
        assertEquals(setOf(ForbiddenEffect.PROCESS_CONTROL), module.allowedEffects)
        assertTrue(
            ModuleId.CLI !in architecture.modules
                .getValue(ModuleId.RUNTIME_COMPOSITION)
                .allowedProjectDependencies,
        )
    }

    @Test
    fun `CLI rejects outward implementation dependency and foreign effect`() {
        val definition = KastArchitecturePolicy.definition()
            .withDependency(ModuleId.CLI, ModuleId.SYMBOL_INTELLIJ)
            .withEffect(ModuleId.CLI, ForbiddenEffect.JDBC)

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.CLI,
                ModuleId.SYMBOL_INTELLIJ,
                ModuleRole.INTELLIJ_READ_ADAPTER,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(
                ModuleId.CLI,
                ForbiddenEffect.JDBC,
            ) in invalid.failures,
        )
    }

    @Test
    fun `materialized runtime server is active contract-only transport`() {
        val module = canonical().modules.getValue(ModuleId.RUNTIME_SERVER)

        assertEquals(ModuleLifecycle.ACTIVE, module.lifecycle)
        assertEquals(ModuleRole.TRANSPORT, module.role)
        assertEquals(
            setOf(ModuleId.PROTOCOL_CONTRACT, ModuleId.PROTOCOL_WIRE),
            module.allowedProjectDependencies,
        )
        assertEquals(emptySet<ForbiddenEffect>(), module.allowedEffects)
    }

    @Test
    fun `runtime server rejects outward implementation dependencies`() {
        val definition = KastArchitecturePolicy.definition()
            .withDependency(ModuleId.RUNTIME_SERVER, ModuleId.SYMBOL_INTELLIJ)

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.RUNTIME_SERVER,
                ModuleId.SYMBOL_INTELLIJ,
                ModuleRole.INTELLIJ_READ_ADAPTER,
            ) in invalid.failures,
        )
    }

    @Test
    fun `runtime server rejects observed platform effects`() {
        val definition = KastArchitecturePolicy.definition().copy(legacyAllowances = emptyList())
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(definition),
        ).architecture
        val effect = EffectObservation(
            module = ModuleId.RUNTIME_SERVER,
            effect = ForbiddenEffect.INTELLIJ_PLATFORM,
            caller = JvmMember.of("example/RuntimeServer", "dispatch", "()V"),
            target = JvmMember.of("com/intellij/openapi/project/Project", "getName", "()Ljava/lang/String;"),
        )
        val observation = ObservedArchitecture(
            modules = definition.modules
                .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
                .mapTo(mutableSetOf(), ModulePolicy::id),
            projectDependencies = definition.legacyImplementationBridges
                .filter { it.lifecycle == LegacyImplementationBridgeLifecycle.ACTIVE }
                .mapTo(mutableSetOf(), LegacyImplementationBridgePolicy::dependency),
            effects = setOf(effect),
        )

        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(architecture, observation),
        )

        assertTrue(
            ArchitectureViolation.UnbaselinedLegacyViolation(
                LegacyViolationKey.ForbiddenEffectUse(effect),
            ) in rejected.violations,
        )
    }

    @Test
    fun `materialized protocol substrate has active host neutral migration authority`() {
        val architecture = canonical()
        val expected = mapOf(
            ModuleId.PROTOCOL_CONTRACT to setOf(ModuleId.KERNEL),
            ModuleId.PROTOCOL_WIRE to setOf(
                ModuleId.KERNEL,
                ModuleId.PROTOCOL_CONTRACT,
                ModuleId.PROTOCOL_REGISTRY,
            ),
        )

        assertEquals(expected.keys, expected.keys.intersect(architecture.modules.keys))
        expected.forEach { (id, dependencies) ->
            val module = architecture.modules.getValue(id)
            assertEquals(ModuleLifecycle.ACTIVE, module.lifecycle, id.projectPath)
            assertEquals(ModuleRole.CONTRACT, module.role, id.projectPath)
            assertEquals(dependencies, module.allowedProjectDependencies, id.projectPath)
            assertEquals(emptySet<ForbiddenEffect>(), module.allowedEffects, id.projectPath)
        }
    }

    @Test
    fun `canonical graph centers ports and gives composition the complete implementation graph`() {
        val architecture = canonical()

        assertEquals(ModuleRole.SPI, architecture.modules.getValue(ModuleId.WORKSPACE_SPI).role)
        assertEquals(ModuleRole.CONTRACT, architecture.modules.getValue(ModuleId.EVIDENCE_CONTRACT).role)
        assertEquals(ModuleRole.SPI, architecture.modules.getValue(ModuleId.CHANGE_RECOVERY_SPI).role)
        assertEquals(
            setOf(ModuleId.CHANGE_RECOVERY_CONTRACT),
            architecture.modules.getValue(ModuleId.CHANGE_RECOVERY_SPI).allowedProjectDependencies,
        )
        assertEquals(
            setOf(ModuleId.CHANGE_RECOVERY_CONTRACT, ModuleId.CHANGE_RECOVERY_SPI),
            architecture.modules.getValue(ModuleId.CHANGE_RECOVERY_FILESYSTEM).allowedProjectDependencies,
        )
        assertTrue(
            ModuleId.CHANGE_RECOVERY_SPI in
                architecture.modules.getValue(ModuleId.CHANGE_RECOVERY_SERVICE).allowedProjectDependencies,
        )
        assertEquals(
            setOf(
                ModuleId.KERNEL,
                ModuleId.PROTOCOL_CONTRACT,
                ModuleId.CHANGE_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
                ModuleId.WORKSPACE_CONTRACT,
            ),
            architecture.modules.getValue(ModuleId.PROTOCOL_REGISTRY).allowedProjectDependencies,
        )

        val composition = architecture.modules.getValue(ModuleId.RUNTIME_COMPOSITION)
        val legacyHosts = architecture.modules.values
            .filter { module -> module.role == ModuleRole.LEGACY_HOST }
            .mapTo(mutableSetOf(), ValidatedModulePolicy::id)
        assertEquals(ModuleRole.COMPOSITION, composition.role)
        assertEquals(
            architecture.modules.keys - legacyHosts -
                setOf(ModuleId.CLI, ModuleId.RUNTIME_COMPOSITION),
            composition.allowedProjectDependencies,
        )
        assertEquals(
            setOf(ModuleId.RUNTIME_COMPOSITION),
            architecture.modules.values
                .filter { module -> module.role == ModuleRole.COMPOSITION }
                .mapTo(mutableSetOf(), ValidatedModulePolicy::id),
        )
    }

    @Test
    fun `every inverse service adapter and registry edge fails closed`() {
        val definition = KastArchitecturePolicy.definition()
            .withDependency(ModuleId.WORKSPACE_SERVICE, ModuleId.EVIDENCE_SQLITE)
            .withDependency(ModuleId.CHANGE_RECOVERY_SERVICE, ModuleId.CHANGE_RECOVERY_FILESYSTEM)
            .withDependency(ModuleId.CHANGE_APPLY_INTELLIJ, ModuleId.CHANGE_APPLY_SERVICE)
            .withDependency(ModuleId.CHANGE_CONTRACT, ModuleId.PROTOCOL_REGISTRY)

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.WORKSPACE_SERVICE,
                ModuleId.EVIDENCE_SQLITE,
                ModuleRole.SQLITE_ADAPTER,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.CHANGE_RECOVERY_SERVICE,
                ModuleId.CHANGE_RECOVERY_FILESYSTEM,
                ModuleRole.FILESYSTEM_WRITE_ADAPTER,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.CHANGE_APPLY_INTELLIJ,
                ModuleId.CHANGE_APPLY_SERVICE,
                ModuleRole.SERVICE,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.FeatureContractDependsOnRegistry(ModuleId.CHANGE_CONTRACT) in
                invalid.failures,
        )
    }

    @Test
    fun `missing unexpected and incomplete composition owners fail closed`() {
        val canonical = KastArchitecturePolicy.definition()
        val missing = canonical.copy(
            modules = canonical.modules.filterNot { module -> module.id == ModuleId.RUNTIME_COMPOSITION },
        )
        val unexpected = canonical.copy(
            modules = canonical.modules.map { module ->
                if (module.id == ModuleId.PROTOCOL_CONTINUATION) {
                    module.copy(role = ModuleRole.COMPOSITION)
                } else {
                    module
                }
            },
        )
        val incomplete = canonical.copy(
            modules = canonical.modules.map { module ->
                if (module.id == ModuleId.RUNTIME_COMPOSITION) {
                    module.copy(
                        allowedProjectDependencies =
                            module.allowedProjectDependencies - ModuleId.EVIDENCE_SQLITE,
                    )
                } else {
                    module
                }
            },
        )

        assertPolicyFailure(missing, ArchitecturePolicyFailure.MissingRuntimeComposition)
        assertPolicyFailure(
            unexpected,
            ArchitecturePolicyFailure.UnexpectedCompositionOwner(ModuleId.PROTOCOL_CONTINUATION),
        )
        assertPolicyFailure(
            incomplete,
            ArchitecturePolicyFailure.InvalidRuntimeCompositionDependencies(
                missing = setOf(ModuleId.EVIDENCE_SQLITE),
                unexpected = emptySet(),
            ),
        )
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

    private fun ArchitecturePolicyDefinition.withDependency(
        consumer: ModuleId,
        dependency: ModuleId,
    ): ArchitecturePolicyDefinition = copy(
        modules = modules.map { module ->
            if (module.id == consumer) {
                module.copy(allowedProjectDependencies = module.allowedProjectDependencies + dependency)
            } else {
                module
            }
        },
    )

    private fun ArchitecturePolicyDefinition.withEffect(
        moduleId: ModuleId,
        effect: ForbiddenEffect,
    ): ArchitecturePolicyDefinition = copy(
        modules = modules.map { module ->
            if (module.id == moduleId) {
                module.copy(allowedEffects = module.allowedEffects + effect)
            } else {
                module
            }
        },
    )

    private fun assertPolicyFailure(
        definition: ArchitecturePolicyDefinition,
        expected: ArchitecturePolicyFailure,
    ) {
        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )
        assertTrue(expected in invalid.failures)
    }
}
