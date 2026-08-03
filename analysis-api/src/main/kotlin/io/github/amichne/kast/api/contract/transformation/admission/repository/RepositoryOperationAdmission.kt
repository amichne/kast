package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

object RepositoryOperationAdmission {
    fun admit(input: RawRepositoryOperationInput): Result =
        RepositoryOperationAdmissionParser(input).parse()

    sealed interface Result {
        data class Admitted(
            val operation: AdmittedRepositoryOperation,
        ) : Result

        data class Rejected(
            val rejection: RepositoryOperationRejection,
        ) : Result
    }
}

class AdmittedRepositoryOperation private constructor(
    val repositoryState: AdmittedRepositoryState,
    val resolvedScope: ResolvedRepositoryScope,
    val resourceBounds: EstablishedResourceBounds,
) {
    init {
        val repositoryUnitsById = repositoryState.compilationUnits.associateBy { unit -> unit.id }
        require(
            resolvedScope.compilationUnits.all { unit -> repositoryUnitsById[unit.id] === unit },
        )
        require(
            resolvedScope.compilationUnits.all { unit ->
                unit.semanticConfiguration.identity == repositoryState.semanticConfiguration.identity
            },
        )
    }

    internal companion object {
        fun create(
            repositoryState: AdmittedRepositoryState,
            resolvedScope: ResolvedRepositoryScope,
            resourceBounds: EstablishedResourceBounds,
        ): AdmittedRepositoryOperation = AdmittedRepositoryOperation(
            repositoryState = repositoryState,
            resolvedScope = resolvedScope,
            resourceBounds = resourceBounds,
        )
    }
}

sealed interface RepositoryOperationRejection {
    val requirement: AdmissionRequirement
    val mutationStarted: Boolean
        get() = false

    data class ResourceBoundMissing(
        val bound: ResourceBoundKind,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_010
    }

    data class ApplicableInputMissing(
        val input: ApplicableInputKind,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.SYS_001
    }

    data class RepositoryRootUnresolvable(
        val requestedRoot: String?,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_001
    }

    data class RepositoryPathOutsideRoot(
        val path: String?,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_001
    }

    data class SourceRevisionUnresolvable(
        val revision: String?,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_002
    }

    data class SourceStateEvidenceMissing(
        val evidence: SourceStateEvidenceKind,
        val path: String?,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_002
    }

    data class SourceStateConflict(
        val path: String,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_002
    }

    data object BuildOwnershipEvidenceUnavailable : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_005
    }

    data class SemanticConfigurationIncomplete(
        val field: SemanticConfigurationField,
        val compilationUnit: String?,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_003
    }

    data class UnknownScope(
        val selector: RawScopeSelector,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_004
    }

    class AmbiguousScope(
        val selector: RawScopeSelector,
        candidates: List<String>,
    ) : RepositoryOperationRejection {
        val candidates: List<String> = immutableList(candidates)
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_004

        override fun equals(other: Any?): Boolean =
            other is AmbiguousScope && selector == other.selector && candidates == other.candidates

        override fun hashCode(): Int = 31 * selector.hashCode() + candidates.hashCode()

        override fun toString(): String = "AmbiguousScope(selector=$selector, candidates=$candidates)"
    }

    data object ScopeResolvesToNothing : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_004
    }

    data class ResourceBoundInvalid(
        val bound: ResourceBoundKind,
        val rawValue: Long,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_010
    }

    data class ResourceBoundExceeded(
        val bound: ResourceBoundKind,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_010
    }

    class IncompatibleSemanticConfigurations(
        compilationUnits: List<String>,
    ) : RepositoryOperationRejection {
        val compilationUnits: List<String> = immutableList(compilationUnits)
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_012

        override fun equals(other: Any?): Boolean =
            other is IncompatibleSemanticConfigurations && compilationUnits == other.compilationUnits

        override fun hashCode(): Int = compilationUnits.hashCode()

        override fun toString(): String =
            "IncompatibleSemanticConfigurations(compilationUnits=$compilationUnits)"
    }
}

enum class ApplicableInputKind {
    REPOSITORY,
    SOURCE_STATE,
    BUILD_OWNERSHIP,
    SCOPE,
}

enum class SourceStateEvidenceKind {
    INVENTORY,
    PATH,
    KIND,
    PRESENCE,
    DISPOSITION,
    CONTENT_DIGEST,
}

