package io.github.amichne.kast.workspace.intellij.read

/** The live IDE authority that established a detached concrete-file fact. */
enum class IntellijFileFactAuthority { PROJECT_FILE_INDEX }

/** Source membership reported for one concrete file by IntelliJ. */
enum class IntellijSourceMembership { PRODUCTION, TEST }

/** Generated-source membership reported for one concrete file by IntelliJ. */
enum class IntellijGeneratedSourceState { AUTHORED, GENERATED }

/** Finite failures that prevent a live file-index observation from becoming detached facts. */
enum class ProjectFileClassificationFailure {
    PROJECT_DISPOSED,
    FILE_INVALID,
    FILE_IS_DIRECTORY,
    INVALID_FILE_IDENTITY,
    MODULE_OWNER_UNAVAILABLE,
    INVALID_MODULE_IDENTITY,
    CONTENT_ROOT_UNAVAILABLE,
    INVALID_CONTENT_ROOT_IDENTITY,
    SOURCE_ROOT_UNAVAILABLE,
    INVALID_SOURCE_ROOT_IDENTITY,
    INDEX_OBSERVATION_FAILED,
}

@JvmInline
value class IntellijFileIdentity internal constructor(val value: String)

@JvmInline
value class IntellijModuleIdentity internal constructor(val value: String)

/**
 * Immutable result of one request-local IntelliJ source-membership observation.
 *
 * The result preserves only facts reported by IntelliJ. It does not infer source sets, module
 * ownership, or roots from paths, and it retains no Project, Module, VirtualFile, or file-index
 * object.
 */
sealed interface IntellijProjectFileClassification {
    val authority: IntellijFileFactAuthority

    data class Source internal constructor(
        val file: IntellijFileIdentity,
        val module: IntellijModuleIdentity,
        val contentRoot: IntellijFileIdentity,
        val sourceRoot: IntellijFileIdentity,
        val membership: IntellijSourceMembership,
        val generated: IntellijGeneratedSourceState,
        override val authority: IntellijFileFactAuthority =
            IntellijFileFactAuthority.PROJECT_FILE_INDEX,
    ) : IntellijProjectFileClassification

    data class NotSource internal constructor(
        val file: IntellijFileIdentity,
        override val authority: IntellijFileFactAuthority =
            IntellijFileFactAuthority.PROJECT_FILE_INDEX,
    ) : IntellijProjectFileClassification

    data class Library internal constructor(
        val file: IntellijFileIdentity,
        override val authority: IntellijFileFactAuthority =
            IntellijFileFactAuthority.PROJECT_FILE_INDEX,
    ) : IntellijProjectFileClassification

    data class Rejected internal constructor(
        val failure: ProjectFileClassificationFailure,
        override val authority: IntellijFileFactAuthority =
            IntellijFileFactAuthority.PROJECT_FILE_INDEX,
    ) : IntellijProjectFileClassification
}

/** Primitive values read from `ProjectFileIndex` before live IntelliJ objects are released. */
internal sealed interface ProjectFileIndexSourceObservation {
    val fileUrl: String

    data class Source(
        override val fileUrl: String,
        val moduleName: String?,
        val contentRootUrl: String?,
        val sourceRootUrl: String?,
        val testSource: Boolean,
        val generatedSource: Boolean,
    ) : ProjectFileIndexSourceObservation

    data class NotSource(
        override val fileUrl: String,
    ) : ProjectFileIndexSourceObservation

    data class Library(
        override val fileUrl: String,
    ) : ProjectFileIndexSourceObservation
}

/**
 * Proof transition: `ProjectFileIndexSourceObservation -> IntellijProjectFileClassification`.
 *
 * A [IntellijProjectFileClassification.Source] proves exact non-empty bounded identities and
 * typed source/generated membership. [ProjectFileClassificationFailure] is the closed expected
 * failure. Raw strings, nullable owner/root values, and Boolean platform facts are consumed only
 * at this detachment boundary.
 */
