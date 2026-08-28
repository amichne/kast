package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private const val MAX_STATE_PATH_BYTES = 4096

enum class KastUserStateRootFailure {
    BLANK,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    CONTAINS_NUL,
    TOO_LONG,
}

/** Detached, canonical root beneath which Kast may create durable per-workspace state. */
@JvmInline
value class KastUserStateRoot private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<KastUserStateRoot,
         * KastUserStateRootFailure>`.
         *
         * Establishes a bounded absolute lexical path without aliases or traversal. The value is
         * retained as detached text; a physical state adapter alone may turn it into a raw path.
         */
        fun parse(raw: String): Refinement<KastUserStateRoot, KastUserStateRootFailure> = when {
            raw.isBlank() -> Refinement.Rejected(KastUserStateRootFailure.BLANK)
            '\u0000' in raw -> Refinement.Rejected(KastUserStateRootFailure.CONTAINS_NUL)
            !raw.startsWith('/') -> Refinement.Rejected(KastUserStateRootFailure.NOT_ABSOLUTE)
            raw.toByteArray(StandardCharsets.UTF_8).size > MAX_STATE_PATH_BYTES ->
                Refinement.Rejected(KastUserStateRootFailure.TOO_LONG)
            !isCanonicalAbsolutePath(raw) ->
                Refinement.Rejected(KastUserStateRootFailure.NOT_NORMALIZED)
            else -> Refinement.Refined(KastUserStateRoot(raw))
        }
    }
}

@JvmInline
value class TopologyDatabaseLocation internal constructor(
    private val value: String,
) {
    /** Detached text may leave only when the SQLite adapter creates its physical capability. */
    fun valueAtSqliteBoundary(): String = value
}

@JvmInline
value class MutationDatabaseLocation internal constructor(
    private val value: String,
) {
    /** Detached text may leave only when the SQLite adapter creates its physical capability. */
    fun valueAtSqliteBoundary(): String = value
}

enum class HostedWorkspaceStateLocationFailure {
    LOCATION_TOO_LONG,
}

/** Exact-root durable state authority, deliberately separate from ephemeral endpoint ownership. */
class HostedWorkspaceStateLocation private constructor(
    val topologyDatabase: TopologyDatabaseLocation,
    val mutationDatabase: MutationDatabaseLocation,
) {
    companion object {
        /**
         * Proof transition: `(KastUserStateRoot, CanonicalWorkspaceRoot) -> Refinement<
         * HostedWorkspaceStateLocation, HostedWorkspaceStateLocationFailure>`.
         *
         * Binds both databases to one full SHA-256 root digest under the durable user-state tree.
         * No endpoint socket path or raw filesystem capability enters this representation.
         */
        fun locate(
            userStateRoot: KastUserStateRoot,
            workspace: CanonicalWorkspaceRoot,
        ): Refinement<HostedWorkspaceStateLocation, HostedWorkspaceStateLocationFailure> {
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(workspace.value.toByteArray(StandardCharsets.UTF_8)),
            )
            val directory = userStateRoot.child("state/workspaces/$digest")
            val topology = "$directory/topology.sqlite"
            val mutation = "$directory/mutation.sqlite"
            if (
                topology.toByteArray(StandardCharsets.UTF_8).size > MAX_STATE_PATH_BYTES ||
                mutation.toByteArray(StandardCharsets.UTF_8).size > MAX_STATE_PATH_BYTES
            ) {
                return Refinement.Rejected(
                    HostedWorkspaceStateLocationFailure.LOCATION_TOO_LONG,
                )
            }
            return Refinement.Refined(
                HostedWorkspaceStateLocation(
                    TopologyDatabaseLocation(topology),
                    MutationDatabaseLocation(mutation),
                ),
            )
        }
    }
}

private fun KastUserStateRoot.child(relative: String): String =
    if (value == "/") "/$relative" else "$value/$relative"

private fun isCanonicalAbsolutePath(raw: String): Boolean =
    raw == "/" || !raw.endsWith('/') && raw.split('/').drop(1).none { segment ->
        segment.isEmpty() || segment == "." || segment == ".."
    }
