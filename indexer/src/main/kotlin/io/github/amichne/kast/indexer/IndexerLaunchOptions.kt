package io.github.amichne.kast.indexer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.Path

internal const val KAST_INDEXER_COMMAND_NAME = "kast-indexer"

enum class IndexerLaunchFailure {
    INVALID_COMMAND,
    MISSING_WORKSPACE_ROOT,
    DUPLICATE_WORKSPACE_ROOT,
    INVALID_WORKSPACE_ROOT,
    MISSING_SOCKET_PATH,
    DUPLICATE_SOCKET_PATH,
    INVALID_SOCKET_PATH,
    MISSING_RUNTIME_ID,
    DUPLICATE_RUNTIME_ID,
    INVALID_RUNTIME_ID,
    UNKNOWN_ARGUMENT,
}

/** Exact content identity supplied by the admitted control runtime. */
@JvmInline
value class IndexerRuntimeId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> IndexerRuntimeIdRefinement`.
         *
         * Establishes one canonical lowercase SHA-256 identity. The closed expected failure is
         * [IndexerRuntimeIdRefinement.Rejected]. Raw text may leave only for transport identity
         * admission and process diagnostics.
         */
        fun parse(raw: String): IndexerRuntimeIdRefinement =
            if (Regex("sha256:[0-9a-f]{64}").matches(raw)) {
                IndexerRuntimeIdRefinement.Refined(IndexerRuntimeId(raw))
            } else {
                IndexerRuntimeIdRefinement.Rejected
            }
    }
}

sealed interface IndexerRuntimeIdRefinement {
    data class Refined(val runtimeId: IndexerRuntimeId) : IndexerRuntimeIdRefinement
    data object Rejected : IndexerRuntimeIdRefinement
    data object NotRequested : IndexerRuntimeIdRefinement
}

/** Exact installed-process launch authority. */
class IndexerLaunchOptions private constructor(
    val workspaceRoot: Path,
    val socketPath: Path,
    val runtimeId: IndexerRuntimeId,
) {
    companion object {
        /**
         * Proof transition: `List<String> -> IndexerLaunchAdmission`.
         *
         * Establishes one canonical physical workspace root, one absolute normalized Unix socket
         * path, and one canonical semantic-runtime identity from the complete installed command
         * line. [IndexerLaunchFailure] is the closed expected failure. Raw paths and identity text
         * may leave only for runtime construction and the Unix-domain transport boundary.
         */
        fun admit(arguments: List<String>): IndexerLaunchAdmission {
            val failures = linkedSetOf<IndexerLaunchFailure>()
            if (arguments.firstOrNull() != KAST_INDEXER_COMMAND_NAME) {
                failures += IndexerLaunchFailure.INVALID_COMMAND
            }
            val rawWorkspaceRoots = arguments.argumentValues(WORKSPACE_ROOT_PREFIX)
            val rawSocketPaths = arguments.argumentValues(SOCKET_PATH_PREFIX)
            val rawRuntimeIds = arguments.argumentValues(RUNTIME_ID_PREFIX)
            when (rawWorkspaceRoots.size) {
                0 -> failures += IndexerLaunchFailure.MISSING_WORKSPACE_ROOT
                1 -> Unit
                else -> failures += IndexerLaunchFailure.DUPLICATE_WORKSPACE_ROOT
            }
            when (rawSocketPaths.size) {
                0 -> failures += IndexerLaunchFailure.MISSING_SOCKET_PATH
                1 -> Unit
                else -> failures += IndexerLaunchFailure.DUPLICATE_SOCKET_PATH
            }
            when (rawRuntimeIds.size) {
                0 -> failures += IndexerLaunchFailure.MISSING_RUNTIME_ID
                1 -> Unit
                else -> failures += IndexerLaunchFailure.DUPLICATE_RUNTIME_ID
            }
            if (arguments.drop(1).any { argument ->
                    !argument.startsWith(WORKSPACE_ROOT_PREFIX) &&
                        !argument.startsWith(SOCKET_PATH_PREFIX)
                        && !argument.startsWith(RUNTIME_ID_PREFIX)
                }
            ) {
                failures += IndexerLaunchFailure.UNKNOWN_ARGUMENT
            }

            val workspaceRoot = when (rawWorkspaceRoots.size) {
                1 -> rawWorkspaceRoots.single().canonicalWorkspaceRoot()
                else -> WorkspaceRootRefinement.NotRequested
            }
            if (workspaceRoot is WorkspaceRootRefinement.Rejected) {
                failures += IndexerLaunchFailure.INVALID_WORKSPACE_ROOT
            }
            val socketPath = when (rawSocketPaths.size) {
                1 -> rawSocketPaths.single().absoluteSocketPath()
                else -> SocketPathRefinement.NotRequested
            }
            if (socketPath is SocketPathRefinement.Rejected) {
                failures += IndexerLaunchFailure.INVALID_SOCKET_PATH
            }
            val runtimeId = when (rawRuntimeIds.size) {
                1 -> IndexerRuntimeId.parse(rawRuntimeIds.single())
                else -> IndexerRuntimeIdRefinement.NotRequested
            }
            if (runtimeId is IndexerRuntimeIdRefinement.Rejected) {
                failures += IndexerLaunchFailure.INVALID_RUNTIME_ID
            }
            if (failures.isNotEmpty()) return IndexerLaunchAdmission.Rejected(failures)
            return IndexerLaunchAdmission.Admitted(
                IndexerLaunchOptions(
                    workspaceRoot = (workspaceRoot as WorkspaceRootRefinement.Refined).path,
                    socketPath = (socketPath as SocketPathRefinement.Refined).path,
                    runtimeId = (runtimeId as IndexerRuntimeIdRefinement.Refined).runtimeId,
                ),
            )
        }
    }
}

sealed interface IndexerLaunchAdmission {
    data class Admitted(
        val options: IndexerLaunchOptions,
    ) : IndexerLaunchAdmission

    data class Rejected(
        val failures: Set<IndexerLaunchFailure>,
    ) : IndexerLaunchAdmission
}

private const val WORKSPACE_ROOT_PREFIX = "--workspace-root="
private const val SOCKET_PATH_PREFIX = "--socket-path="
private const val RUNTIME_ID_PREFIX = "--runtime-id="

/**
 * Boundary extraction: `List<String> + String -> List<String>`.
 *
 * Extracts only exact option values for immediate refinement by [IndexerLaunchOptions.admit]. Raw
 * strings do not leave that outer command-line boundary.
 */
private fun List<String>.argumentValues(prefix: String): List<String> =
    drop(1).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

private sealed interface WorkspaceRootRefinement {
    data class Refined(val path: Path) : WorkspaceRootRefinement
    data object Rejected : WorkspaceRootRefinement
    data object NotRequested : WorkspaceRootRefinement
}

private sealed interface SocketPathRefinement {
    data class Refined(val path: Path) : SocketPathRefinement
    data object Rejected : SocketPathRefinement
    data object NotRequested : SocketPathRefinement
}

/**
 * Proof transition: `String -> WorkspaceRootRefinement`.
 *
 * Establishes one absolute, normalized, physically canonical directory path.
 * [WorkspaceRootRefinement.Rejected] is the closed expected failure. The raw path may leave only
 * for physical filesystem admission.
 */
private fun String.canonicalWorkspaceRoot(): WorkspaceRootRefinement {
    if (isBlank()) return WorkspaceRootRefinement.Rejected
    val candidate = try {
        Path(this)
    } catch (_: RuntimeException) {
        return WorkspaceRootRefinement.Rejected
    }
    if (!candidate.isAbsolute) return WorkspaceRootRefinement.Rejected
    val normalized = candidate.normalize()
    val canonical = try {
        normalized.toRealPath()
    } catch (_: IOException) {
        return WorkspaceRootRefinement.Rejected
    } catch (_: SecurityException) {
        return WorkspaceRootRefinement.Rejected
    }
    return if (
        canonical == normalized && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
    ) {
        WorkspaceRootRefinement.Refined(canonical)
    } else {
        WorkspaceRootRefinement.Rejected
    }
}

/**
 * Proof transition: `String -> SocketPathRefinement`.
 *
 * Establishes one absolute normalized socket path with a final path component.
 * [SocketPathRefinement.Rejected] is the closed expected failure. The raw path may leave only for
 * the Unix-domain transport boundary.
 */
private fun String.absoluteSocketPath(): SocketPathRefinement {
    if (isBlank()) return SocketPathRefinement.Rejected
    val candidate = try {
        Path(this)
    } catch (_: RuntimeException) {
        return SocketPathRefinement.Rejected
    }
    return if (!candidate.isAbsolute || candidate.fileName == null) {
        SocketPathRefinement.Rejected
    } else {
        SocketPathRefinement.Refined(candidate.normalize())
    }
}
