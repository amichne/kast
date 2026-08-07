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
         * Operations that can enter workspace reconciliation are bounded by
         * the indexer's progress policy; every other method retains the
         * effective ordinary server deadline.
         */
        fun derive(method: String, config: AnalysisServerConfig): RpcRequestWaitPolicy =
            if (BackendProgressRpcMethod.resolve(method) != null) {
                BackendProgressDeadline
            } else {
                ServerDeadline.derive(config.effectiveRequestTimeoutMillis)
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
         * backend contract can wait for post-mutation reconciliation. `null`
         * means the method has no such authority and must keep the ordinary
         * server deadline.
         */
        fun resolve(method: String): BackendProgressRpcMethod? = entries.singleOrNull { candidate ->
            candidate.protocolName == method
        }
    }
}
