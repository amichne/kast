package support.architecture

import support.architecture.baseline.KastArchitectureLegacyBaseline
import support.architecture.baseline.KastArchitectureLegacyImplementationBridges
import support.architecture.baseline.KastArchitectureLegacyMigrations
import support.architecture.process.KastMutationRuntimeProcesses
import support.architecture.process.MutationRuntimeProcessPolicy
import support.architecture.process.MutationRuntimeTopologyValidation
import support.architecture.process.MutationRuntimeTopologyValidator

object KastArchitecturePolicy {
    internal fun definition(): ArchitecturePolicyDefinition = ArchitecturePolicyDefinition(
        modules = KastPlatformModules.all,
        mutationDeliveryTasks = KastMutationDelivery.all,
        mutationRuntimeProcesses = KastMutationRuntimeProcesses.all,
        legacyAllowances = KastArchitectureLegacyBaseline.all,
        legacyMigrationEdges = KastArchitectureLegacyMigrations.all,
        legacyImplementationBridges = KastArchitectureLegacyImplementationBridges.all,
    )

    /**
     * Proof transition: `ArchitecturePolicyDefinition -> ValidatedArchitecturePolicy`.
     *
     * Establishes unique identities, complete references, and acyclic platform-module, mutation
     * runtime, and mutation delivery graphs, including exact alternative apply-lane and recovery
     * topology. [ArchitecturePolicyValidation.Invalid] is the closed expected failure. Raw graph
     * extraction is permitted only in Gradle task and JSON projection adapters.
     */
    fun validate(): ArchitecturePolicyValidation = ArchitecturePolicyValidator.validate(definition())
}

object ArchitecturePolicyValidator {
    /**
     * Proof transition: `ArchitecturePolicyDefinition -> ValidatedArchitecturePolicy`.
     *
     * Establishes that every node is unique, module direction has one complete composition owner,
     * dependencies and effects satisfy independent role and cost boundaries, every reference
     * terminates at a declared node, every legacy allowance is exact, and every temporary edge is
     * exact, non-permanent, and bound to an open retirement task with the required owner.
     * [ArchitecturePolicyValidation.Invalid] retains every closed expected policy failure. Raw
     * graph extraction is permitted only at build-tool boundaries.
     */
    fun validate(definition: ArchitecturePolicyDefinition): ArchitecturePolicyValidation {
        val duplicateModules = definition.modules
            .groupingBy(ModulePolicy::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateModule)
        val modules = definition.modules.associateBy(ModulePolicy::id)
        val moduleValidations = definition.modules.map { module ->
            ModulePolicyValidator.validate(module, modules)
        }
        val moduleFailures = moduleValidations
            .filterIsInstance<ModulePolicyValidation.Invalid>()
            .flatMap(ModulePolicyValidation.Invalid::failures)
        val validatedModules = moduleValidations
            .filterIsInstance<ModulePolicyValidation.Valid>()
            .associate { validation -> validation.module.id to validation.module }
        val missingModuleDependencies = definition.modules.flatMap { module ->
            module.allowedProjectDependencies
                .filterNot(modules::containsKey)
                .map { ArchitecturePolicyFailure.MissingModuleDependency(module.id, it) }
        }
        val moduleSort = topologicalOrder(
            nodes = modules.keys,
            dependencies = { modules.getValue(it).allowedProjectDependencies.filter(modules::containsKey).toSet() },
        )
        val compositionFailures = buildList {
            definition.modules
                .filter { module ->
                    module.role == ModuleRole.COMPOSITION &&
                    module.id != ModuleId.RUNTIME_COMPOSITION
                }
                .forEach { module ->
                    add(ArchitecturePolicyFailure.UnexpectedCompositionOwner(module.id))
                }
            val composition = modules[ModuleId.RUNTIME_COMPOSITION]
            if (composition?.role != ModuleRole.COMPOSITION) {
                add(ArchitecturePolicyFailure.MissingRuntimeComposition)
            } else {
                val expectedDependencies = modules.values
                    .filter { module ->
                        module.id != ModuleId.RUNTIME_COMPOSITION &&
                        module.role != ModuleRole.LEGACY_HOST &&
                        module.lifecycle != ModuleLifecycle.RETIRED
                    }
                    .mapTo(linkedSetOf(), ModulePolicy::id)
                val missing = expectedDependencies - composition.allowedProjectDependencies
                val unexpected = composition.allowedProjectDependencies - expectedDependencies
                if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
                    add(
                        ArchitecturePolicyFailure.InvalidRuntimeCompositionDependencies(
                            missing = missing,
                            unexpected = unexpected,
                        ),
                    )
                }
            }
        }

