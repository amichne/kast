package support.architecture

sealed interface ModuleRoleConventionObservation {
    data object NotCollected : ModuleRoleConventionObservation

    data class Collected(
        val conventions: Map<ModuleId, ModuleRoleConvention>,
    ) : ModuleRoleConventionObservation
}

data class ObservedArchitecture(
    val modules: Set<ModuleId>,
    val projectDependencies: Set<ProjectDependencyObservation>,
    val effects: Set<EffectObservation>,
    val exportedProjectDependencies: Set<ProjectDependencyObservation> = emptySet(),
    val moduleRoleConventions: ModuleRoleConventionObservation =
        ModuleRoleConventionObservation.NotCollected,
)

data class ObservedProjectGraph(
    val modules: Set<ModuleId>,
    val projectDependencies: Set<ProjectDependencyObservation>,
    val exportedProjectDependencies: Set<ProjectDependencyObservation>,
    val moduleRoleConventions: ModuleRoleConventionObservation.Collected,
)

sealed interface ArchitectureObservationFailure {
    data class UnknownProjectPath(val projectPath: String) : ArchitectureObservationFailure

    data class MalformedProjectDependency(val notation: String) : ArchitectureObservationFailure

    data class MalformedModuleRoleConvention(val notation: String) : ArchitectureObservationFailure

    data class UnknownModuleRoleConvention(val pluginId: String) : ArchitectureObservationFailure

    data class DuplicateModuleRoleConvention(val module: ModuleId) : ArchitectureObservationFailure
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
     * canonical [ModuleId] identities, every exported edge is exact, and every applied role marker
     * resolves to one typed [ModuleRoleConvention]. [ArchitectureObservationValidation.Invalid] is
     * the closed expected failure. Raw extraction is permitted only in the Gradle plugin adapter.
     */
    fun parse(
        policy: ValidatedArchitecturePolicy,
        rawProjectPaths: Iterable<String>,
        rawProjectDependencies: Iterable<String>,
        rawExportedProjectDependencies: Iterable<String> = emptyList(),
        rawModuleRoleConventions: Iterable<String> = emptyList(),
    ): ArchitectureObservationValidation {
        val moduleByPath = policy.modules.keys.associateBy(ModuleId::projectPath)
        val conventionByPluginId = ModuleRoleConvention.entries.associateBy(ModuleRoleConvention::pluginId)
        val failures = mutableListOf<ArchitectureObservationFailure>()
        val modules = rawProjectPaths.mapNotNull { path ->
            moduleByPath[path] ?: run {
                failures += ArchitectureObservationFailure.UnknownProjectPath(path)
                null
            }
        }.toSet()

        fun parseDependency(notation: String): ProjectDependencyObservation? {
            val parts = notation.split(EDGE_SEPARATOR)
            if (parts.size != 2) {
                failures += ArchitectureObservationFailure.MalformedProjectDependency(notation)
                return null
            }
            val consumer = moduleByPath[parts[0]]
            val dependency = moduleByPath[parts[1]]
            if (consumer == null) failures += ArchitectureObservationFailure.UnknownProjectPath(parts[0])
            if (dependency == null) failures += ArchitectureObservationFailure.UnknownProjectPath(parts[1])
            return if (consumer == null || dependency == null) {
                null
            } else {
                ProjectDependencyObservation(consumer, dependency)
            }
        }

        val dependencies = rawProjectDependencies.mapNotNull(::parseDependency).toSet()
        val exportedDependencies = rawExportedProjectDependencies.mapNotNull(::parseDependency).toSet()
        val parsedConventions = rawModuleRoleConventions.mapNotNull { notation ->
            val parts = notation.split(ROLE_SEPARATOR)
            if (parts.size != 2) {
                failures += ArchitectureObservationFailure.MalformedModuleRoleConvention(notation)
                return@mapNotNull null
            }
            val module = moduleByPath[parts[0]]
            val convention = conventionByPluginId[parts[1]]
            if (module == null) failures += ArchitectureObservationFailure.UnknownProjectPath(parts[0])
            if (convention == null) {
                failures += ArchitectureObservationFailure.UnknownModuleRoleConvention(parts[1])
            }
            if (module == null || convention == null) null else module to convention
        }
        parsedConventions.groupingBy(Pair<ModuleId, ModuleRoleConvention>::first)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .mapTo(failures, ArchitectureObservationFailure::DuplicateModuleRoleConvention)
        val conventions = parsedConventions.toMap()
        return if (failures.isEmpty()) {
            ArchitectureObservationValidation.Valid(
                ObservedProjectGraph(
                    modules,
                    dependencies,
                    exportedDependencies,
                    ModuleRoleConventionObservation.Collected(conventions),
                ),
            )
        } else {
            ArchitectureObservationValidation.Invalid(failures.distinct())
        }
    }

