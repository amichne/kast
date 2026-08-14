package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LegacyImplementationBridgePolicyTest {
    private val dependency = ProjectDependencyObservation(
        ModuleId.EVIDENCE_SQLITE,
        ModuleId.INDEX_STORE,
    )

    @Test
    fun `canonical implementation bridge is active exact retirement bound and non-permanent`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val bridge = assertInstanceOf<ValidatedLegacyImplementationBridge.Active>(
            architecture.legacyImplementationBridges.getValue(dependency),
        )
        val retirement = architecture.mutationDeliveryTasks.getValue(bridge.retirementTask)

        assertEquals(MutationDeliveryTaskId.M04, bridge.retirementTask)
        assertEquals(MutationDeliveryPhase.MIGRATION, retirement.phase)
        assertEquals(
            MutationDeliveryOwner.Modules(setOf(ModuleId.EVIDENCE_SQLITE)),
            retirement.owner,
        )
        assertTrue(
            dependency.dependency !in
                architecture.modules.getValue(dependency.consumer).allowedProjectDependencies,
        )
    }

    @Test
    fun `only the exact observed active implementation bridge is admitted`() {
        val architecture = validatedDefinition()
        val accepted = assertInstanceOf<ArchitectureAdmission.Accepted>(
            ArchitectureAdmission.evaluate(architecture, observation(setOf(dependency))),
        )

        assertEquals(setOf(dependency), accepted.retainedLegacyImplementationBridges.keys)

        val obsolete = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(architecture, observation(emptySet())),
        )
        assertTrue(
            obsolete.violations.any {
                it is ArchitectureViolation.ObsoleteLegacyImplementationBridge &&
                    it.bridge.dependency == dependency
            },
        )

        val sibling = ProjectDependencyObservation(
            ModuleId.CHANGE_JOURNAL_SQLITE,
            ModuleId.INDEX_STORE,
        )
        val unapproved = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(architecture, observation(setOf(dependency, sibling))),
        )
        assertTrue(
            ArchitectureViolation.UnbaselinedLegacyViolation(
                LegacyViolationKey.UnapprovedProjectDependency(sibling),
            ) in unapproved.violations,
        )
    }

    @Test
    fun `unadmitted sibling bridge cannot expand the exact ceiling`() {
        val sibling = bridge().copy(
            dependency = ProjectDependencyObservation(
                ModuleId.CHANGE_JOURNAL_SQLITE,
                ModuleId.INDEX_STORE,
            ),
        )

        assertFailure<ArchitecturePolicyFailure.UnadmittedLegacyImplementationBridge>(
            definition().copy(
                legacyImplementationBridges = definition().legacyImplementationBridges + sibling,
            ),
        )
    }

    @Test
    fun `completed permanent and unretired implementation bridges fail closed`() {
        assertFailure<ArchitecturePolicyFailure.CompletedLegacyImplementationBridge>(
            definition().copy(
                legacyImplementationBridges = listOf(
                    bridge(LegacyImplementationBridgeLifecycle.COMPLETED),
                ),
            ),
        )

        assertFailure<ArchitecturePolicyFailure.MissingLegacyImplementationBridgeRetirementTask>(
            definition().copy(
                mutationDeliveryTasks = definition().mutationDeliveryTasks
                    .filterNot { it.id == MutationDeliveryTaskId.M04 },
            ),
        )

        assertFailure<ArchitecturePolicyFailure.CompletedLegacyImplementationBridgeRetirementTask>(
            definition().copy(
                mutationDeliveryTasks = definition().mutationDeliveryTasks.map { task ->
                    if (task.id == MutationDeliveryTaskId.M04) {
                        task.copy(lifecycle = MutationDeliveryTaskLifecycle.COMPLETED)
                    } else {
                        task
                    }
                },
            ),
        )

        assertFailure<ArchitecturePolicyFailure.InvalidLegacyImplementationBridgeRetirementOwner>(
            definition().copy(
                mutationDeliveryTasks = definition().mutationDeliveryTasks.map { task ->
                    if (task.id == MutationDeliveryTaskId.M04) {
                        task.copy(owner = MutationDeliveryOwner.Modules(setOf(ModuleId.INDEX_STORE)))
                    } else {
                        task
                    }
                },
            ),
        )

        assertFailure<ArchitecturePolicyFailure.InvalidLegacyImplementationBridgeRetirementPhase>(
            definition().copy(
                mutationDeliveryTasks = definition().mutationDeliveryTasks.map { task ->
                    if (task.id == MutationDeliveryTaskId.M04) {
                        task.copy(phase = MutationDeliveryPhase.PROOF)
                    } else {
                        task
                    }
                },
            ),
        )

        assertFailure<ArchitecturePolicyFailure.PermanentLegacyImplementationBridge>(
            definition().copy(
                modules = definition().modules.map { module ->
                    if (module.id == dependency.consumer) {
                        module.copy(
                            allowedProjectDependencies =
                                module.allowedProjectDependencies + dependency.dependency,
                        )
                    } else {
                        module
                    }
                },
            ),
        )
    }

    private fun bridge(
        lifecycle: LegacyImplementationBridgeLifecycle = LegacyImplementationBridgeLifecycle.ACTIVE,
    ): LegacyImplementationBridgePolicy = LegacyImplementationBridgePolicy(
        dependency = dependency,
        lifecycle = lifecycle,
        retirementTask = MutationDeliveryTaskId.M04,
    )

    private fun definition(): ArchitecturePolicyDefinition =
        KastArchitecturePolicy.definition().copy(legacyAllowances = emptyList())

    private fun validatedDefinition(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(definition()),
        ).architecture

    private inline fun <reified T : ArchitecturePolicyFailure> assertFailure(
        definition: ArchitecturePolicyDefinition,
    ) {
        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )
        assertTrue(invalid.failures.any { it is T })
    }

    private fun observation(
        dependencies: Set<ProjectDependencyObservation>,
    ): ObservedArchitecture = ObservedArchitecture(
        modules = definition().modules
            .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .mapTo(linkedSetOf(), ModulePolicy::id),
        projectDependencies = dependencies,
        effects = emptySet(),
    )
}