enum class SemanticConfigurationField {
    COMPILATION_UNITS,
    OWNER_ID,
    MODULE_IDENTITY,
    MODULE,
    SOURCE_SET,
    VARIANT,
    SOURCE_ROOTS,
    GENERATED_SOURCE_ROOTS,
    DECLARATIONS,
    FAMILIES,
    SOURCE_SET_RELATIONSHIPS,
    COMPILER,
    COMPILER_VERSION,
    LANGUAGE_VERSION,
    API_VERSION,
    LANGUAGE_SETTINGS,
    COMPILER_IMPLEMENTATION,
    TOOLCHAIN,
    COMPILER_OPTIONS,
    DEPENDENCIES,
    COMPILER_PLUGINS,
}

enum class AdmissionRequirement {
    SYS_001,
    INP_001,
    INP_002,
    INP_003,
    INP_004,
    INP_005,
    INP_010,
    INP_012,
}

enum class ResourceBoundKind {
    TIME,
    MEMORY,
    DEPTH,
    PATHS,
    RESULTS,
}

data class RawRepositoryOperationInput(
    val repository: RawRepositoryInput?,
    val sourceState: RawSourceStateInput?,
    val buildOwnership: RawBuildOwnershipEvidence?,
    val scope: List<RawScopeSelector>?,
    val resourceBounds: RawResourceBoundsInput?,
)

data class RawRepositoryInput(
    val requestedRoot: String?,
    val baseDirectory: String?,
)

data class RawSourceStateInput(
    val revision: String?,
    val inputs: List<RawSourceInput>?,
)

data class RawSourceInput(
    val path: String?,
    val kind: RawSourceInputKind?,
    val presence: RawSourceInputPresence?,
    val disposition: RawSourceInputDisposition?,
    val contentSha256: String?,
)

enum class RawSourceInputKind {
    TRACKED_CHANGE,
    UNTRACKED,
    GENERATED,
}

enum class RawSourceInputPresence {
    PRESENT,
    DELETED,
}

enum class RawSourceInputDisposition {
    INCLUDED,
    EXCLUDED,
}

sealed interface RawBuildOwnershipEvidence {
    data class Available(
        val compilationUnits: List<RawCompilationUnitInput>?,
    ) : RawBuildOwnershipEvidence

    data object Unavailable : RawBuildOwnershipEvidence
}

data class RawCompilationUnitInput(
    val ownerId: String?,
    val moduleIdentity: String?,
    val moduleName: String?,
    val sourceSetName: String?,
    val variantName: String?,
    val sourceRoots: Set<String>?,
    val generatedSourceRoots: Set<String>?,
    val declarations: List<RawOwnedDeclarationInput>?,
    val families: Set<String>?,
    val sourceSetRelationships: Set<RawSourceSetRelationshipInput>?,
    val compiler: RawCompilerInput?,
)

data class RawOwnedDeclarationInput(
    val fullyQualifiedName: String?,
    val path: String?,
)

data class RawCompilerInput(
    val compilerVersion: String?,
    val languageVersion: String?,
    val apiVersion: String?,
    val languageSettings: Map<String, String>?,
    val compilerImplementation: RawResolvedArtifactInput?,
    val toolchain: RawCompilerToolchainInput?,
    val compilerOptions: List<RawCompilerOptionInput>?,
    val resolvedDependencies: List<RawResolvedArtifactInput>?,
    val compilerPlugins: List<RawCompilerPluginInput>?,
)

data class RawResolvedArtifactInput(
    val componentIdentity: String?,
    val selectedVariantIdentity: String?,
    val contentKind: ArtifactContentKind?,
    val contentSha256: String?,
)

enum class ArtifactContentKind {
    FILE,
    DIRECTORY_TREE,
}

data class RawCompilerToolchainInput(
    val targetPlatform: String?,
    val version: String?,
    val vendor: String?,
    val implementation: String?,
    val contentSha256: String?,
)

data class RawCompilerOptionInput(
    val token: String?,
)

data class RawCompilerPluginInput(
    val pluginId: String?,
    val classpath: List<RawResolvedArtifactInput>?,
    val options: List<RawCompilerOptionInput>?,
)

data class RawSourceSetRelationshipInput(
    val kind: SourceSetRelationshipKind?,
    val targetCompilationUnitId: String?,
)

enum class SourceSetRelationshipKind {
    DEPENDS_ON,
    FRIEND,
}

sealed interface RawScopeSelector {
    data class Module(val moduleName: String?) : RawScopeSelector

    data class SourceSet(
        val moduleName: String?,
        val sourceSetName: String?,
    ) : RawScopeSelector

    data class Declaration(val fullyQualifiedName: String?) : RawScopeSelector