internal fun classifyProjectFileIndexObservation(
    observation: ProjectFileIndexSourceObservation,
): IntellijProjectFileClassification {
    val file = when (val identity = refineFileIdentity(observation.fileUrl)) {
        is IdentityRefinement.Refined -> identity.value
        is IdentityRefinement.Rejected -> return rejected(identity.failure)
    }
    return when (observation) {
        is ProjectFileIndexSourceObservation.NotSource ->
            IntellijProjectFileClassification.NotSource(file)
        is ProjectFileIndexSourceObservation.Library ->
            IntellijProjectFileClassification.Library(file)
        is ProjectFileIndexSourceObservation.Source -> classifySourceObservation(file, observation)
    }
}

private fun classifySourceObservation(
    file: IntellijFileIdentity,
    observation: ProjectFileIndexSourceObservation.Source,
): IntellijProjectFileClassification {
    val moduleName = observation.moduleName
        ?: return rejected(ProjectFileClassificationFailure.MODULE_OWNER_UNAVAILABLE)
    val module = when (val identity = refineModuleIdentity(moduleName)) {
        is IdentityRefinement.Refined -> identity.value
        is IdentityRefinement.Rejected -> return rejected(identity.failure)
    }
    val contentRootUrl = observation.contentRootUrl
        ?: return rejected(ProjectFileClassificationFailure.CONTENT_ROOT_UNAVAILABLE)
    val contentRoot = when (
        val identity = refineFileIdentity(
            contentRootUrl,
            ProjectFileClassificationFailure.INVALID_CONTENT_ROOT_IDENTITY,
        )
    ) {
        is IdentityRefinement.Refined -> identity.value
        is IdentityRefinement.Rejected -> return rejected(identity.failure)
    }
    val sourceRootUrl = observation.sourceRootUrl
        ?: return rejected(ProjectFileClassificationFailure.SOURCE_ROOT_UNAVAILABLE)
    val sourceRoot = when (
        val identity = refineFileIdentity(
            sourceRootUrl,
            ProjectFileClassificationFailure.INVALID_SOURCE_ROOT_IDENTITY,
        )
    ) {
        is IdentityRefinement.Refined -> identity.value
        is IdentityRefinement.Rejected -> return rejected(identity.failure)
    }
    return IntellijProjectFileClassification.Source(
        file = file,
        module = module,
        contentRoot = contentRoot,
        sourceRoot = sourceRoot,
        membership = if (observation.testSource) {
            IntellijSourceMembership.TEST
        } else {
            IntellijSourceMembership.PRODUCTION
        },
        generated = if (observation.generatedSource) {
            IntellijGeneratedSourceState.GENERATED
        } else {
            IntellijGeneratedSourceState.AUTHORED
        },
    )
}

private sealed interface IdentityRefinement<out Value> {
    data class Refined<Value>(val value: Value) : IdentityRefinement<Value>
    data class Rejected(
        val failure: ProjectFileClassificationFailure,
    ) : IdentityRefinement<Nothing>
}

private fun refineFileIdentity(
    raw: String,
    failure: ProjectFileClassificationFailure =
        ProjectFileClassificationFailure.INVALID_FILE_IDENTITY,
): IdentityRefinement<IntellijFileIdentity> =
    if (raw.isNotBlank() && raw.length <= MAX_FILE_IDENTITY_CHARS) {
        IdentityRefinement.Refined(IntellijFileIdentity(raw))
    } else {
        IdentityRefinement.Rejected(failure)
    }

private fun refineModuleIdentity(
    raw: String,
): IdentityRefinement<IntellijModuleIdentity> =
    if (raw.isNotBlank() && raw.length <= MAX_MODULE_IDENTITY_CHARS) {
        IdentityRefinement.Refined(IntellijModuleIdentity(raw))
    } else {
        IdentityRefinement.Rejected(ProjectFileClassificationFailure.INVALID_MODULE_IDENTITY)
    }

private fun rejected(
    failure: ProjectFileClassificationFailure,
): IntellijProjectFileClassification.Rejected =
    IntellijProjectFileClassification.Rejected(failure)

private const val MAX_MODULE_IDENTITY_CHARS = 512
private const val MAX_FILE_IDENTITY_CHARS = 8_192
