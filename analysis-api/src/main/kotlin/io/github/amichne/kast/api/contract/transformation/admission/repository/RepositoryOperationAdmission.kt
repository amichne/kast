package io.github.amichne.kast.api.contract.transformation.admission.repository

object RepositoryOperationAdmission {
    fun admit(input: RawRepositoryOperationInput): Result {
        val bounds = input.resourceBounds
            ?: return Result.Rejected(
                RepositoryOperationRejection.ResourceBoundMissing(ResourceBoundKind.TIME),
            )
        val missingBound = listOf(
            ResourceBoundKind.TIME to bounds.timeLimitMillis,
            ResourceBoundKind.MEMORY to bounds.memoryLimitBytes,
            ResourceBoundKind.DEPTH to bounds.traversalDepthLimit,
            ResourceBoundKind.PATHS to bounds.pathLimit,
            ResourceBoundKind.RESULTS to bounds.resultLimit,
        ).firstOrNull { (_, value) -> value == null }
        if (missingBound != null) {
            return Result.Rejected(
                RepositoryOperationRejection.ResourceBoundMissing(missingBound.first),
            )
        }
        return Result.Admitted(AdmittedRepositoryOperation(input))
    }

    sealed interface Result {
        data class Admitted(
            val operation: AdmittedRepositoryOperation,
        ) : Result

        data class Rejected(
            val rejection: RepositoryOperationRejection,
        ) : Result
    }
}

class AdmittedRepositoryOperation internal constructor(
    internal val uncheckedInput: RawRepositoryOperationInput,
)

sealed interface RepositoryOperationRejection {
    val requirement: AdmissionRequirement
    val mutationStarted: Boolean
        get() = false

    data class ResourceBoundMissing(
        val bound: ResourceBoundKind,
    ) : RepositoryOperationRejection {
        override val requirement: AdmissionRequirement = AdmissionRequirement.INP_010
    }
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
    val disposition: RawSourceInputDisposition?,
    val contentSha256: String?,
)

enum class RawSourceInputKind {
    TRACKED_CHANGE,
    UNTRACKED,
    GENERATED,
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
    val moduleName: String?,
    val sourceSetName: String?,
    val variantName: String?,
    val sourceRoots: Set<String>?,
    val declarations: List<RawOwnedDeclarationInput>?,
    val families: Set<String>?,
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
    val resolvedDependencies: Set<String>?,
    val compilerPlugins: Set<String>?,
)

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
