package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.util.Collections
import java.util.LinkedHashSet

/** Finite reasons that one existing-Project model observation cannot become detached authority. */
enum class DetachedModelCaptureFailure {
    PROJECT_DISPOSED,
    PROJECT_NOT_OPEN,
    PROJECT_NOT_INITIALIZED,
    PROJECT_DUMB,
    WRONG_THREAD,
    ROOT_UNAVAILABLE,
    ROOT_MISMATCH,
    GRADLE_MODEL_INCOMPLETE,
    TOO_MANY_GRADLE_MODELS,
    NO_MODULES,
    TOO_MANY_MODULES,
    MODULE_DISPOSED,
    INVALID_MODULE_NAME,
    IDENTITY_TOO_LONG,
    DUPLICATE_MODULE,
    NOT_GRADLE_OWNED,
    INVALID_GRADLE_BUILD_ROOT,
    GRADLE_BUILD_ROOT_OUTSIDE_WORKSPACE,
    INVALID_GRADLE_PROJECT_ROOT,
    GRADLE_PROJECT_ROOT_OUTSIDE_WORKSPACE,
    INVALID_GRADLE_PROJECT_IDENTITY,
    NO_SOURCE_ROOTS,
    TOO_MANY_SOURCE_ROOTS,
    INVALID_SOURCE_ROOT,
    INVALID_SOURCE_ROOT_KIND,
    INVALID_SOURCE_ROOT_PROVENANCE,
    SOURCE_ROOT_OUTSIDE_WORKSPACE,
    DUPLICATE_SOURCE_ROOT,
    CONFLICTING_SOURCE_ROOT_KIND,
    CONFLICTING_SOURCE_ROOT_PROVENANCE,
    AMBIGUOUS_SOURCE_ROOT_OWNER,
    INVALID_SDK_IDENTITY,
    NO_CLASSPATH,
    TOO_MANY_CLASSPATH_ENTRIES,
    INVALID_CLASSPATH_IDENTITY,
    CLASSPATH_IDENTITY_TOO_LONG,
    DUPLICATE_CLASSPATH_IDENTITY,
    PATH_IDENTITY_TOO_LONG,
    OBSERVATION_FAILED,
    READ_PREEMPTED,
}

/** Closed result of the state-specific existing-Project model capture. */
sealed interface DetachedModelCapture {
    data class Captured(
        val model: DetachedIdeWorkspaceModel,
    ) : DetachedModelCapture

    /** A rejection whose required first failure makes the empty state unrepresentable. */
    class Rejected internal constructor(
        firstFailure: DetachedModelCaptureFailure,
        additionalFailures: Set<DetachedModelCaptureFailure> = emptySet(),
    ) : DetachedModelCapture {
        val failures: Set<DetachedModelCaptureFailure> = Collections.unmodifiableSet(
            LinkedHashSet<DetachedModelCaptureFailure>().apply {
                add(firstFailure)
                addAll(additionalFailures)
            },
        )
    }
}

/** Finite source-root kinds detached from IntelliJ source-folder implementations. */
enum class DetachedSourceRootKind { PRODUCTION, TEST, RESOURCE, TEST_RESOURCE }

/** Cached IntelliJ source-folder provenance detached without path inference. */
enum class DetachedSourceRootProvenance { AUTHORED, GENERATED }

@JvmInline
value class DetachedModuleName internal constructor(val value: String)

@JvmInline
value class DetachedWorkspaceRelativePath internal constructor(val value: String)

@JvmInline
value class DetachedGradleProjectIdentity internal constructor(val value: String)

@JvmInline
value class DetachedSdkName internal constructor(val value: String)

@JvmInline
value class DetachedSdkType internal constructor(val value: String)

@JvmInline
value class DetachedSdkVersion internal constructor(val value: String)

@JvmInline
value class DetachedClasspathEntryUrl internal constructor(val value: String)

/** Detached Gradle owner of one admitted IntelliJ module. */
@ConsistentCopyVisibility
data class DetachedGradleModuleOwner internal constructor(
    val buildRoot: DetachedWorkspaceRelativePath,
    val projectRoot: DetachedWorkspaceRelativePath,
    val projectIdentity: DetachedGradleProjectIdentity,
)

/** Detached source-root identity owned by exactly one admitted module. */
@ConsistentCopyVisibility
data class DetachedIdeSourceRoot internal constructor(
    val location: DetachedWorkspaceRelativePath,
    val kind: DetachedSourceRootKind,
    val provenance: DetachedSourceRootProvenance,
)

/** Detached SDK identity used by one admitted module. */
@ConsistentCopyVisibility
data class DetachedIdeSdkIdentity internal constructor(
    val name: DetachedSdkName,
    val type: DetachedSdkType,
    val version: DetachedSdkVersion,
)

/** Detached classpath identity used by one admitted module. */
@ConsistentCopyVisibility
data class DetachedIdeClasspathEntry internal constructor(
    val url: DetachedClasspathEntryUrl,
)

