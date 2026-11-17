package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Serializable

private const val SIGINT_EXIT = 130
private const val SIGTERM_EXIT = 143

@Serializable
enum class ContainerFailure(val exit: Int = 1) {
    INVALID_NAME,
    INVALID_ID,
    INVALID_WORKSPACE,
    INVALID_GRADLE_HOME,
    UNSUPPORTED_PLATFORM,
    NOT_FOUND,
    AMBIGUOUS_WORKSPACE,
    NAME_EXISTS,
    WORKSPACE_EXISTS,
    INVALID_RECORD,
    FOREIGN_STATE,
    FILESYSTEM_UNAVAILABLE,
    FENCE_REJECTED,
    BUSY,
    LEASE_CLOSED,
    PREPARATION_WAIT_EXPIRED,
    INVALID_CONFIGURATION,
    /** Explicit caller settings select a Host other than the workspace's registered Host. */
    CONFIGURATION_CONFLICT,
    CAPACITY_EXCEEDED,
    STOPPED,
    RUNTIME_UNAVAILABLE,
    RUNTIME_MISMATCH,
    INVALID_ARCHIVE,
    DIGEST_MISMATCH,
    DOWNLOAD_FAILED,
    PREPARATION_FAILED,
    SHUTDOWN_FAILED,
    CANCELLED_INTERRUPT(SIGINT_EXIT),
    CANCELLED_TERMINATE(SIGTERM_EXIT),
}
