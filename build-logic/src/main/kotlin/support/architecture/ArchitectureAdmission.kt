package support.architecture

data class ObservedArchitecture(
    val modules: Set<ModuleId>,
    val projectDependencies: Set<ProjectDependencyObservation>,
    val effects: Set<EffectObservation>,
)

data class ObservedProjectGraph(
    val modules: Set<ModuleId>,
    val projectDependencies: Set<ProjectDependencyObservation>,
)

sealed interface ArchitectureObservationFailure {
    data class UnknownProjectPath(val projectPath: String) : ArchitectureObservationFailure

    data class MalformedProjectDependency(val notation: String) : ArchitectureObservationFailure
}

sealed interface ArchitectureObservationValidation {
    data class Valid(val graph: ObservedProjectGraph) : ArchitectureObservationValidation

    data class Invalid(val failures: List<ArchitectureObservationFailure>) : ArchitectureObservationValidation
}

object ArchitectureObservationParser {
    /**
     * Proof transition: `(ValidatedArchitecturePolicy, raw Gradle paths and edges) -> ObservedProjectGraph`.
     *
     * Establishes that every observed Gradle project and both endpoints of every direct edge have
     * canonical [ModuleId] identities. [ArchitectureObservationValidation.Invalid] is the closed
     * expected failure. Raw path extraction is permitted only in the Gradle plugin adapter.
     */
    fun parse(
        policy: ValidatedArchitecturePolicy,
        rawProjectPaths: Iterable<String>,
        rawProjectDependencies: Iterable<String>,
    ): ArchitectureObservationValidation {
        val moduleByPath = policy.modules.keys.associateBy(ModuleId::projectPath)
        val failures = mutableListOf<ArchitectureObservationFailure>()
        val modules = rawProjectPaths.mapNotNull { path ->
            moduleByPath[path] ?: run {
                failures += ArchitectureObservationFailure.UnknownProjectPath(path)
                null
            }
        }.toSet()
        val dependencies = rawProjectDependencies.mapNotNull { notation ->
            val parts = notation.split(EDGE_SEPARATOR)
            if (parts.size != 2) {
                failures += ArchitectureObservationFailure.MalformedProjectDependency(notation)
                return@mapNotNull null
            }
            val consumer = moduleByPath[parts[0]]
            val dependency = moduleByPath[parts[1]]
            if (consumer == null) failures += ArchitectureObservationFailure.UnknownProjectPath(parts[0])
            if (dependency == null) failures += ArchitectureObservationFailure.UnknownProjectPath(parts[1])
            if (consumer == null || dependency == null) null else ProjectDependencyObservation(consumer, dependency)
        }.toSet()
        return if (failures.isEmpty()) {
            ArchitectureObservationValidation.Valid(ObservedProjectGraph(modules, dependencies))
        } else {
            ArchitectureObservationValidation.Invalid(failures.distinct())
        }
    }

    const val EDGE_SEPARATOR: String = " -> "
}

sealed interface ArchitectureViolation {
    data class ActiveModuleMissing(val module: ModuleId) : ArchitectureViolation

    data class PlannedModuleMaterialized(val module: ModuleId) : ArchitectureViolation

    data class RetiredModulePresent(val module: ModuleId) : ArchitectureViolation

    data class UnbaselinedLegacyViolation(
        val violation: LegacyViolationKey,
    ) : ArchitectureViolation

    data class ObsoleteLegacyAllowance(
        val allowance: LegacyAllowance,
    ) : ArchitectureViolation
}

sealed interface ArchitectureAdmission {
    data class Accepted(val retainedLegacyAllowances: Set<LegacyAllowance>) : ArchitectureAdmission

    data class Rejected(val violations: Set<ArchitectureViolation>) : ArchitectureAdmission

    companion object {
        /**
         * Proof transition: `(ValidatedArchitecturePolicy, ObservedArchitecture) -> ArchitectureAdmission`.
         *
         * Establishes exact active-module presence, planned-module absence, approved direct edges,
         * permitted effects, and equality between observed legacy violations and their allowances.
         * [Rejected] is the closed expected failure. Raw Gradle paths and class-file references may
         * be extracted only before constructing [ObservedArchitecture].
         */
        fun evaluate(
            policy: ValidatedArchitecturePolicy,
            observation: ObservedArchitecture,
        ): ArchitectureAdmission {
            val lifecycleViolations = policy.modules.values.mapNotNull { module ->
                when {
                    module.lifecycle == ModuleLifecycle.ACTIVE && module.id !in observation.modules ->
                        ArchitectureViolation.ActiveModuleMissing(module.id)
                    module.lifecycle == ModuleLifecycle.PLANNED && module.id in observation.modules ->
                        ArchitectureViolation.PlannedModuleMaterialized(module.id)
                    module.lifecycle == ModuleLifecycle.RETIRED && module.id in observation.modules ->
                        ArchitectureViolation.RetiredModulePresent(module.id)
                    else -> null
                }
            }
            val observedLegacyViolations = buildSet {
                observation.projectDependencies
                    .filterNot { edge ->
                        edge.dependency in policy.modules.getValue(edge.consumer).allowedProjectDependencies
                    }
                    .mapTo(this, LegacyViolationKey::UnapprovedProjectDependency)
                observation.effects
                    .filterNot { effect ->
                        effect.effect in policy.modules.getValue(effect.module).allowedEffects
                    }
                    .mapTo(this, LegacyViolationKey::ForbiddenEffectUse)
            }
            val allowancesByViolation = policy.legacyAllowances.associateBy(LegacyAllowance::violation)
            val unbaselined = observedLegacyViolations
                .filterNot(allowancesByViolation::containsKey)
                .map(ArchitectureViolation::UnbaselinedLegacyViolation)
            val obsolete = policy.legacyAllowances
                .filterNot { it.violation in observedLegacyViolations }
                .map(ArchitectureViolation::ObsoleteLegacyAllowance)
            val violations = (lifecycleViolations + unbaselined + obsolete).toSet()
            return if (violations.isEmpty()) {
                Accepted(policy.legacyAllowances)
            } else {
                Rejected(violations)
            }
        }
    }
}