        val duplicateMutationDeliveryTasks = definition.mutationDeliveryTasks
            .groupingBy(MutationDeliveryTaskPolicy::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateMutationDeliveryTask)
        val mutationDeliveryTasks = definition.mutationDeliveryTasks.associateBy(MutationDeliveryTaskPolicy::id)
        val missingMutationDeliveryDependencies = definition.mutationDeliveryTasks.flatMap { task ->
            task.dependsOn
                .filterNot(mutationDeliveryTasks::containsKey)
                .map { ArchitecturePolicyFailure.MissingMutationDeliveryDependency(task.id, it) }
        }
        val missingMutationDeliveryOwnerModules = definition.mutationDeliveryTasks.flatMap { task ->
            task.owner.moduleIds()
                .filterNot(modules::containsKey)
                .map { ArchitecturePolicyFailure.MissingMutationDeliveryOwnerModule(task.id, it) }
        }
        val mutationDeliverySort = topologicalOrder(
            nodes = mutationDeliveryTasks.keys,
            dependencies = {
                mutationDeliveryTasks.getValue(it).dependsOn.filter(mutationDeliveryTasks::containsKey)
                    .toSet()
            },
        )
        val duplicateLegacyMigrations = definition.legacyMigrationEdges
            .groupingBy(LegacyMigrationEdgePolicy::dependency)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateLegacyMigration)
        val migrationValidations = definition.legacyMigrationEdges.map { migration ->
            LegacyMigrationEdgeValidator.validate(migration, modules, mutationDeliveryTasks)
        }
        val migrationFailures = migrationValidations
            .filterIsInstance<LegacyMigrationEdgeValidation.Invalid>()
            .flatMap(LegacyMigrationEdgeValidation.Invalid::failures)
        val validatedMigrations = migrationValidations
            .filterIsInstance<LegacyMigrationEdgeValidation.Valid>()
            .associate { validation -> validation.migration.dependency to validation.migration }
        val duplicateLegacyImplementationBridges = definition.legacyImplementationBridges
            .groupingBy(LegacyImplementationBridgePolicy::dependency)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateLegacyImplementationBridge)
        val bridgeValidations = definition.legacyImplementationBridges.map { bridge ->
            LegacyImplementationBridgeValidator.validate(bridge, modules, mutationDeliveryTasks)
        }
        val bridgeFailures = bridgeValidations
            .filterIsInstance<LegacyImplementationBridgeValidation.Invalid>()
            .flatMap(LegacyImplementationBridgeValidation.Invalid::failures)
        val validatedBridges = bridgeValidations
            .filterIsInstance<LegacyImplementationBridgeValidation.Valid>()
            .associate { validation -> validation.bridge.dependency to validation.bridge }
        val duplicateAllowances = definition.legacyAllowances
            .groupingBy(LegacyAllowance::violation)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateLegacyAllowance)
        val missingRetirementTasks = definition.legacyAllowances
            .filterNot { mutationDeliveryTasks.containsKey(it.retirementTask) }
            .map(ArchitecturePolicyFailure::MissingLegacyRetirementTask)
        val missingLegacyModules = definition.legacyAllowances.flatMap { allowance ->
            allowance.referencedModules()
                .filterNot(modules::containsKey)
                .map { ArchitecturePolicyFailure.MissingLegacyAllowanceModule(allowance, it) }
        }
        val nonExactAllowances = definition.legacyAllowances
            .filterNot { it.isExact() }
            .map(ArchitecturePolicyFailure::NonExactLegacyAllowance)
        val dependencyAllowances = definition.legacyAllowances
            .filter { it.violation is LegacyViolationKey.UnapprovedProjectDependency }
            .map(ArchitecturePolicyFailure::DependencyAllowanceRequiresMigration)
        val duplicateMutationRuntimeProcesses = definition.mutationRuntimeProcesses
            .groupingBy(MutationRuntimeProcessPolicy::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateMutationRuntimeProcess)
        val mutationRuntimeProcesses = definition.mutationRuntimeProcesses.associateBy(MutationRuntimeProcessPolicy::id)
        val mutationRuntimeTopologyValidation =
            MutationRuntimeTopologyValidator.validate(definition.mutationRuntimeProcesses)
        val mutationRuntimeTopologyFailures = when (mutationRuntimeTopologyValidation) {
            is MutationRuntimeTopologyValidation.Valid -> emptySet()
            is MutationRuntimeTopologyValidation.Invalid ->
                mutationRuntimeTopologyValidation.failures.mapTo(linkedSetOf()) {
                    ArchitecturePolicyFailure.InvalidMutationRuntimeTopology(it)
                }
        }
        val missingMutationRuntimeDependencies = definition.mutationRuntimeProcesses.flatMap { process ->
            process.admission.orderingDependencies
                .filterNot(mutationRuntimeProcesses::containsKey)
                .map { ArchitecturePolicyFailure.MissingMutationRuntimeProcessDependency(process.id, it) }
        }
        val missingMutationRuntimeOwners = definition.mutationRuntimeProcesses.flatMap { process ->
            process.owners
                .filterNot(modules::containsKey)
                .map { ArchitecturePolicyFailure.MissingMutationRuntimeProcessOwner(process.id, it) }
        }
        val mutationRuntimeSort = topologicalOrder(
            nodes = mutationRuntimeProcesses.keys,
            dependencies = {
                mutationRuntimeProcesses.getValue(it).admission.orderingDependencies
                    .filter(mutationRuntimeProcesses::containsKey)
                    .toSet()
            },
        )

        val failures = buildList {
            addAll(duplicateModules)
            addAll(missingModuleDependencies)
            addAll(moduleFailures)
            addAll(compositionFailures)
            moduleSort.cycle?.let { add(ArchitecturePolicyFailure.ModuleDependencyCycle(it)) }
            addAll(duplicateMutationDeliveryTasks)
            addAll(missingMutationDeliveryDependencies)
            addAll(missingMutationDeliveryOwnerModules)
            mutationDeliverySort.cycle?.let {
                add(ArchitecturePolicyFailure.MutationDeliveryDependencyCycle(it))
            }
            addAll(duplicateLegacyMigrations)
            addAll(migrationFailures)
            addAll(duplicateLegacyImplementationBridges)
            addAll(bridgeFailures)
            addAll(duplicateAllowances)
            addAll(missingRetirementTasks)
            addAll(missingLegacyModules)
            addAll(nonExactAllowances)
            addAll(dependencyAllowances)
            addAll(duplicateMutationRuntimeProcesses)
            addAll(mutationRuntimeTopologyFailures)
            addAll(missingMutationRuntimeDependencies)
            addAll(missingMutationRuntimeOwners)
            mutationRuntimeSort.cycle?.let {
                add(ArchitecturePolicyFailure.MutationRuntimeProcessDependencyCycle(it))
            }
        }
        return when (mutationRuntimeTopologyValidation) {
            is MutationRuntimeTopologyValidation.Valid ->
                if (failures.isEmpty()) {
                    ArchitecturePolicyValidation.Valid(
                        ValidatedArchitecturePolicy(
                            modules = validatedModules,
                            mutationDeliveryTasks = mutationDeliveryTasks,
                            mutationRuntimeTopology = mutationRuntimeTopologyValidation.topology,
                            moduleOrder = moduleSort.order,
                            mutationDeliveryOrder = mutationDeliverySort.order,
                            mutationRuntimeProcessOrder = mutationRuntimeSort.order,
                            legacyAllowances = definition.legacyAllowances.toSet(),
                            legacyMigrationEdges = validatedMigrations,
                            legacyImplementationBridges = validatedBridges,
                        ),
                    )
                } else {
                    ArchitecturePolicyValidation.Invalid(failures)
                }
            is MutationRuntimeTopologyValidation.Invalid ->
                ArchitecturePolicyValidation.Invalid(failures)
        }
    }

    private fun MutationDeliveryOwner.moduleIds(): Set<ModuleId> = when (this) {
        MutationDeliveryOwner.BuildLogic,
        MutationDeliveryOwner.EndToEndCorpus,
            -> emptySet()
        is MutationDeliveryOwner.Modules -> ids
    }

    private fun LegacyAllowance.referencedModules(): Set<ModuleId> = when (val key = violation) {
        is LegacyViolationKey.UnapprovedProjectDependency ->
            setOf(key.dependency.consumer, key.dependency.dependency)
        is LegacyViolationKey.ForbiddenEffectUse -> setOf(key.observation.module)
    }

    private fun LegacyAllowance.isExact(): Boolean = when (val key = violation) {
        is LegacyViolationKey.UnapprovedProjectDependency -> true
        is LegacyViolationKey.ForbiddenEffectUse -> with(key.observation) {
            listOf(
                caller.owner.internalName,
                caller.name.value,
                caller.descriptor.value,
                target.owner.internalName,
                target.name.value,
                target.descriptor.value,
            ).none { it.containsPatternMarker() }
        }
    }

    private fun String.containsPatternMarker(): Boolean =
        '*' in this || '?' in this || "..." in this

    private fun <T> topologicalOrder(
        nodes: Set<T>,
        dependencies: (T) -> Set<T>,
    ): TopologicalOrder<T> {
        val permanent = mutableSetOf<T>()
        val temporary = linkedSetOf<T>()
        val order = mutableListOf<T>()
        var cycle: Set<T>? = null

        fun visit(node: T) {
            if (node in permanent || cycle != null) return
            if (!temporary.add(node)) {
                cycle = temporary.dropWhile { it != node }.toSet() + node
                return
            }
            dependencies(node).filter(nodes::contains).forEach(::visit)
            temporary.remove(node)
            permanent.add(node)
            order.add(node)
        }

        nodes.forEach(::visit)
        return TopologicalOrder(order, cycle)
    }

    private data class TopologicalOrder<T>(
        val order: List<T>,
        val cycle: Set<T>?,
    )
}
