package support.architecture

object KastArchitecturePolicy {
    internal fun definition(): ArchitecturePolicyDefinition = ArchitecturePolicyDefinition(
        modules = KastCleanSlateModules.all,
    )

    /**
     * Proof transition: `ArchitecturePolicyDefinition -> ValidatedArchitecturePolicy`.
     *
     * Establishes unique module identities, complete dependency references, one complete runtime
     * composition owner, role and effect boundaries, and an acyclic clean-slate module graph.
     * [ArchitecturePolicyValidation.Invalid] is the closed expected failure. Raw graph extraction
     * is permitted only in Gradle task and JSON projection adapters.
     */
    fun validate(): ArchitecturePolicyValidation = ArchitecturePolicyValidator.validate(definition())
}

object ArchitecturePolicyValidator {
    /**
     * Proof transition: `ArchitecturePolicyDefinition -> ValidatedArchitecturePolicy`.
     *
     * Establishes that every clean-slate module is unique, every dependency terminates at a
     * declared module, role and cost direction is valid, runtime composition is complete, and the
     * graph is acyclic. [ArchitecturePolicyValidation.Invalid] retains every closed expected
     * policy failure. Raw graph extraction is permitted only at build-tool boundaries.
     */
    fun validate(definition: ArchitecturePolicyDefinition): ArchitecturePolicyValidation {
        val duplicateModules = definition.modules
            .groupingBy(ModulePolicy::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .map(ArchitecturePolicyFailure::DuplicateModule)
        val modules = definition.modules.associateBy(ModulePolicy::id)
        val moduleValidations = definition.modules.map { ModulePolicyValidator.validate(it, modules) }
        val moduleFailures = moduleValidations
            .filterIsInstance<ModulePolicyValidation.Invalid>()
            .flatMap(ModulePolicyValidation.Invalid::failures)
        val validatedModules = moduleValidations
            .filterIsInstance<ModulePolicyValidation.Valid>()
            .associate { it.module.id to it.module }
        val missingDependencies = definition.modules.flatMap { module ->
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
                .filter {
                    it.role == ModuleRole.COMPOSITION &&
                        it.id !in setOf(ModuleId.RUNTIME_COMPOSITION, ModuleId.RUNTIME_IDE_HOST)
                }
                .forEach { add(ArchitecturePolicyFailure.UnexpectedCompositionOwner(it.id)) }
            val composition = modules[ModuleId.RUNTIME_COMPOSITION]
            if (composition?.role != ModuleRole.COMPOSITION) {
                add(ArchitecturePolicyFailure.MissingRuntimeComposition)
            } else {
                val excluded = setOf(
                    ModuleId.CLI,
                    ModuleId.INDEXER,
                    ModuleId.RUNTIME_COMPOSITION,
                    ModuleId.RUNTIME_IDE_HOST,
                )
                val expectedDependencies = modules.keys - excluded
                val missing = expectedDependencies - composition.allowedProjectDependencies
                val unexpected = composition.allowedProjectDependencies - expectedDependencies
                if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
                    add(ArchitecturePolicyFailure.InvalidRuntimeCompositionDependencies(missing, unexpected))
                }
            }
        }
        val exclusiveEffectFailures = EXCLUSIVE_EFFECT_OWNERS.mapNotNull { (effect, expected) ->
            val observed = definition.modules.filter { effect in it.allowedEffects }
                .mapTo(linkedSetOf(), ModulePolicy::id)
            if (observed == expected) null else ArchitecturePolicyFailure.InvalidExclusiveEffectOwners(
                effect,
                expected,
                observed,
            )
        }
        val failures = buildList {
            addAll(duplicateModules)
            addAll(missingDependencies)
            addAll(moduleFailures)
            addAll(compositionFailures)
            addAll(exclusiveEffectFailures)
            moduleSort.cycle?.let { add(ArchitecturePolicyFailure.ModuleDependencyCycle(it)) }
        }
        return if (failures.isEmpty()) {
            ArchitecturePolicyValidation.Valid(ValidatedArchitecturePolicy(validatedModules, moduleSort.order))
        } else {
            ArchitecturePolicyValidation.Invalid(failures)
        }
    }

    private val EXCLUSIVE_EFFECT_OWNERS = mapOf(
        ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY to
            setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
        ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY to setOf(ModuleId.IDE_PLUGIN),
        ForbiddenEffect.UDS_BIND to setOf(ModuleId.IDE_PLUGIN),
        ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE to setOf(ModuleId.IDE_PLUGIN),
        ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY to setOf(ModuleId.TOPOLOGY_BUILD),
        ForbiddenEffect.TOPOLOGY_PUBLICATION to setOf(ModuleId.EVIDENCE_SQLITE),
    )

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
