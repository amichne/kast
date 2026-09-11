package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.ProjectReadEpochVfsPath
import io.github.amichne.kast.workspace.intellij.read.ProjectReadEpochVfsRoot
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
internal enum class HostedVfsEventKind {
    CREATE,
    DELETE,
    COPY,
    MOVE,
    RENAME,
    PROPERTY,
    CONTENT,
    OTHER,
}

@Serializable
internal enum class HostedVfsEventOrigin {
    REFRESH,
    IDE,
}

@Serializable
internal enum class HostedVfsPathCategory {
    IDE_SETTINGS_PATH,
    GRADLE_CACHE_PATH,
    BUILD_PATH,
    GRADLE_SCRIPT,
    KOTLIN_FILE,
    JAVA_FILE,
    OTHER_PROJECT_PATH,
}

@Serializable
internal enum class HostedVfsObservationFailure {
    BATCH_LIMIT,
    PATH_UNPROVEN,
}

/** Raw platform event paths are confined to observation admission and never enter a receipt. */
internal data class HostedVfsEventBoundary(
    val kind: HostedVfsEventKind,
    val origin: HostedVfsEventOrigin,
    val paths: List<String>,
)

internal data class HostedVfsCoordinate(
    val kind: HostedVfsEventKind,
    val origin: HostedVfsEventOrigin,
    val category: HostedVfsPathCategory,
)

internal class HostedVfsPathCount private constructor(val value: Int) {
    companion object {
        fun count(values: List<HostedVfsCoordinate>): HostedVfsPathCount = HostedVfsPathCount(values.size)
    }
}

internal data class HostedVfsCount(val coordinate: HostedVfsCoordinate, val paths: HostedVfsPathCount)

internal sealed interface HostedVfsBatchEvidence {
    data object OutsideRoot : HostedVfsBatchEvidence

    class Observed private constructor(val counts: List<HostedVfsCount>) : HostedVfsBatchEvidence {
        companion object {
            fun from(coordinates: List<HostedVfsCoordinate>): HostedVfsBatchEvidence =
                if (coordinates.isEmpty()) OutsideRoot
                else
                    Observed(
                        coordinates
                            .groupBy { it }
                            .map { (key, values) -> HostedVfsCount(key, HostedVfsPathCount.count(values)) }
                    )
        }
    }

    data class Rejected(val failure: HostedVfsObservationFailure) : HostedVfsBatchEvidence
}

internal fun observeHostedVfsBatch(
    root: CanonicalWorkspaceRoot,
    events: List<HostedVfsEventBoundary>,
    limits: ReadLimits,
): HostedVfsBatchEvidence {
    if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value)
        return HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.BATCH_LIMIT)
    val rootIdentity = ProjectReadEpochVfsRoot.from(root)
    val rootPath = Path.of(root.value)
    val coordinates = mutableListOf<HostedVfsCoordinate>()
    for (event in events) {
        if (event.paths.size !in 1..MAX_EVENT_PATHS)
            return HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.PATH_UNPROVEN)
        for (raw in event.paths) {
            val path =
                when (val admitted = ProjectReadEpochVfsPath.admit(raw, limits)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.PATH_UNPROVEN)
                }
            if (rootIdentity.contains(path)) {
                coordinates +=
                    HostedVfsCoordinate(event.kind, event.origin, category(rootPath.relativize(Path.of(raw))))
            }
        }
    }
    return HostedVfsBatchEvidence.Observed.from(coordinates)
}

/** Syntactic path labels are observations; they do not decide source membership or epoch authority. */
private fun category(relative: Path): HostedVfsPathCategory {
    val parts = relative.map(Path::toString)
    val name = relative.fileName.toString()
    return when {
        ".idea" in parts -> HostedVfsPathCategory.IDE_SETTINGS_PATH
        ".gradle" in parts -> HostedVfsPathCategory.GRADLE_CACHE_PATH
        "build" in parts -> HostedVfsPathCategory.BUILD_PATH
        name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> HostedVfsPathCategory.GRADLE_SCRIPT
        name.endsWith(".kt") || name.endsWith(".kts") -> HostedVfsPathCategory.KOTLIN_FILE
        name.endsWith(".java") -> HostedVfsPathCategory.JAVA_FILE
        else -> HostedVfsPathCategory.OTHER_PROJECT_PATH
    }
}

private const val MAX_EVENT_PATHS = 2