    data class Family(val familyName: String?) : RawScopeSelector
}

data class RawResourceBoundsInput(
    val timeLimitMillis: Long?,
    val memoryLimitBytes: Long?,
    val traversalDepthLimit: Int?,
    val pathLimit: Int?,
    val resultLimit: Int?,
)

class AdmittedRepositoryState private constructor(
    val canonicalRoot: CanonicalRepositoryRoot,
    val sourceState: ExactSourceState,
    val semanticConfiguration: CoherentSemanticConfiguration,
    compilationUnits: List<AdmittedCompilationUnit>,
    val identity: RepositoryStateIdentity,
) {
    val compilationUnits: List<AdmittedCompilationUnit> = immutableList(compilationUnits)

    init {
        require(this.compilationUnits.isNotEmpty())
        require(this.compilationUnits.map { unit -> unit.id }.distinct().size == this.compilationUnits.size)
    }

    internal companion object {
        fun create(
            canonicalRoot: CanonicalRepositoryRoot,
            sourceState: ExactSourceState,
            semanticConfiguration: CoherentSemanticConfiguration,
            compilationUnits: List<AdmittedCompilationUnit>,
        ): AdmittedRepositoryState {
            val immutableUnits = immutableList(compilationUnits.sortedBy { unit -> unit.id.value })
            require(
                immutableUnits.any { unit ->
                    unit.semanticConfiguration.identity == semanticConfiguration.identity
                },
            )
            return AdmittedRepositoryState(
                canonicalRoot = canonicalRoot,
                sourceState = sourceState,
                semanticConfiguration = semanticConfiguration,
                compilationUnits = immutableUnits,
                identity = AdmissionIdentity.repository(canonicalRoot, sourceState, immutableUnits),
            )
        }
    }
}

@JvmInline
value class CanonicalRepositoryRoot private constructor(val value: String) {
    init {
        val path = Path.of(value)
        require(value.isNotBlank() && path.isAbsolute && path.normalize() == path)
    }

    internal companion object {
        fun fromValidated(value: String): CanonicalRepositoryRoot = CanonicalRepositoryRoot(value)
    }
}

@JvmInline
value class RepositoryStateIdentity private constructor(val value: String) {
    init {
        require(value.matches(SHA_256_VALUE))
    }

    internal companion object {
        fun fromValidated(value: String): RepositoryStateIdentity = RepositoryStateIdentity(value)
    }
}

class ExactSourceState private constructor(
    val revision: SourceRevision,
    inputs: List<ExactSourceInput>,
) {
    val inputs: List<ExactSourceInput> = immutableList(inputs)

    init {
        require(this.inputs.map { input -> input.path }.distinct().size == this.inputs.size)
    }

    internal companion object {
        fun create(
            revision: SourceRevision,
            inputs: List<ExactSourceInput>,
        ): ExactSourceState = ExactSourceState(
            revision,
            inputs.sortedBy { input -> input.path.value },
        )
    }
}

@JvmInline
value class SourceRevision private constructor(val value: String) {
    init {
        require(value.matches(SOURCE_REVISION_VALUE) && value == value.lowercase())
    }

    internal companion object {
        fun fromValidated(value: String): SourceRevision = SourceRevision(value)
    }
}

sealed interface ExactSourceInput {
    val path: RepositoryRelativePath
    val kind: RawSourceInputKind
    val presence: RawSourceInputPresence
    val disposition: RawSourceInputDisposition
    val contentDigest: SourceContentDigest?

    class IncludedFile private constructor(
        override val path: RepositoryRelativePath,
        override val kind: RawSourceInputKind,
        override val contentDigest: SourceContentDigest,
    ) : ExactSourceInput {
        override val presence: RawSourceInputPresence = RawSourceInputPresence.PRESENT
        override val disposition: RawSourceInputDisposition = RawSourceInputDisposition.INCLUDED

        internal companion object {
            fun create(
                path: RepositoryRelativePath,
                kind: RawSourceInputKind,
                contentDigest: SourceContentDigest,
            ): IncludedFile = IncludedFile(path, kind, contentDigest)
        }
    }

    class ExcludedFile private constructor(
        override val path: RepositoryRelativePath,
        override val kind: RawSourceInputKind,
    ) : ExactSourceInput {
        override val presence: RawSourceInputPresence = RawSourceInputPresence.PRESENT
        override val disposition: RawSourceInputDisposition = RawSourceInputDisposition.EXCLUDED
        override val contentDigest: SourceContentDigest? = null

        internal companion object {
            fun create(
                path: RepositoryRelativePath,
                kind: RawSourceInputKind,
            ): ExcludedFile = ExcludedFile(path, kind)
        }
    }

