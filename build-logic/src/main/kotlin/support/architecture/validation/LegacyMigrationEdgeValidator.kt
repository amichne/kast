package support.architecture

import support.architecture.baseline.KastArchitectureLegacyMigrations

internal sealed interface LegacyMigrationEdgeValidation {
    data class Valid(
        val migration: ValidatedLegacyMigrationEdge,
    ) : LegacyMigrationEdgeValidation

    data class Invalid(
        val failures: List<ArchitecturePolicyFailure>,
    ) : LegacyMigrationEdgeValidation
}

internal object LegacyMigrationEdgeValidator {
    /**
     * Proof transition: `(LegacyMigrationEdgePolicy, module policy, delivery policy) ->
     * ValidatedLegacyMigrationEdge`.
     *
     * Establishes an exact subtraction-only, non-permanent edge from one active legacy host to one
     * non-retired contract, SPI, or service, with an existing open retirement task and an admissible
     * Planned or Active lifecycle. [LegacyMigrationEdgeValidation.Invalid] is the closed expected
     * failure. Raw migration policy construction is permitted only in architecture source
     * definitions and policy tests.
     */
    fun validate(
        migration: LegacyMigrationEdgePolicy,
        modules: Map<ModuleId, ModulePolicy>,
        tasks: Map<MutationDeliveryTaskId, MutationDeliveryTaskPolicy>,
    ): LegacyMigrationEdgeValidation {
        val consumer = modules[migration.dependency.consumer]
        val dependency = modules[migration.dependency.dependency]
        val retirementTask = tasks[migration.retirementTask]
        val failures = buildList {
            if (migration.dependency !in KastArchitectureLegacyMigrations.admittedDependencies) {
                add(ArchitecturePolicyFailure.UnadmittedLegacyMigration(migration))
            }
            if (consumer == null) {
                add(
                    ArchitecturePolicyFailure.MissingLegacyMigrationModule(
                        migration,
                        migration.dependency.consumer,
                    ),
                )
            }
            if (dependency == null) {
                add(
                    ArchitecturePolicyFailure.MissingLegacyMigrationModule(
                        migration,
                        migration.dependency.dependency,
                    ),
                )
            }
            if (
                consumer != null &&
                (
                    consumer.role != ModuleRole.LEGACY_HOST ||
                    consumer.lifecycle != ModuleLifecycle.ACTIVE
                )
            ) {
                add(ArchitecturePolicyFailure.InvalidLegacyMigrationDirection(migration))
            }
            if (dependency?.role == ModuleRole.LEGACY_HOST) {
                add(ArchitecturePolicyFailure.InvalidLegacyMigrationDirection(migration))
            }
            if (dependency != null) {
                val targetFailures = buildSet {
                    if (dependency.role !in inwardTargetRoles) {
                        add(LegacyMigrationTargetFailure.TARGET_ROLE_NOT_INWARD)
                    }
                    if (dependency.lifecycle == ModuleLifecycle.RETIRED) {
                        add(LegacyMigrationTargetFailure.TARGET_RETIRED)
                    }
                }
                if (targetFailures.isNotEmpty()) {
                    add(
                        ArchitecturePolicyFailure.InvalidLegacyMigrationTarget(
                            migration,
                            targetFailures,
                        ),
                    )
                }
            }
            if (
                consumer != null &&
                migration.dependency.dependency in consumer.allowedProjectDependencies
            ) {
                add(ArchitecturePolicyFailure.PermanentLegacyMigration(migration))
            }
            if (retirementTask == null) {
                add(ArchitecturePolicyFailure.MissingLegacyMigrationRetirementTask(migration))
            } else if (retirementTask.lifecycle == MutationDeliveryTaskLifecycle.COMPLETED) {
                add(ArchitecturePolicyFailure.CompletedLegacyMigrationRetirementTask(migration))
            }
            if (migration.lifecycle == LegacyMigrationLifecycle.COMPLETED) {
                add(ArchitecturePolicyFailure.CompletedLegacyMigration(migration))
            }
        }
        if (failures.isNotEmpty()) return LegacyMigrationEdgeValidation.Invalid(failures)
        return when (migration.lifecycle) {
            LegacyMigrationLifecycle.PLANNED -> LegacyMigrationEdgeValidation.Valid(
                ValidatedLegacyMigrationEdge.Planned(
                    migration.dependency,
                    migration.retirementTask,
                ),
            )
            LegacyMigrationLifecycle.ACTIVE -> LegacyMigrationEdgeValidation.Valid(
                ValidatedLegacyMigrationEdge.Active(
                    migration.dependency,
                    migration.retirementTask,
                ),
            )
            LegacyMigrationLifecycle.COMPLETED -> LegacyMigrationEdgeValidation.Invalid(
                listOf(ArchitecturePolicyFailure.CompletedLegacyMigration(migration)),
            )
        }
    }

    private val inwardTargetRoles = setOf(
        ModuleRole.CONTRACT,
        ModuleRole.SPI,
        ModuleRole.SERVICE,
    )
}
