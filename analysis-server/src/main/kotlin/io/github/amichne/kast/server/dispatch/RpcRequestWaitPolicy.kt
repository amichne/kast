package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.server.AnalysisServerConfig

internal sealed interface RpcRequestWaitPolicy {
    sealed interface ServerDeadline : RpcRequestWaitPolicy {
        val timeoutMillis: Long

        @ConsistentCopyVisibility
        data class Ordinary private constructor(
            override val timeoutMillis: Long,
        ) : ServerDeadline {
            companion object {
                /**
                 * Proof transition: `Long -> ServerDeadline.Ordinary`.
                 *
                 * Refines a raw configured timeout into a strictly positive
                 * ordinary deadline safe for the coroutine timeout boundary.
                 */
                fun derive(timeoutMillis: Long): Ordinary {
                    require(timeoutMillis > 0) { "RPC server deadline must be positive" }
                    return Ordinary(timeoutMillis)
                }
            }
        }

        @ConsistentCopyVisibility
        data class WorkspaceTransition private constructor(
            override val timeoutMillis: Long,
        ) : ServerDeadline {
            companion object {
                /**
                 * Proof transition: `Long -> ServerDeadline.WorkspaceTransition`.
                 *
                 * Refines the positive ordinary timeout into a finite outer
                 * deadline covering the backend's one-hour reconciliation
                 * maximum plus transport reserve, without shortening a larger
                 * configured deadline.
                 */
                fun derive(ordinaryTimeoutMillis: Long): WorkspaceTransition {
                    require(ordinaryTimeoutMillis > 0) { "RPC server deadline must be positive" }
                    return WorkspaceTransition(
                        maxOf(ordinaryTimeoutMillis, MINIMUM_TIMEOUT_MILLIS),
                    )
                }

                private const val MINIMUM_TIMEOUT_MILLIS = 60L * 60 * 1_000 + 5_000
            }
        }
    }

    /** The backend operation owns a finite, progress-aware deadline. */
    data object BackendProgressDeadline : RpcRequestWaitPolicy

    companion object {
        /**
         * Boundary transition: `(String, AnalysisServerConfig) -> RpcRequestWaitPolicy`.
         *
         * Converts the JSON-RPC method name to a closed deadline authority.
         * Semantic-graph recovery receives a finite transition-aware outer
         * deadline. Mutation operations whose entire dispatch is governed by
         * backend progress retain backend-owned deadline authority; every
         * other method receives the effective ordinary server deadline.
         */
        fun derive(method: String, config: AnalysisServerConfig): RpcRequestWaitPolicy = when {
            TransitionAwareRpcMethod.resolve(method) != null ->
                ServerDeadline.WorkspaceTransition.derive(config.effectiveRequestTimeoutMillis)
            BackendProgressRpcMethod.resolve(method) != null -> BackendProgressDeadline
            else -> ServerDeadline.Ordinary.derive(config.effectiveRequestTimeoutMillis)
        }
    }
}

private enum class TransitionAwareRpcMethod(val protocolName: String) {
    SEMANTIC_GRAPH("raw/semantic-graph"),
    ;

    companion object {
        /**
         * Boundary transition: `String -> TransitionAwareRpcMethod?`.
         *
         * Refines the transport method primitive into the closed set requiring
         * a finite server deadline large enough for nested workspace
         * reconciliation.
         */
        fun resolve(method: String): TransitionAwareRpcMethod? = entries.singleOrNull { candidate ->
            candidate.protocolName == method
        }
    }
}

private enum class BackendProgressRpcMethod(val protocolName: String) {
    WORKSPACE_REFRESH("raw/workspace-refresh"),
    APPLY_EDITS("raw/apply-edits"),
    EXACT_FILE_IMAGE_CAS("raw/exact-file-image-cas"),
    RECOVER_MUTATION_SCRATCH("raw/recover-mutation-scratch"),
    ;

    companion object {
        /**
         * Boundary transition: `String -> BackendProgressRpcMethod?`.
         *
         * Refines the transport method primitive into the closed set whose
         * backend contract can wait for progress-bounded workspace
         * reconciliation. `null` means the method has no such authority and
         * must keep the ordinary server deadline.
         */
        fun resolve(method: String): BackendProgressRpcMethod? = entries.singleOrNull { candidate ->
            candidate.protocolName == method
        }
    }
}