    class DeletedTrackedInput private constructor(
        override val path: RepositoryRelativePath,
        override val disposition: RawSourceInputDisposition,
    ) : ExactSourceInput {
        override val kind: RawSourceInputKind = RawSourceInputKind.TRACKED_CHANGE
        override val presence: RawSourceInputPresence = RawSourceInputPresence.DELETED
        override val contentDigest: SourceContentDigest? = null

        init {
            require(
                disposition == RawSourceInputDisposition.INCLUDED ||
                    disposition == RawSourceInputDisposition.EXCLUDED,
            )
        }

        internal companion object {
            fun create(
                path: RepositoryRelativePath,
                disposition: RawSourceInputDisposition,
            ): DeletedTrackedInput = DeletedTrackedInput(path, disposition)
        }
    }

    companion object {
        internal fun create(
            path: RepositoryRelativePath,
            kind: RawSourceInputKind,
            presence: RawSourceInputPresence,
            disposition: RawSourceInputDisposition,
            contentDigest: SourceContentDigest?,
        ): ExactSourceInput = when {
            presence == RawSourceInputPresence.PRESENT &&
                disposition == RawSourceInputDisposition.INCLUDED &&
                contentDigest != null -> IncludedFile.create(path, kind, contentDigest)

            presence == RawSourceInputPresence.PRESENT &&
                disposition == RawSourceInputDisposition.EXCLUDED &&
                contentDigest == null -> ExcludedFile.create(path, kind)

            presence == RawSourceInputPresence.DELETED &&
                kind == RawSourceInputKind.TRACKED_CHANGE &&
                contentDigest == null -> DeletedTrackedInput.create(path, disposition)

            else -> error("Source input invariants were not established")
        }
    }
}

@JvmInline
value class RepositoryRelativePath private constructor(val value: String) {
    init {
        val path = Path.of(value)
        require(value.isNotBlank() && !path.isAbsolute && path.normalize() == path)
    }

    internal companion object {
        fun fromValidated(value: String): RepositoryRelativePath = RepositoryRelativePath(value)
    }
}

@JvmInline
value class SourceContentDigest private constructor(val value: String) {
    init {
        require(value.matches(SHA_256_VALUE) && value == value.lowercase())
    }

    internal companion object {
        fun fromValidated(value: String): SourceContentDigest = SourceContentDigest(value)
    }
}

class ResolvedRepositoryScope private constructor(
    compilationUnits: List<AdmittedCompilationUnit>,
) {
    val compilationUnits: List<AdmittedCompilationUnit> = immutableList(compilationUnits)

    init {
        require(this.compilationUnits.isNotEmpty())
        require(this.compilationUnits.map { unit -> unit.id }.distinct().size == this.compilationUnits.size)
    }

    internal companion object {
        fun create(compilationUnits: List<AdmittedCompilationUnit>): ResolvedRepositoryScope =
            ResolvedRepositoryScope(compilationUnits.sortedBy { unit -> unit.id.value })
    }
}

