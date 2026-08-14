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
                 * Proof transition:
                 * `ServerDeadline.Ordinary -> ServerDeadline.WorkspaceTransition`.
                 *
                 * Derives a finite outer deadline for the complete recovery
                 * dispatch: one ordinary graph pass may discover incomplete
                 * coverage, reconciliation may consume its one-hour maximum,
                 * and a second ordinary graph pass must publish the response.
                 */
                fun derive(ordinary: Ordinary): WorkspaceTransition {
                    val graphPassBudget = Math.multiplyExact(
                        ordinary.timeoutMillis,
                        SEMANTIC_GRAPH_PASS_COUNT,
                    )
                    return WorkspaceTransition(
                        Math.addExact(MAXIMUM_RECONCILIATION_MILLIS, graphPassBudget),
                    )
                }

                private const val SEMANTIC_GRAPH_PASS_COUNT = 2L
                private const val MAXIMUM_RECONCILIATION_MILLIS = 60L * 60 * 1_000
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
        fun derive(method: String, config: AnalysisServerConfig): RpcRequestWaitPolicy = when (
            RpcDeadlineAuthority.derive(method)
        ) {
            RpcDeadlineAuthority.SEMANTIC_GRAPH_SERVER -> ServerDeadline.WorkspaceTransition.derive(
                ServerDeadline.Ordinary.derive(config.effectiveRequestTimeoutMillis),
            )
            RpcDeadlineAuthority.BACKEND_PROGRESS -> BackendProgressDeadline
            RpcDeadlineAuthority.ORDINARY_SERVER ->
                ServerDeadline.Ordinary.derive(config.effectiveRequestTimeoutMillis)
        }
    }
}

private enum class RpcDeadlineAuthority {
    SEMANTIC_GRAPH_SERVER,
    BACKEND_PROGRESS,
    ORDINARY_SERVER,
    ;

    companion object {
        /**
         * Boundary transition: `String -> RpcDeadlineAuthority`.
         *
         * Derives exactly one deadline authority from the transport primitive.
         * The result intentionally need not retain the method string: it is the
         * stronger, constrained fact consumed by exhaustive policy selection.
         */
        fun derive(method: String): RpcDeadlineAuthority = when (method) {
            "raw/semantic-graph" -> SEMANTIC_GRAPH_SERVER
            "raw/workspace-refresh",
            "raw/apply-edits",
            "raw/exact-file-image-cas",
            "raw/recover-mutation-scratch",
            "change/apply-add-declaration",
            -> BACKEND_PROGRESS
            else -> ORDINARY_SERVER
        }
    }
}