/** One immutable exact Gradle-owned module in the detached workspace model. */
class DetachedIdeModule internal constructor(
    val name: DetachedModuleName,
    val owner: DetachedGradleModuleOwner,
    sourceRoots: List<DetachedIdeSourceRoot>,
    val sdk: DetachedIdeSdkIdentity,
    classpath: List<DetachedIdeClasspathEntry>,
) {
    val sourceRoots: List<DetachedIdeSourceRoot> = immutableList(sourceRoots)
    val classpath: List<DetachedIdeClasspathEntry> = immutableList(classpath)
}

/**
 * Immutable model detached from one already-admitted IntelliJ Project.
 *
 * Every retained value is host-neutral data. No live Project, module, VFS, PSI, search-scope,
 * Gradle model, callback, or mutable collection can be recovered from this surface.
 */
class DetachedIdeWorkspaceModel private constructor(
    exactRoot: ExactObservedWorkspaceRoot,
    val compatibility: AdmittedIdeHostCompatibility,
    modules: RefinedDetachedModules,
) {
    val canonicalRoot: CanonicalWorkspaceRoot = exactRoot.canonicalRoot
    val modules: List<DetachedIdeModule> = immutableList(modules.values)

    companion object {
        /**
         * Proof transition: `(CanonicalWorkspaceRoot, AdmittedIdeHostCompatibility,
         * DetachedModelObservation) -> DetachedModelCapture`.
         *
         * Establishes one exact-root, complete, bounded, deterministically ordered immutable
         * module/source-root/Gradle-owner/SDK/classpath model. The closed expected failure is
         * [DetachedModelCaptureFailure]. Raw path and identity extraction is permitted only at
         * the live IntelliJ observation boundary.
         */
        internal fun admit(
            expectedRoot: CanonicalWorkspaceRoot,
            compatibility: AdmittedIdeHostCompatibility,
            observation: DetachedModelObservation,
        ): DetachedModelCapture {
            val boundary = when (observation) {
                is DetachedModelObservation.Observed -> observation.boundary
                is DetachedModelObservation.Rejected -> return rejected(observation.failure)
            }
            return refineDetachedModel(expectedRoot, compatibility, boundary)
        }

        /**
         * Proof transition: `(ExactObservedWorkspaceRoot, AdmittedIdeHostCompatibility,
         * RefinedDetachedModules) -> DetachedIdeWorkspaceModel`.
         *
         * Establishes an unmodifiable model surface whose module aggregate proves non-emptiness,
         * boundedness, unique names, and unambiguous source-root ownership. No further raw
         * extraction is permitted beyond the live adapter boundary.
         */
        internal fun captured(
            root: ExactObservedWorkspaceRoot,
            compatibility: AdmittedIdeHostCompatibility,
            modules: RefinedDetachedModules,
        ): DetachedIdeWorkspaceModel = DetachedIdeWorkspaceModel(root, compatibility, modules)
    }
}

internal sealed interface DetachedModelObservation {
    data class Observed(val boundary: DetachedModelBoundary) : DetachedModelObservation
    data class Rejected(
        val failure: DetachedModelCaptureFailure,
    ) : DetachedModelObservation
}

internal data class DetachedModelBoundary(
    val disposed: Boolean,
    val smart: Boolean,
    val projectRoot: String?,
    val gradleModelComplete: Boolean,
    val modules: List<DetachedModuleBoundary>,
)

internal data class DetachedModuleBoundary(
    val disposed: Boolean,
    val name: String,
    val gradleOwned: Boolean,
    val gradleBuildRoot: String?,
    val gradleProjectRoot: String?,
    val gradleProjectIdentity: String?,
    val sourceRoots: List<DetachedSourceRootBoundary>,
    val sdk: DetachedSdkBoundary?,
    val classpath: List<DetachedClasspathBoundary>,
)

internal data class DetachedSourceRootBoundary(
    val path: String?,
    val kind: DetachedSourceRootKind?,
    val provenance: DetachedSourceRootProvenance?,
)

internal data class DetachedSdkBoundary(
    val name: String,
    val type: String,
    val version: String?,
)

internal data class DetachedClasspathBoundary(
    val url: String,
)

private fun rejected(failure: DetachedModelCaptureFailure) =
    DetachedModelCapture.Rejected(failure)

private fun <Value> immutableList(values: List<Value>): List<Value> =
    Collections.unmodifiableList(ArrayList(values))

internal object DetachedModelLimits {
    const val MAX_CACHED_GRADLE_MODELS = 8
    const val MAX_MODULES = 256
    const val MAX_SOURCE_ROOTS_PER_MODULE = 256
    const val MAX_CLASSPATH_ENTRIES_PER_MODULE = 2_048
    const val MAX_IDENTITY_CHARS = 512
    const val MAX_PATH_CHARS = 4_096
    const val MAX_CLASSPATH_URL_CHARS = 8_192
}