class AdmittedCompilationUnit private constructor(
    val id: CompilationUnitId,
    val moduleIdentity: AdmittedModuleIdentity,
    val moduleName: AdmittedModuleName,
    val sourceSetName: AdmittedSourceSetName,
    val variantName: AdmittedVariantName,
    sourceRoots: List<RepositoryRelativePath>,
    generatedSourceRoots: List<RepositoryRelativePath>,
    declarations: List<AdmittedDeclaration>,
    families: Set<SemanticFamilyName>,
    sourceSetRelationships: Set<SourceSetRelationship>,
    val semanticConfiguration: CoherentSemanticConfiguration,
) {
    val sourceRoots: List<RepositoryRelativePath> = immutableList(sourceRoots)
    val generatedSourceRoots: List<RepositoryRelativePath> = immutableList(generatedSourceRoots)
    val declarations: List<AdmittedDeclaration> = immutableList(declarations)
    val families: Set<SemanticFamilyName> = immutableSet(families)
    val sourceSetRelationships: Set<SourceSetRelationship> = immutableSet(sourceSetRelationships)

    init {
        require(this.sourceRoots.isNotEmpty())
        require(
            this.generatedSourceRoots.all { generatedRoot ->
                this.sourceRoots.any { sourceRoot ->
                    generatedRoot.value == sourceRoot.value ||
                        generatedRoot.value.startsWith("${sourceRoot.value}/")
                }
            },
        )
        require(semanticConfiguration.variantName == variantName)
        require(
            this.declarations.all { declaration ->
                this.sourceRoots.any { sourceRoot ->
                    declaration.path.value == sourceRoot.value ||
                        declaration.path.value.startsWith("${sourceRoot.value}/")
                }
            },
        )
    }

    internal companion object {
        fun create(
            id: CompilationUnitId,
            moduleIdentity: AdmittedModuleIdentity,
            moduleName: AdmittedModuleName,
            sourceSetName: AdmittedSourceSetName,
            variantName: AdmittedVariantName,
            sourceRoots: List<RepositoryRelativePath>,
            generatedSourceRoots: List<RepositoryRelativePath>,
            declarations: List<AdmittedDeclaration>,
            families: Set<SemanticFamilyName>,
            sourceSetRelationships: Set<SourceSetRelationship>,
            semanticConfiguration: CoherentSemanticConfiguration,
        ): AdmittedCompilationUnit = AdmittedCompilationUnit(
            id = id,
            moduleIdentity = moduleIdentity,
            moduleName = moduleName,
            sourceSetName = sourceSetName,
            variantName = variantName,
            sourceRoots = sourceRoots.distinct().sortedBy(RepositoryRelativePath::value),
            generatedSourceRoots = generatedSourceRoots.distinct().sortedBy(RepositoryRelativePath::value),
            declarations = declarations.sortedWith(
                compareBy({ declaration -> declaration.name.value }, { declaration -> declaration.path.value }),
            ),
            families = families.sortedBy(SemanticFamilyName::value).toCollection(linkedSetOf()),
            sourceSetRelationships = sourceSetRelationships.sortedWith(
                compareBy(
                    { relationship -> relationship.kind.name },
                    { relationship -> relationship.targetCompilationUnitId.value },
                ),
            ).toCollection(linkedSetOf()),
            semanticConfiguration = semanticConfiguration,
        )
    }
}

class AdmittedDeclaration private constructor(
    val name: AdmittedDeclarationName,
    val path: RepositoryRelativePath,
) {
    internal companion object {
        fun create(
            name: AdmittedDeclarationName,
            path: RepositoryRelativePath,
        ): AdmittedDeclaration = AdmittedDeclaration(name, path)
    }
}

class SourceSetRelationship private constructor(
    val kind: SourceSetRelationshipKind,
    val targetCompilationUnitId: CompilationUnitId,
) {
    internal companion object {
        fun create(
            kind: SourceSetRelationshipKind,
            targetCompilationUnitId: CompilationUnitId,
        ): SourceSetRelationship = SourceSetRelationship(kind, targetCompilationUnitId)
    }

    override fun equals(other: Any?): Boolean =
        other is SourceSetRelationship &&
            kind == other.kind &&
            targetCompilationUnitId == other.targetCompilationUnitId

    override fun hashCode(): Int = 31 * kind.hashCode() + targetCompilationUnitId.hashCode()
}

class ResolvedBuildArtifact private constructor(
    val componentIdentity: BuildComponentIdentity,
    val selectedVariantIdentity: SelectedBuildVariantIdentity,
    val contentKind: ArtifactContentKind,
    val contentDigest: ArtifactContentDigest,
) {
    internal companion object {
        fun create(
            componentIdentity: BuildComponentIdentity,
            selectedVariantIdentity: SelectedBuildVariantIdentity,
            contentKind: ArtifactContentKind,
            contentDigest: ArtifactContentDigest,
        ): ResolvedBuildArtifact = ResolvedBuildArtifact(
            componentIdentity,
            selectedVariantIdentity,
            contentKind,
            contentDigest,
        )
    }
}

class CompilerToolchain private constructor(
    val targetPlatform: CompilerTargetPlatform,
    val version: CompilerToolchainVersion,
    val vendor: CompilerToolchainVendor,
    val implementation: CompilerToolchainImplementation,
    val contentDigest: ArtifactContentDigest,
) {
    internal companion object {
        fun create(
            targetPlatform: CompilerTargetPlatform,
            version: CompilerToolchainVersion,
            vendor: CompilerToolchainVendor,
            implementation: CompilerToolchainImplementation,
            contentDigest: ArtifactContentDigest,
        ): CompilerToolchain = CompilerToolchain(
            targetPlatform,
            version,
            vendor,
            implementation,
            contentDigest,
        )
    }
}

