package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

/** Hosted transport failures shared by the producer and public process adapters. */
@Serializable
enum class HostedEndpointFailure {
    ADMISSION_CAPACITY_EXCEEDED,
    ADMISSION_DEADLINE_EXCEEDED,
    INVALID_REQUEST,
    REQUEST_TOO_LARGE,
    REQUEST_INCOMPLETE,
    IO_UNAVAILABLE,
    DEADLINE_EXCEEDED,
    WRONG_ROOT,
    OWNERSHIP_CONFLICT,
    DIRECTORY_REJECTED,
    SOCKET_UNAVAILABLE,
    PLATFORM_UNAVAILABLE,
    UNSAVED_DOCUMENTS,
    MODEL_REFRESH_REQUIRED,
    RESPONSE_REJECTED,
    RESULT_TOO_LARGE,
    APPROVAL_UNAVAILABLE,
    APPROVAL_REJECTED,
}
