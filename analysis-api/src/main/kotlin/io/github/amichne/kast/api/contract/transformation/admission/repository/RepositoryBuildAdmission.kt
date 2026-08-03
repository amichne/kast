package io.github.amichne.kast.api.contract.transformation.admission.repository

internal class RepositoryBuildAdmission(
    private val repository: RepositoryAdmissionRepository,
) {
    fun parse(
        evidence: RawBuildOwnershipEvidence,
        root: CanonicalRepositoryRoot,
    ): List<AdmittedCompilationUnit> {
        val rawUnits = when (evidence) {
            RawBuildOwnershipEvidence.Unavailable ->
                reject(RepositoryOperationRejection.BuildOwnershipEvidenceUnavailable)

            is RawBuildOwnershipEvidence.Available -> evidence.compilationUnits
                ?: reject(incomplete(SemanticConfigurationField.COMPILATION_UNITS, null))
        }
        if (rawUnits.isEmpty()) {
            reject(incomplete(SemanticConfigurationField.COMPILATION_UNITS, null))
        }
        val units = rawUnits.map { rawUnit -> parseCompilationUnit(rawUnit, root) }
        val duplicateId = units.groupBy { unit -> unit.id.value }
            .entries
            .firstOrNull { (_, matches) -> matches.size > 1 }
            ?.key
        if (duplicateId != null) {
            reject(incomplete(SemanticConfigurationField.OWNER_ID, duplicateId))
        }
        val conflictingModuleIdentity = units.groupBy { unit -> unit.moduleIdentity }
            .entries
            .firstOrNull { (_, matches) -> matches.map { unit -> unit.moduleName }.distinct().size > 1 }
            ?.key
        if (conflictingModuleIdentity != null) {
            reject(
                incomplete(
                    SemanticConfigurationField.MODULE_IDENTITY,
                    conflictingModuleIdentity.value,
                ),
            )
        }
        val unitIds = units.map { unit -> unit.id }.toSet()
        units.forEach { unit ->
            val invalidRelationship = unit.sourceSetRelationships.firstOrNull { relationship ->
                relationship.targetCompilationUnitId == unit.id ||
                    relationship.targetCompilationUnitId !in unitIds
            }
            if (invalidRelationship != null) {
                reject(incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, unit.id.value))
            }
        }
        val unitsById = units.associateBy { unit -> unit.id }
        val visited = mutableSetOf<CompilationUnitId>()
        val active = linkedSetOf<CompilationUnitId>()
        fun findCycle(unit: AdmittedCompilationUnit): CompilationUnitId? {
            if (!active.add(unit.id)) return unit.id
            if (unit.id in visited) {
                active.remove(unit.id)
                return null
            }
            unit.sourceSetRelationships.forEach { relationship ->
                val target = requireNotNull(unitsById[relationship.targetCompilationUnitId])
                val cycle = findCycle(target)
                if (cycle != null) return cycle
            }
            active.remove(unit.id)
            visited.add(unit.id)
            return null
        }
        units.forEach { unit ->
            val cycle = findCycle(unit)
            if (cycle != null) {
                reject(incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, cycle.value))
            }
        }
        return units.sortedBy { unit -> unit.id.value }
    }

    private fun parseCompilationUnit(
        input: RawCompilationUnitInput,
        root: CanonicalRepositoryRoot,
    ): AdmittedCompilationUnit {
        val ownerId = requiredBuildValue(input.ownerId, SemanticConfigurationField.OWNER_ID, input.ownerId)
        val moduleIdentity = requiredBuildValue(
            input.moduleIdentity,
            SemanticConfigurationField.MODULE_IDENTITY,
            ownerId,
        )
        val moduleName = requiredBuildValue(input.moduleName, SemanticConfigurationField.MODULE, ownerId)
        val sourceSetName = requiredBuildValue(input.sourceSetName, SemanticConfigurationField.SOURCE_SET, ownerId)
        val variantName = requiredBuildValue(input.variantName, SemanticConfigurationField.VARIANT, ownerId)
        val rawSourceRoots = input.sourceRoots
            ?: reject(incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId))
        if (rawSourceRoots.isEmpty()) {
            reject(incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId))
        }
        val sourceRoots = rawSourceRoots.map { rawPath ->
            if (rawPath.isBlank()) {
                reject(incomplete(SemanticConfigurationField.SOURCE_ROOTS, ownerId))
            }
            repository.parsePath(root, rawPath)
        }.distinct().sortedBy(RepositoryRelativePath::value)
        val rawGeneratedSourceRoots = input.generatedSourceRoots
            ?: reject(incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId))
        val generatedSourceRoots = rawGeneratedSourceRoots.map { rawPath ->
            if (rawPath.isBlank()) {
                reject(incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId))
            }
            val generatedRoot = repository.parsePath(root, rawPath)
            if (sourceRoots.none { sourceRoot -> generatedRoot.isWithin(sourceRoot) }) {
                reject(incomplete(SemanticConfigurationField.GENERATED_SOURCE_ROOTS, ownerId))
            }
            generatedRoot
        }.distinct().sortedBy(RepositoryRelativePath::value)
        val rawDeclarations = input.declarations
            ?: reject(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
        val declarations = rawDeclarations.map { declaration ->
            val name = declaration.fullyQualifiedName?.takeIf(String::isNotBlank)
                ?: reject(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
            val rawPath = declaration.path?.takeIf(String::isNotBlank)
                ?: reject(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
            val path = repository.parsePath(root, rawPath)
            if (sourceRoots.none { sourceRoot -> path.isWithin(sourceRoot) }) {
                reject(incomplete(SemanticConfigurationField.DECLARATIONS, ownerId))
            }
            AdmittedDeclaration.create(
                name = AdmittedDeclarationName.fromValidated(name),
                path = path,
            )
        }.sortedWith(compareBy({ declaration -> declaration.name.value }, { declaration -> declaration.path.value }))
        val rawFamilies = input.families
            ?: reject(incomplete(SemanticConfigurationField.FAMILIES, ownerId))
        if (rawFamilies.any(String::isBlank)) {
            reject(incomplete(SemanticConfigurationField.FAMILIES, ownerId))
        }
        val rawRelationships = input.sourceSetRelationships
            ?: reject(incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, ownerId))
        val relationships = rawRelationships.map { relationship ->
            val kind = relationship.kind
                ?: reject(incomplete(SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS, ownerId))
            val target = requiredBuildValue(
                relationship.targetCompilationUnitId,
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                ownerId,
            )
            SourceSetRelationship.create(
                kind = kind,
                targetCompilationUnitId = CompilationUnitId.fromValidated(target),
            )
        }.toSet()
        val compiler = input.compiler
            ?: reject(incomplete(SemanticConfigurationField.COMPILER, ownerId))
        val configuration = parseSemanticConfiguration(compiler, variantName, ownerId)
        return AdmittedCompilationUnit.create(
            id = CompilationUnitId.fromValidated(ownerId),
            moduleIdentity = AdmittedModuleIdentity.fromValidated(moduleIdentity),
            moduleName = AdmittedModuleName.fromValidated(moduleName),
            sourceSetName = AdmittedSourceSetName.fromValidated(sourceSetName),
            variantName = AdmittedVariantName.fromValidated(variantName),
            sourceRoots = sourceRoots,
            generatedSourceRoots = generatedSourceRoots,
            declarations = declarations,
            families = rawFamilies.map(SemanticFamilyName::fromValidated).toSet(),
            sourceSetRelationships = relationships,
            semanticConfiguration = configuration,
        )
    }

    private fun parseSemanticConfiguration(
        input: RawCompilerInput,
        variantName: String,
        ownerId: String,
    ): CoherentSemanticConfiguration {
        val compilerVersion = requiredBuildValue(
            input.compilerVersion,
            SemanticConfigurationField.COMPILER_VERSION,
            ownerId,
        )
        val languageVersion = requiredBuildValue(
            input.languageVersion,
            SemanticConfigurationField.LANGUAGE_VERSION,
            ownerId,
        )
        val apiVersion = requiredBuildValue(
            input.apiVersion,
            SemanticConfigurationField.API_VERSION,
            ownerId,
        )
        val rawSettings = input.languageSettings
            ?: reject(incomplete(SemanticConfigurationField.LANGUAGE_SETTINGS, ownerId))
        if (rawSettings.any { (key, value) -> key.isBlank() || value.isBlank() }) {
            reject(incomplete(SemanticConfigurationField.LANGUAGE_SETTINGS, ownerId))
        }
        val compilerImplementation = parseResolvedArtifact(
            input.compilerImplementation,
            SemanticConfigurationField.COMPILER_IMPLEMENTATION,
            ownerId,
        )
        val toolchain = parseCompilerToolchain(input.toolchain, ownerId)
        val rawOptions = input.compilerOptions
            ?: reject(incomplete(SemanticConfigurationField.COMPILER_OPTIONS, ownerId))
        val compilerOptions = rawOptions.map { option ->
            CompilerOptionToken.fromValidated(
                requiredBuildValue(option.token, SemanticConfigurationField.COMPILER_OPTIONS, ownerId),
            )
        }
        val rawDependencies = input.resolvedDependencies
            ?: reject(incomplete(SemanticConfigurationField.DEPENDENCIES, ownerId))
        val dependencies = rawDependencies.map { dependency ->
            parseResolvedArtifact(dependency, SemanticConfigurationField.DEPENDENCIES, ownerId)
        }
        val rawPlugins = input.compilerPlugins
            ?: reject(incomplete(SemanticConfigurationField.COMPILER_PLUGINS, ownerId))
        val plugins = rawPlugins.map { plugin -> parseCompilerPlugin(plugin, ownerId) }
        val settings = rawSettings.entries
            .sortedBy { (key, _) -> key }
            .associate { (key, value) ->
                LanguageSettingName.fromValidated(key) to LanguageSettingValue.fromValidated(value)
            }
        return CoherentSemanticConfiguration.create(
            compilerVersion = CompilerVersion.fromValidated(compilerVersion),
            languageVersion = LanguageVersion.fromValidated(languageVersion),
            apiVersion = ApiVersion.fromValidated(apiVersion),
            variantName = AdmittedVariantName.fromValidated(variantName),
            languageSettings = settings,
            compilerImplementation = compilerImplementation,
            toolchain = toolchain,
            compilerOptions = compilerOptions,
            resolvedDependencies = dependencies,
            compilerPlugins = plugins,
        )
    }

    private fun parseResolvedArtifact(
        input: RawResolvedArtifactInput?,
        field: SemanticConfigurationField,
        ownerId: String,
    ): ResolvedBuildArtifact {
        val artifact = input ?: reject(incomplete(field, ownerId))
        val componentIdentity = requiredBuildValue(artifact.componentIdentity, field, ownerId)
        val selectedVariantIdentity = requiredBuildValue(artifact.selectedVariantIdentity, field, ownerId)
        val contentKind = artifact.contentKind ?: reject(incomplete(field, ownerId))
        val contentDigest = artifact.contentSha256
            ?.takeIf { digest -> digest.matches(SHA_256) }
            ?.lowercase()
            ?: reject(incomplete(field, ownerId))
        return ResolvedBuildArtifact.create(
            componentIdentity = BuildComponentIdentity.fromValidated(componentIdentity),
            selectedVariantIdentity = SelectedBuildVariantIdentity.fromValidated(selectedVariantIdentity),
            contentKind = contentKind,
            contentDigest = ArtifactContentDigest.fromValidated(contentDigest),
        )
    }

    private fun parseCompilerToolchain(
        input: RawCompilerToolchainInput?,
        ownerId: String,
    ): CompilerToolchain {
        val toolchain = input
            ?: reject(incomplete(SemanticConfigurationField.TOOLCHAIN, ownerId))
        val digest = toolchain.contentSha256
            ?.takeIf { value -> value.matches(SHA_256) }
            ?.lowercase()
            ?: reject(incomplete(SemanticConfigurationField.TOOLCHAIN, ownerId))
        return CompilerToolchain.create(
            targetPlatform = CompilerTargetPlatform.fromValidated(
                requiredBuildValue(toolchain.targetPlatform, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            version = CompilerToolchainVersion.fromValidated(
                requiredBuildValue(toolchain.version, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            vendor = CompilerToolchainVendor.fromValidated(
                requiredBuildValue(toolchain.vendor, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            implementation = CompilerToolchainImplementation.fromValidated(
                requiredBuildValue(toolchain.implementation, SemanticConfigurationField.TOOLCHAIN, ownerId),
            ),
            contentDigest = ArtifactContentDigest.fromValidated(digest),
        )
    }

    private fun parseCompilerPlugin(
        input: RawCompilerPluginInput,
        ownerId: String,
    ): CompilerPluginInvocation {
        val field = SemanticConfigurationField.COMPILER_PLUGINS
        val pluginId = requiredBuildValue(input.pluginId, field, ownerId)
        val rawClasspath = input.classpath ?: reject(incomplete(field, ownerId))
        if (rawClasspath.isEmpty()) {
            reject(incomplete(field, ownerId))
        }
        val classpath = rawClasspath.map { artifact -> parseResolvedArtifact(artifact, field, ownerId) }
        val rawOptions = input.options ?: reject(incomplete(field, ownerId))
        val options = rawOptions.map { option ->
            CompilerOptionToken.fromValidated(requiredBuildValue(option.token, field, ownerId))
        }
        return CompilerPluginInvocation.create(
            pluginId = CompilerPluginId.fromValidated(pluginId),
            classpath = classpath,
            options = options,
        )
    }
}

internal fun requiredBuildValue(
    raw: String?,
    field: SemanticConfigurationField,
    ownerId: String?,
): String = raw?.takeIf(String::isNotBlank) ?: reject(incomplete(field, ownerId))

internal fun incomplete(
    field: SemanticConfigurationField,
    ownerId: String?,
): RepositoryOperationRejection.SemanticConfigurationIncomplete =
    RepositoryOperationRejection.SemanticConfigurationIncomplete(field, ownerId)