class CompilerPluginInvocation private constructor(
    val pluginId: CompilerPluginId,
    classpath: List<ResolvedBuildArtifact>,
    options: List<CompilerOptionToken>,
) {
    val classpath: List<ResolvedBuildArtifact> = immutableList(classpath)
    val options: List<CompilerOptionToken> = immutableList(options)

    init {
        require(this.classpath.isNotEmpty())
    }

    internal companion object {
        fun create(
            pluginId: CompilerPluginId,
            classpath: List<ResolvedBuildArtifact>,
            options: List<CompilerOptionToken>,
        ): CompilerPluginInvocation = CompilerPluginInvocation(pluginId, classpath, options)
    }
}

class CoherentSemanticConfiguration private constructor(
    val compilerVersion: CompilerVersion,
    val languageVersion: LanguageVersion,
    val apiVersion: ApiVersion,
    val variantName: AdmittedVariantName,
    languageSettings: Map<LanguageSettingName, LanguageSettingValue>,
    val compilerImplementation: ResolvedBuildArtifact,
    val toolchain: CompilerToolchain,
    compilerOptions: List<CompilerOptionToken>,
    resolvedDependencies: List<ResolvedBuildArtifact>,
    compilerPlugins: List<CompilerPluginInvocation>,
    val identity: SemanticConfigurationIdentity,
) {
    val languageSettings: Map<LanguageSettingName, LanguageSettingValue> = immutableMap(languageSettings)
    val compilerOptions: List<CompilerOptionToken> = immutableList(compilerOptions)
    val resolvedDependencies: List<ResolvedBuildArtifact> = immutableList(resolvedDependencies)
    val compilerPlugins: List<CompilerPluginInvocation> = immutableList(compilerPlugins)

    internal companion object {
        fun create(
            compilerVersion: CompilerVersion,
            languageVersion: LanguageVersion,
            apiVersion: ApiVersion,
            variantName: AdmittedVariantName,
            languageSettings: Map<LanguageSettingName, LanguageSettingValue>,
            compilerImplementation: ResolvedBuildArtifact,
            toolchain: CompilerToolchain,
            compilerOptions: List<CompilerOptionToken>,
            resolvedDependencies: List<ResolvedBuildArtifact>,
            compilerPlugins: List<CompilerPluginInvocation>,
        ): CoherentSemanticConfiguration {
            val immutableSettings = immutableMap(
                languageSettings.entries
                    .sortedBy { (key, _) -> key.value }
                    .associate { (key, value) -> key to value },
            )
            val immutableOptions = immutableList(compilerOptions)
            val immutableDependencies = immutableList(resolvedDependencies)
            val immutablePlugins = immutableList(compilerPlugins)
            return CoherentSemanticConfiguration(
                compilerVersion = compilerVersion,
                languageVersion = languageVersion,
                apiVersion = apiVersion,
                variantName = variantName,
                languageSettings = immutableSettings,
                compilerImplementation = compilerImplementation,
                toolchain = toolchain,
                compilerOptions = immutableOptions,
                resolvedDependencies = immutableDependencies,
                compilerPlugins = immutablePlugins,
                identity = AdmissionIdentity.semanticConfiguration(
                    compilerVersion = compilerVersion,
                    languageVersion = languageVersion,
                    apiVersion = apiVersion,
                    variantName = variantName,
                    languageSettings = immutableSettings,
                    compilerImplementation = compilerImplementation,
                    toolchain = toolchain,
                    compilerOptions = immutableOptions,
                    resolvedDependencies = immutableDependencies,
                    compilerPlugins = immutablePlugins,
                ),
            )
        }
    }
}

class EstablishedResourceBounds private constructor(
    val timeLimitMillis: AnalysisTimeLimitMillis,
    val memoryLimitBytes: AnalysisMemoryLimitBytes,
    val traversalDepthLimit: TraversalDepthLimit,
    val pathLimit: AnalysisPathLimit,
    val resultLimit: AnalysisResultLimit,
) {
    internal companion object {
        fun create(
            timeLimitMillis: AnalysisTimeLimitMillis,
            memoryLimitBytes: AnalysisMemoryLimitBytes,
            traversalDepthLimit: TraversalDepthLimit,
            pathLimit: AnalysisPathLimit,
            resultLimit: AnalysisResultLimit,
        ): EstablishedResourceBounds = EstablishedResourceBounds(
            timeLimitMillis = timeLimitMillis,
            memoryLimitBytes = memoryLimitBytes,
            traversalDepthLimit = traversalDepthLimit,
            pathLimit = pathLimit,
            resultLimit = resultLimit,
        )
    }
}

