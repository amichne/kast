package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LegacyMigrationPolicyTest {
    private val dependency = ProjectDependencyObservation(
        ModuleId.ANALYSIS_SERVER,
        ModuleId.RUNTIME_BINDINGS,
    )

    @Test
    fun `canonical migration is planned exact and separate from permanent policy`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val migration = assertInstanceOf<ValidatedLegacyMigrationEdge.Planned>(
            architecture.legacyMigrationEdges.getValue(dependency),
        )

        assertEquals(MutationDeliveryTaskId.F04, migration.retirementTask)
        assertTrue(
            dependency.dependency !in
                architecture.modules.getValue(dependency.consumer).allowedProjectDependencies,
        )
    }

    @Test
    fun `migration ceiling permits subtraction and rejects exact growth`() {
        val canonical = KastArchitecturePolicy.definition()
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(
                canonical.copy(legacyMigrationEdges = emptyList()),
            ),
        )

        val added = migration(LegacyMigrationLifecycle.PLANNED).copy(
            dependency = ProjectDependencyObservation(
                ModuleId.ANALYSIS_API,
                ModuleId.SYMBOL_CONTRACT,
            ),
        )
        assertFailure<ArchitecturePolicyFailure.UnadmittedLegacyMigration>(
            canonical.copy(
                legacyAllowances = emptyList(),
                legacyMigrationEdges = canonical.legacyMigrationEdges + added,
            ),
        )
    }

    @Test
    fun `physical adapter migration target fails closed`() {
        val physicalTarget = migration(LegacyMigrationLifecycle.PLANNED).copy(
            dependency = ProjectDependencyObservation(
                ModuleId.ANALYSIS_SERVER,
                ModuleId.EVIDENCE_SQLITE,
            ),
        )

        assertFailure<ArchitecturePolicyFailure.InvalidLegacyMigrationTarget>(
            definition(physicalTarget),
        )
    }

    @Test
    fun `only an observed active migration is admitted`() {
        val plannedArchitecture = validatedDefinition(
            edge = migration(LegacyMigrationLifecycle.PLANNED),
            activateTarget = true,
        )
        val plannedRejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(plannedArchitecture, observation(setOf(dependency))),
        )
        assertTrue(
            ArchitectureViolation.UnbaselinedLegacyViolation(
                LegacyViolationKey.UnapprovedProjectDependency(dependency),
            ) in plannedRejected.violations,
        )

        val activeArchitecture = validatedDefinition(
            edge = migration(LegacyMigrationLifecycle.ACTIVE),
            activateTarget = true,
        )
        val accepted = assertInstanceOf<ArchitectureAdmission.Accepted>(
            ArchitectureAdmission.evaluate(activeArchitecture, observation(setOf(dependency))),
        )
        assertEquals(setOf(dependency), accepted.retainedLegacyMigrations.keys)

        val obsolete = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(activeArchitecture, observation(emptySet())),
        )
        assertTrue(obsolete.violations.any { it is ArchitectureViolation.ObsoleteLegacyMigration })
    }

    @Test
    fun `reverse completed permanent and unretired migrations fail closed`() {
        val reverse = migration(LegacyMigrationLifecycle.ACTIVE).copy(
            dependency = ProjectDependencyObservation(ModuleId.RUNTIME_BINDINGS, ModuleId.ANALYSIS_SERVER),
        )
        assertFailure<ArchitecturePolicyFailure.InvalidLegacyMigrationDirection>(definition(reverse))

        val completed = migration(LegacyMigrationLifecycle.COMPLETED)
        assertFailure<ArchitecturePolicyFailure.CompletedLegacyMigration>(definition(completed))

        val completedTask = definition(migration(LegacyMigrationLifecycle.ACTIVE)).let { definition ->
            definition.copy(
                mutationDeliveryTasks = definition.mutationDeliveryTasks.map { task ->
                    if (task.id == MutationDeliveryTaskId.F04) {
                        task.copy(lifecycle = MutationDeliveryTaskLifecycle.COMPLETED)
                    } else {
                        task
                    }
                },
            )
        }
        assertFailure<ArchitecturePolicyFailure.CompletedLegacyMigrationRetirementTask>(completedTask)

        val missingTask = definition(migration(LegacyMigrationLifecycle.ACTIVE)).let { definition ->
            definition.copy(
                mutationDeliveryTasks = definition.mutationDeliveryTasks
                    .filterNot { it.id == MutationDeliveryTaskId.F04 },
            )
        }
        assertFailure<ArchitecturePolicyFailure.MissingLegacyMigrationRetirementTask>(missingTask)

        val permanent = definition(migration(LegacyMigrationLifecycle.ACTIVE)).let { definition ->
            definition.copy(
                modules = definition.modules.map { module ->
                    if (module.id == dependency.consumer) {
                        module.copy(
                            allowedProjectDependencies =
                                module.allowedProjectDependencies + dependency.dependency,
                        )
                    } else {
                        module
                    }
                },
            )
        }
        assertFailure<ArchitecturePolicyFailure.PermanentLegacyMigration>(permanent)

        val duplicate = definition(migration(LegacyMigrationLifecycle.PLANNED)).copy(
            legacyMigrationEdges = listOf(
                migration(LegacyMigrationLifecycle.PLANNED),
                migration(LegacyMigrationLifecycle.ACTIVE),
            ),
        )
        assertFailure<ArchitecturePolicyFailure.DuplicateLegacyMigration>(duplicate)
    }

    @Test
    fun `dependency allowances and wildcard notation cannot create migrations`() {
        val allowance = LegacyAllowance(
            violation = LegacyViolationKey.UnapprovedProjectDependency(dependency),
            retirementTask = MutationDeliveryTaskId.F04,
        )
        assertFailure<ArchitecturePolicyFailure.DependencyAllowanceRequiresMigration>(
            definition(migration(LegacyMigrationLifecycle.PLANNED)).copy(
                legacyAllowances = listOf(allowance),
            ),
        )

        val architecture = validatedDefinition(migration(LegacyMigrationLifecycle.PLANNED))
        val parsed = assertInstanceOf<ArchitectureObservationValidation.Invalid>(
            ArchitectureObservationParser.parse(
                architecture,
                rawProjectPaths = canonicalActiveModules.map(ModuleId::projectPath),
                rawProjectDependencies = listOf(":analysis-server -> :symbol:*"),
            ),
        )
        assertTrue(
            ArchitectureObservationFailure.UnknownProjectPath(":symbol:*") in parsed.failures,
        )
    }

    private fun migration(lifecycle: LegacyMigrationLifecycle): LegacyMigrationEdgePolicy =
        LegacyMigrationEdgePolicy(
            dependency = dependency,
            lifecycle = lifecycle,
            retirementTask = MutationDeliveryTaskId.F04,
        )

    private fun definition(edge: LegacyMigrationEdgePolicy): ArchitecturePolicyDefinition =
        KastArchitecturePolicy.definition().copy(
            legacyAllowances = emptyList(),
            legacyMigrationEdges = listOf(edge),
        )

    private fun validatedDefinition(
        edge: LegacyMigrationEdgePolicy,
        activateTarget: Boolean = false,
    ): ValidatedArchitecturePolicy {
        val definition = definition(edge)
        val modules = if (activateTarget) {
            definition.modules.map { module ->
                if (module.id == dependency.dependency) {
                    module.copy(lifecycle = ModuleLifecycle.ACTIVE)
                } else {
                    module
                }
            }
        } else {
            definition.modules
        }
        return assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(definition.copy(modules = modules)),
        ).architecture
    }

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
        modules = canonicalActiveModules + dependency.dependency,
        projectDependencies = dependencies + canonicalActiveImplementationBridges,
        effects = emptySet(),
    )

    private companion object {
        val canonicalActiveModules = KastArchitecturePolicy.definition().modules
            .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .map(ModulePolicy::id)
            .toSet()
        val canonicalActiveImplementationBridges =
            KastArchitecturePolicy.definition().legacyImplementationBridges
                .filter { it.lifecycle == LegacyImplementationBridgeLifecycle.ACTIVE }
                .map(LegacyImplementationBridgePolicy::dependency)
                .toSet()
    }
}
