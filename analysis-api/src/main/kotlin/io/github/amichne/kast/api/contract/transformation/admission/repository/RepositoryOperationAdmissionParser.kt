package io.github.amichne.kast.api.contract.transformation.admission.repository

internal class RepositoryOperationAdmissionParser(
    private val rawInput: RawRepositoryOperationInput,
    private val stabilityCheckpoint: SourceStateStabilityCheckpoint = SourceStateStabilityCheckpoint.NO_OP,
    private val contentReadCheckpoint: SourceContentReadCheckpoint = SourceContentReadCheckpoint.NO_OP,
    private val authorityReadCheckpoint: GitAuthorityReadCheckpoint = GitAuthorityReadCheckpoint.NO_OP,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun parse(): RepositoryOperationAdmission.Result = try {
        val rawBounds = rawInput.resourceBounds?.copy()
            ?: reject(RepositoryOperationRejection.ResourceBoundMissing(ResourceBoundKind.TIME))
        val resourceBounds = parseResourceBounds(rawBounds)
        parseAdmitted(snapshotRawInput(rawInput), resourceBounds)
    } catch (rejected: AdmissionParseRejected) {
        RepositoryOperationAdmission.Result.Rejected(rejected.rejection)
    }

    private fun snapshotRawInput(input: RawRepositoryOperationInput): RawRepositoryOperationInput = input.copy(
        repository = input.repository?.copy(),
        sourceState = input.sourceState?.let { sourceState ->
            sourceState.copy(
                inputs = snapshotList<RawSourceInput>(
                    sourceState.inputs,
                    RepositoryOperationRejection.SourceStateEvidenceMissing(
                        SourceStateEvidenceKind.INVENTORY,
                        null,
                    ),
                )?.map(RawSourceInput::copy),
            )
        },
        buildOwnership = when (val buildOwnership = input.buildOwnership) {
            null -> null
            RawBuildOwnershipEvidence.Unavailable -> RawBuildOwnershipEvidence.Unavailable
            is RawBuildOwnershipEvidence.Available -> buildOwnership.copy(
                compilationUnits = snapshotList<RawCompilationUnitInput>(
                    buildOwnership.compilationUnits,
                    incomplete(SemanticConfigurationField.COMPILATION_UNITS, null),
                )?.map(::snapshotCompilationUnit),
            )
        },
        scope = snapshotList<RawScopeSelector>(
            input.scope,
            RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SCOPE),
        )?.map { selector ->
            when (selector) {
                is RawScopeSelector.Module -> selector.copy()
                is RawScopeSelector.SourceSet -> selector.copy()
                is RawScopeSelector.Declaration -> selector.copy()
                is RawScopeSelector.Family -> selector.copy()
            }
        },
        resourceBounds = input.resourceBounds?.copy(),
    )

    private fun snapshotCompilationUnit(input: RawCompilationUnitInput): RawCompilationUnitInput {
        val ownerId = input.ownerId
        return input.copy(
            sourceRoots = snapshotSet<String>(
                input.sourceRoots,
                incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId),
            ),
            generatedSourceRoots = snapshotSet<String>(
                input.generatedSourceRoots,
                incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId),
            ),
            declarations = snapshotList<RawOwnedDeclarationInput>(
                input.declarations,
                incomplete(SemanticConfigurationField.DECLARATIONS, ownerId),
            )?.map(RawOwnedDeclarationInput::copy),
            families = snapshotSet<String>(
                input.families,
                incomplete(SemanticConfigurationField.FAMILIES, ownerId),
            ),
            sourceSetRelationships = snapshotSet<RawSourceSetRelationshipInput>(
                input.sourceSetRelationships,
                incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, ownerId),
            )?.map(RawSourceSetRelationshipInput::copy)?.toSet(),
            compiler = input.compiler?.let { compiler -> snapshotCompiler(compiler, ownerId) },
        )
    }

    private fun snapshotCompiler(
        input: RawCompilerInput,
        ownerId: String?,
    ): RawCompilerInput = input.copy(
        languageSettings = snapshotMap<String, String>(
            input.languageSettings,
            incomplete(SemanticConfigurationField.LANGUAGE_SETTINGS, ownerId),
        ),
        compilerImplementation = input.compilerImplementation?.copy(),
        toolchain = input.toolchain?.copy(),
        compilerOptions = snapshotList<RawCompilerOptionInput>(
            input.compilerOptions,
            incomplete(SemanticConfigurationField.COMPILER_OPTIONS, ownerId),
        )?.map(RawCompilerOptionInput::copy),
        resolvedDependencies = snapshotList<RawResolvedArtifactInput>(
            input.resolvedDependencies,
            incomplete(SemanticConfigurationField.DEPENDENCIES, ownerId),
        )?.map(RawResolvedArtifactInput::copy),
        compilerPlugins = snapshotList<RawCompilerPluginInput>(
            input.compilerPlugins,
            incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId),
        )?.map { plugin ->
            plugin.copy(
                classpath = snapshotList<RawResolvedArtifactInput>(
                    plugin.classpath,
                    incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId),
                )?.map(RawResolvedArtifactInput::copy),
                options = snapshotList<RawCompilerOptionInput>(
                    plugin.options,
                    incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId),
                )?.map(RawCompilerOptionInput::copy),
            )
        },
    )

    private inline fun <reified Value : Any> snapshotList(
        values: List<*>?,
        rejection: RepositoryOperationRejection,
    ): List<Value>? = values?.let { source ->
        snapshotCollection(rejection) {
            val expectedSize = source.size
            val result = ArrayList<Value>(expectedSize)
            repeat(expectedSize) { index ->
                result.add(source[index] as? Value ?: reject(rejection))
            }
            if (source.size != expectedSize) reject(rejection)
            result
        }
    }

    private inline fun <reified Value : Any> snapshotSet(
        values: Set<*>?,
        rejection: RepositoryOperationRejection,
    ): Set<Value>? = values?.let { source ->
        snapshotCollection(rejection) {
            val expectedSize = source.size
            val result = LinkedHashSet<Value>(expectedSize)
            source.forEach { rawValue -> result.add(rawValue as? Value ?: reject(rejection)) }
            if (source.size != expectedSize || result.size != expectedSize) reject(rejection)
            result
        }
    }

    private inline fun <reified Key : Any, reified Value : Any> snapshotMap(
        values: Map<*, *>?,
        rejection: RepositoryOperationRejection,
    ): Map<Key, Value>? = values?.let { source ->
        snapshotCollection(rejection) {
            val expectedSize = source.size
            val result = LinkedHashMap<Key, Value>(expectedSize)
            source.entries.forEach { entry ->
                result[entry.key as? Key ?: reject(rejection)] = entry.value as? Value ?: reject(rejection)
            }
            if (source.size != expectedSize || result.size != expectedSize) reject(rejection)
            result
        }
    }

    private inline fun <Value> snapshotCollection(
        rejection: RepositoryOperationRejection,
        snapshot: () -> Value,
    ): Value = try {
        snapshot()
    } catch (rejected: AdmissionParseRejected) {
        throw rejected
    } catch (_: RuntimeException) {
        reject(rejection)
    }

    private fun parseAdmitted(
        input: RawRepositoryOperationInput,
        resourceBounds: EstablishedResourceBounds,
    ): RepositoryOperationAdmission.Result.Admitted {
        val rawRepository = input.repository
            ?: reject(RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.REPOSITORY))
        val rawSourceState = input.sourceState
            ?: reject(RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SOURCE_STATE))
        val rawBuildOwnership = input.buildOwnership
            ?: reject(RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.BUILD_OWNERSHIP))
        val rawScope = input.scope
            ?: reject(RepositoryOperationRejection.ApplicableInputMissing(ApplicableInputKind.SCOPE))
        val repository = RepositoryAdmissionRepository(authorityReadCheckpoint)
        val canonicalRoot = repository.parseRoot(rawRepository)
        val compilationUnits = RepositoryBuildAdmission(repository).parse(rawBuildOwnership, canonicalRoot)
        val sourceState = RepositorySourceStateAdmission(
            repository,
            stabilityCheckpoint,
            contentReadCheckpoint,
            nanoTime,
        ).parse(rawSourceState, canonicalRoot, compilationUnits, resourceBounds)
        val parsedScope = parseScope(rawScope, compilationUnits)
        val repositoryState = AdmittedRepositoryState.create(
            canonicalRoot = canonicalRoot,
            sourceState = sourceState,
            semanticConfiguration = parsedScope.semanticConfiguration,
            compilationUnits = compilationUnits,
        )
        return RepositoryOperationAdmission.Result.Admitted(
            AdmittedRepositoryOperation.create(repositoryState, parsedScope.scope, resourceBounds),
        )
    }

    private fun parseScope(
        selectors: List<RawScopeSelector>,
        units: List<AdmittedCompilationUnit>,
    ): ParsedScope {
        if (selectors.isEmpty()) reject(RepositoryOperationRejection.ScopeResolvesToNothing)
        val selected = linkedMapOf<String, AdmittedCompilationUnit>()
        selectors.forEach { selector ->
            val matches = when (selector) {
                is RawScopeSelector.Module -> when (val module = resolveModuleReference(selector.moduleName, units)) {
                    ModuleReferenceResolution.Unknown -> emptyList()
                    is ModuleReferenceResolution.Ambiguous -> reject(
                        RepositoryOperationRejection.AmbiguousScope(selector, module.moduleIdentities),
                    )
                    is ModuleReferenceResolution.Resolved -> module.units
                }

                is RawScopeSelector.SourceSet -> {
                    val sourceSetName = selector.sourceSetName?.takeIf(String::isNotBlank)
                    if (sourceSetName == null) {
                        emptyList()
                    } else {
                        when (val module = resolveModuleReference(selector.moduleName, units)) {
                            ModuleReferenceResolution.Unknown -> emptyList()
                            is ModuleReferenceResolution.Ambiguous -> reject(
                                RepositoryOperationRejection.AmbiguousScope(selector, module.moduleIdentities),
                            )
                            is ModuleReferenceResolution.Resolved -> module.units.filter { unit ->
                                unit.sourceSetName.value == sourceSetName
                            }
                        }
                    }
                }

                is RawScopeSelector.Declaration -> selector.fullyQualifiedName
                    ?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        val occurrences = units.flatMap { unit ->
                            unit.declarations.filter { it.name.value == name }.map { declaration -> unit to declaration }
                        }
                        if (occurrences.size > 1) {
                            reject(
                                RepositoryOperationRejection.AmbiguousScope(
                                    selector,
                                    occurrences.map { (unit, declaration) ->
                                        "${unit.id.value}:${declaration.path.value}"
                                    }.sorted(),
                                ),
                            )
                        }
                        occurrences.map { (unit, _) -> unit }
                    }.orEmpty()

                is RawScopeSelector.Family -> selector.familyName
                    ?.takeIf(String::isNotBlank)
                    ?.let { name -> units.filter { unit -> unit.families.any { it.value == name } } }
                    .orEmpty()
            }
            if (matches.isEmpty()) reject(RepositoryOperationRejection.UnknownScope(selector))
            val singularSelector = selector is RawScopeSelector.SourceSet ||
                selector is RawScopeSelector.Declaration || selector is RawScopeSelector.Family
            if (singularSelector && matches.size > 1) {
                reject(
                    RepositoryOperationRejection.AmbiguousScope(
                        selector,
                        matches.map { unit -> unit.id.value }.sorted(),
                    ),
                )
            }
            relationshipClosure(matches, units).forEach { unit -> selected[unit.id.value] = unit }
        }
        if (selected.isEmpty()) reject(RepositoryOperationRejection.ScopeResolvesToNothing)
        val selectedUnits = selected.values.sortedBy { unit -> unit.id.value }
        val configurations = selectedUnits.map { unit -> unit.semanticConfiguration.identity }.distinct()
        if (configurations.size != 1) {
            reject(
                RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                    selectedUnits.map { unit -> unit.id.value },
                ),
            )
        }
        return ParsedScope(
            ResolvedRepositoryScope.create(selectedUnits),
            selectedUnits.first().semanticConfiguration,
        )
    }

    private fun resolveModuleReference(
        rawReference: String?,
        units: List<AdmittedCompilationUnit>,
    ): ModuleReferenceResolution {
        val reference = rawReference?.takeIf(String::isNotBlank) ?: return ModuleReferenceResolution.Unknown
        val exactMatches = units.filter { unit -> unit.moduleIdentity.value == reference }
        val displayNameMatches = units.filter { unit -> unit.moduleName.value == reference }
        val matches = (exactMatches + displayNameMatches).distinctBy { unit -> unit.id }
        if (matches.isEmpty()) return ModuleReferenceResolution.Unknown
        val moduleIdentities = matches.map { unit -> unit.moduleIdentity.value }.distinct().sorted()
        return if (moduleIdentities.size == 1) {
            ModuleReferenceResolution.Resolved(matches)
        } else {
            ModuleReferenceResolution.Ambiguous(moduleIdentities)
        }
    }

    private fun relationshipClosure(
        roots: List<AdmittedCompilationUnit>,
        units: List<AdmittedCompilationUnit>,
    ): List<AdmittedCompilationUnit> {
        val unitsById = units.associateBy { unit -> unit.id }
        val closure = linkedMapOf<CompilationUnitId, AdmittedCompilationUnit>()
        val pending = ArrayDeque<AdmittedCompilationUnit>()
        pending.addAll(roots)
        while (pending.isNotEmpty()) {
            val unit = pending.removeFirst()
            if (closure.putIfAbsent(unit.id, unit) != null) continue
            unit.sourceSetRelationships.forEach { relationship ->
                pending.addLast(requireNotNull(unitsById[relationship.targetCompilationUnitId]))
            }
        }
        return closure.values.sortedBy { unit -> unit.id.value }
    }

    private data class ParsedScope(
        val scope: ResolvedRepositoryScope,
        val semanticConfiguration: CoherentSemanticConfiguration,
    )

    private sealed interface ModuleReferenceResolution {
        data object Unknown : ModuleReferenceResolution
        data class Ambiguous(val moduleIdentities: List<String>) : ModuleReferenceResolution
        data class Resolved(val units: List<AdmittedCompilationUnit>) : ModuleReferenceResolution
    }
}

internal fun reject(rejection: RepositoryOperationRejection): Nothing =
    throw AdmissionParseRejected(rejection)

internal class AdmissionParseRejected(
    val rejection: RepositoryOperationRejection,
) : RuntimeException(null, null, false, false)