@JvmInline value class CompilationUnitId private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilationUnitId(value) }
}

@JvmInline value class AdmittedModuleName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedModuleName(value) }
}

@JvmInline value class AdmittedModuleIdentity private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedModuleIdentity(value) }
}

@JvmInline value class AdmittedSourceSetName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedSourceSetName(value) }
}

@JvmInline value class AdmittedVariantName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedVariantName(value) }
}

@JvmInline value class AdmittedDeclarationName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = AdmittedDeclarationName(value) }
}

@JvmInline value class SemanticFamilyName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = SemanticFamilyName(value) }
}

@JvmInline value class CompilerVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerVersion(value) }
}

@JvmInline value class LanguageVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = LanguageVersion(value) }
}

@JvmInline value class ApiVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = ApiVersion(value) }
}

@JvmInline value class LanguageSettingName private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = LanguageSettingName(value) }
}

@JvmInline value class LanguageSettingValue private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = LanguageSettingValue(value) }
}

@JvmInline value class BuildComponentIdentity private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = BuildComponentIdentity(value) }
}

@JvmInline value class SelectedBuildVariantIdentity private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = SelectedBuildVariantIdentity(value) }
}

@JvmInline value class ArtifactContentDigest private constructor(val value: String) {
    init { require(value.matches(SHA_256_VALUE) && value == value.lowercase()) }
    internal companion object { fun fromValidated(value: String) = ArtifactContentDigest(value) }
}

@JvmInline value class CompilerTargetPlatform private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerTargetPlatform(value) }
}

@JvmInline value class CompilerToolchainVersion private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerToolchainVersion(value) }
}

@JvmInline value class CompilerToolchainVendor private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerToolchainVendor(value) }
}

@JvmInline value class CompilerToolchainImplementation private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerToolchainImplementation(value) }
}

@JvmInline value class CompilerOptionToken private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerOptionToken(value) }
}

@JvmInline value class CompilerPluginId private constructor(val value: String) {
    init { require(value.isNotBlank()) }
    internal companion object { fun fromValidated(value: String) = CompilerPluginId(value) }
}

@JvmInline value class SemanticConfigurationIdentity private constructor(val value: String) {
    init { require(value.matches(SHA_256_VALUE)) }
    internal companion object { fun fromValidated(value: String) = SemanticConfigurationIdentity(value) }
}

@JvmInline value class AnalysisTimeLimitMillis private constructor(val value: Long) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Long) = AnalysisTimeLimitMillis(value) }
}

@JvmInline value class AnalysisMemoryLimitBytes private constructor(val value: Long) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Long) = AnalysisMemoryLimitBytes(value) }
}

@JvmInline value class TraversalDepthLimit private constructor(val value: Int) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Int) = TraversalDepthLimit(value) }
}

@JvmInline value class AnalysisPathLimit private constructor(val value: Int) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Int) = AnalysisPathLimit(value) }
}

@JvmInline value class AnalysisResultLimit private constructor(val value: Int) {
    init { require(value > 0) }
    internal companion object { fun fromValidated(value: Int) = AnalysisResultLimit(value) }
}