    const val EDGE_SEPARATOR: String = " -> "
    const val ROLE_SEPARATOR: String = " :: "
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

    data class ObsoleteLegacyMigration(
        val migration: ValidatedLegacyMigrationEdge.Active,
    ) : ArchitectureViolation

    data class ForbiddenExportedProjectDependency(
        val dependency: ProjectDependencyObservation,
    ) : ArchitectureViolation

    data class MissingModuleRoleConvention(
        val module: ModuleId,
        val expected: ModuleRoleConvention,
    ) : ArchitectureViolation

    data class UnexpectedModuleRoleConvention(
        val module: ModuleId,
        val observed: ModuleRoleConvention,
    ) : ArchitectureViolation

    data class MismatchedModuleRoleConvention(
        val module: ModuleId,
        val expected: ModuleRoleConvention,
        val observed: ModuleRoleConvention,
    ) : ArchitectureViolation
}

sealed interface ArchitectureAdmission {
    data class Accepted(
        val retainedLegacyAllowances: Set<LegacyAllowance>,
        val retainedLegacyMigrations: Map<
            ProjectDependencyObservation,
            ValidatedLegacyMigrationEdge.Active,
            >,
    ) : ArchitectureAdmission

    data class Rejected(val violations: Set<ArchitectureViolation>) : ArchitectureAdmission

    companion object {
        /**
         * Proof transition: `(ValidatedArchitecturePolicy, ObservedArchitecture) -> ArchitectureAdmission`.
         *
         * Establishes exact active-module presence, planned-module absence, approved direct edges,
         * permitted effects, equality between observed legacy effects and their allowances, and
         * equality between observed temporary dependencies and active validated migrations.
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
            val observedTemporaryDependencies = observation.projectDependencies
                .filterNot { edge ->
                    edge.dependency in policy.modules.getValue(edge.consumer).allowedProjectDependencies
                }
                .toSet()
            val activeMigrations = policy.legacyMigrationEdges.values
                .filterIsInstance<ValidatedLegacyMigrationEdge.Active>()
                .associateBy(ValidatedLegacyMigrationEdge.Active::dependency)
            val observedLegacyViolations = buildSet {
                observedTemporaryDependencies
                    .filterNot(activeMigrations::containsKey)
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
            val obsoleteMigrations = activeMigrations
                .filterKeys { it !in observedTemporaryDependencies }
                .values
                .map(ArchitectureViolation::ObsoleteLegacyMigration)
            val forbiddenExports = observation.exportedProjectDependencies.mapNotNull { edge ->
                val consumer = policy.modules.getValue(edge.consumer)
                val dependency = policy.modules.getValue(edge.dependency)
                if (dependency.role !in consumer.boundary.allowedExportedDependencyRoles) {
                    ArchitectureViolation.ForbiddenExportedProjectDependency(edge)
                } else {
                    null
                }
            }
            val roleConventionViolations = when (
                val roleObservation = observation.moduleRoleConventions
            ) {
                ModuleRoleConventionObservation.NotCollected -> emptyList()
                is ModuleRoleConventionObservation.Collected ->
                    observation.modules.mapNotNull { moduleId ->
                        val observed = roleObservation.conventions[moduleId]
                        when (
                            val requirement =
                                policy.modules.getValue(moduleId).conventionRequirement
                        ) {
                            ModuleRoleConventionRequirement.UnmarkedLegacy ->
                                if (observed == null) {
                                    null
                                } else {
                                    ArchitectureViolation.UnexpectedModuleRoleConvention(
                                        moduleId,
                                        observed,
                                    )
                                }
                            is ModuleRoleConventionRequirement.Required -> when {
                                observed == null ->
                                    ArchitectureViolation.MissingModuleRoleConvention(
                                        moduleId,
                                        requirement.convention,
                                    )
                                observed != requirement.convention ->
                                    ArchitectureViolation.MismatchedModuleRoleConvention(
                                        moduleId,
                                        requirement.convention,
                                        observed,
                                    )
                                else -> null
                            }
                        }
                    }
            }
            val violations = (
                lifecycleViolations + unbaselined + obsolete + obsoleteMigrations +
                forbiddenExports + roleConventionViolations
                             ).toSet()
            return if (violations.isEmpty()) {
                Accepted(policy.legacyAllowances, activeMigrations)
            } else {
                Rejected(violations)
            }
        }
    }
}
