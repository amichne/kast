package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.server.AnalysisServerConfig

internal sealed interface RpcRequestWaitPolicy {
    @ConsistentCopyVisibility
    data class ServerDeadline private constructor(
        val timeoutMillis: Long,
    ) : RpcRequestWaitPolicy {
        companion object {
            /**
             * Proof transition: `Long -> ServerDeadline`.
             *
             * Refines a raw configured timeout into a strictly positive
             * deadline that is safe to pass to the coroutine timeout boundary.
             */
            fun derive(timeoutMillis: Long): ServerDeadline {
                require(timeoutMillis > 0) { "RPC server deadline must be positive" }
                return ServerDeadline(timeoutMillis)
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
         * Workspace refresh is bounded by the indexer's progress policy;
         * every other method retains the effective ordinary server deadline.
         */
        fun derive(method: String, config: AnalysisServerConfig): RpcRequestWaitPolicy =
            if (method == "raw/workspace-refresh") {
                BackendProgressDeadline
            } else {
                ServerDeadline.derive(config.effectiveRequestTimeoutMillis)
            }
    }
}