private object AdmissionIdentity {
    fun semanticConfiguration(
        compilerVersion: CompilerVersion,
        languageVersion: LanguageVersion,
        apiVersion: ApiVersion,
        variantName: AdmittedVariantName,
        languageSettings: Map<LanguageSettingName, LanguageSettingValue>,
        compilerImplementation: ResolvedBuildArtifact,
        toolchain: CompilerToolchain,
        compilerOptions: List<CompilerOptionToken>,
        resolvedDependencies: List<ResolvedBuildArtifact>,
        compilerPlugins: List<CompilerPluginInvocation>,
    ): SemanticConfigurationIdentity {
        val evidence = buildList<List<String>> {
            add(listOf("compiler", compilerVersion.value))
            add(listOf("language", languageVersion.value))
            add(listOf("api", apiVersion.value))
            add(listOf("variant", variantName.value))
            languageSettings.entries.sortedBy { (key, _) -> key.value }
                .forEach { (key, value) -> add(listOf("setting", key.value, value.value)) }
            add(
                listOf(
                    "compilerImplementation",
                    compilerImplementation.componentIdentity.value,
                    compilerImplementation.selectedVariantIdentity.value,
                    compilerImplementation.contentKind.name,
                    compilerImplementation.contentDigest.value,
                ),
            )
            add(
                listOf(
                    "toolchain",
                    toolchain.targetPlatform.value,
                    toolchain.version.value,
                    toolchain.vendor.value,
                    toolchain.implementation.value,
                    toolchain.contentDigest.value,
                ),
            )
            compilerOptions.forEachIndexed { index, option ->
                add(listOf("compilerOption", index.toString(), option.value))
            }
            resolvedDependencies.forEachIndexed { index, dependency ->
                add(
                    listOf(
                        "dependency",
                        index.toString(),
                        dependency.componentIdentity.value,
                        dependency.selectedVariantIdentity.value,
                        dependency.contentKind.name,
                        dependency.contentDigest.value,
                    ),
                )
            }
            compilerPlugins.forEachIndexed { pluginIndex, plugin ->
                add(listOf("plugin", pluginIndex.toString(), plugin.pluginId.value))
                plugin.classpath.forEachIndexed { artifactIndex, artifact ->
                    add(
                        listOf(
                            "pluginArtifact",
                            pluginIndex.toString(),
                            artifactIndex.toString(),
                            artifact.componentIdentity.value,
                            artifact.selectedVariantIdentity.value,
                            artifact.contentKind.name,
                            artifact.contentDigest.value,
                        ),
                    )
                }
                plugin.options.forEachIndexed { optionIndex, option ->
                    add(
                        listOf(
                            "pluginOption",
                            pluginIndex.toString(),
                            optionIndex.toString(),
                            option.value,
                        ),
                    )
                }
            }
        }
        return SemanticConfigurationIdentity.fromValidated(digest(evidence))
    }

    fun repository(
        root: CanonicalRepositoryRoot,
        sourceState: ExactSourceState,
        units: List<AdmittedCompilationUnit>,
    ): RepositoryStateIdentity {
        val evidence = buildList<List<String>> {
            add(listOf("root", root.value))
            add(listOf("revision", sourceState.revision.value))
            sourceState.inputs.sortedBy { input -> input.path.value }.forEach { input ->
                add(
                    listOf(
                        "source",
                        input.path.value,
                        input.kind.name,
                        input.presence.name,
                        input.disposition.name,
                        input.contentDigest?.value.orEmpty(),
                    ),
                )
            }
            units.sortedBy { unit -> unit.id.value }.forEach { unit ->
                add(listOf("unit", unit.id.value))
                add(listOf("moduleIdentity", unit.moduleIdentity.value))
                add(listOf("module", unit.moduleName.value))
                add(listOf("sourceSet", unit.sourceSetName.value))
                add(listOf("variant", unit.variantName.value))
                unit.sourceRoots.sortedBy(RepositoryRelativePath::value)
                    .forEach { sourceRoot -> add(listOf("sourceRoot", sourceRoot.value)) }
                unit.generatedSourceRoots.sortedBy(RepositoryRelativePath::value)
                    .forEach { sourceRoot -> add(listOf("generatedSourceRoot", sourceRoot.value)) }
                unit.declarations.sortedWith(
                    compareBy({ declaration -> declaration.name.value }, { declaration -> declaration.path.value }),
                ).forEach { declaration ->
                    add(listOf("declaration", declaration.name.value, declaration.path.value))
                }
                unit.families.sortedBy(SemanticFamilyName::value)
                    .forEach { family -> add(listOf("family", family.value)) }
                unit.sourceSetRelationships.sortedWith(
                    compareBy(
                        { relationship -> relationship.kind.name },
                        { relationship -> relationship.targetCompilationUnitId.value },
                    ),
                ).forEach { relationship ->
                    add(
                        listOf(
                            "sourceSetRelationship",
                            relationship.kind.name,
                            relationship.targetCompilationUnitId.value,
                        ),
                    )
                }
                add(listOf("configuration", unit.semanticConfiguration.identity.value))
            }
        }
        return RepositoryStateIdentity.fromValidated(digest(evidence))
    }

    private fun digest(records: List<List<String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(records.size)
        records.forEach { record ->
            digest.updateInt(record.size)
            record.forEach { field ->
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
        }
        return digest.digest()
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }
}

private val SOURCE_REVISION_VALUE: Regex = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
private val SHA_256_VALUE: Regex = Regex("[0-9a-f]{64}")

private fun <Value> immutableList(values: Collection<Value>): List<Value> =
    Collections.unmodifiableList(values.toList())

private fun <Value> immutableSet(values: Collection<Value>): Set<Value> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <Key, Value> immutableMap(values: Map<Key, Value>): Map<Key, Value> =
    Collections.unmodifiableMap(LinkedHashMap(values))
