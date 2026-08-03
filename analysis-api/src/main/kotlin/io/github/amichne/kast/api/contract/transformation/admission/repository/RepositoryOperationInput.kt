package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.util.Collections

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

private fun <Value> immutableList(values: Collection<Value>): List<Value> =
    Collections.unmodifiableList(values.toList())
