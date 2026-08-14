package support.architecture

import support.architecture.baseline.KastArchitectureLegacyImplementationBridges

internal sealed interface LegacyImplementationBridgeValidation {
    data class Valid(
        val bridge: ValidatedLegacyImplementationBridge.Active,
    ) : LegacyImplementationBridgeValidation

    data class Invalid(
        val failures: List<ArchitecturePolicyFailure>,
    ) : LegacyImplementationBridgeValidation
}

internal object LegacyImplementationBridgeValidator {
    /**
     * Proof transition: `(LegacyImplementationBridgePolicy, module policy, delivery policy) ->
     * ValidatedLegacyImplementationBridge.Active`.
     *
     * Establishes an exact subtraction-only, non-permanent edge from one active non-legacy module
     * to one active legacy implementation host, with an open migration task owned solely by the
     * consumer. [LegacyImplementationBridgeValidation.Invalid] is the closed expected failure. Raw
     * bridge policy construction is permitted only in architecture source definitions and tests.
     */
    fun validate(
        bridge: LegacyImplementationBridgePolicy,
        modules: Map<ModuleId, ModulePolicy>,
        tasks: Map<MutationDeliveryTaskId, MutationDeliveryTaskPolicy>,
    ): LegacyImplementationBridgeValidation {
        val consumer = modules[bridge.dependency.consumer]
        val dependency = modules[bridge.dependency.dependency]
        val retirementTask = tasks[bridge.retirementTask]
        val failures = buildList {
            if (bridge.dependency !in KastArchitectureLegacyImplementationBridges.admittedDependencies) {
                add(ArchitecturePolicyFailure.UnadmittedLegacyImplementationBridge(bridge))
            }
            if (consumer == null) {
                add(
                    ArchitecturePolicyFailure.MissingLegacyImplementationBridgeModule(
                        bridge,
                        bridge.dependency.consumer,
                    ),
                )
            }
            if (dependency == null) {
                add(
                    ArchitecturePolicyFailure.MissingLegacyImplementationBridgeModule(
                        bridge,
                        bridge.dependency.dependency,
                    ),
                )
            }
            if (
                consumer != null &&
                (consumer.role == ModuleRole.LEGACY_HOST || consumer.lifecycle != ModuleLifecycle.ACTIVE)
            ) {
                add(ArchitecturePolicyFailure.InvalidLegacyImplementationBridgeDirection(bridge))
            }
            if (
                dependency != null &&
                (dependency.role != ModuleRole.LEGACY_HOST || dependency.lifecycle != ModuleLifecycle.ACTIVE)
            ) {
                add(ArchitecturePolicyFailure.InvalidLegacyImplementationBridgeDirection(bridge))
            }
            if (
                consumer != null &&
                bridge.dependency.dependency in consumer.allowedProjectDependencies
            ) {
                add(ArchitecturePolicyFailure.PermanentLegacyImplementationBridge(bridge))
            }
            if (retirementTask == null) {
                add(ArchitecturePolicyFailure.MissingLegacyImplementationBridgeRetirementTask(bridge))
            } else {
                if (retirementTask.lifecycle == MutationDeliveryTaskLifecycle.COMPLETED) {
                    add(ArchitecturePolicyFailure.CompletedLegacyImplementationBridgeRetirementTask(bridge))
                }
                val expectedOwner = MutationDeliveryOwner.Modules(setOf(bridge.dependency.consumer))
                if (retirementTask.owner != expectedOwner) {
                    add(
                        ArchitecturePolicyFailure.InvalidLegacyImplementationBridgeRetirementOwner(
                            bridge,
                            retirementTask.owner,
                        ),
                    )
                }
                if (retirementTask.phase != MutationDeliveryPhase.MIGRATION) {
                    add(
                        ArchitecturePolicyFailure.InvalidLegacyImplementationBridgeRetirementPhase(
                            bridge,
                            retirementTask.phase,
                        ),
                    )
                }
            }
            if (bridge.lifecycle == LegacyImplementationBridgeLifecycle.COMPLETED) {
                add(ArchitecturePolicyFailure.CompletedLegacyImplementationBridge(bridge))
            }
        }
        return if (failures.isEmpty()) {
            LegacyImplementationBridgeValidation.Valid(
                ValidatedLegacyImplementationBridge.Active(
                    bridge.dependency,
                    bridge.retirementTask,
                ),
            )
        } else {
            LegacyImplementationBridgeValidation.Invalid(failures)
        }
    }
}
