package io.github.amichne.kast.appserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class CoordinatorControlAction {
    STATUS,
    DEMAND,
    RETIRED,
}

@Serializable internal data class CoordinatorControlRequest(val action: CoordinatorControlAction, val root: String = "")

@Serializable
internal data class CoordinatorControlRejection(val failure: WorkerControlFailure, val status: String = "REJECTED")

internal fun rejectedCoordinatorControl(failure: WorkerControlFailure): String = Json {
    encodeDefaults = true
}
    .encodeToString(CoordinatorControlRejection(failure))

enum class WorkerControlFailure {
    ISOLATED_RUNTIME_RETIRED,
    UNAVAILABLE,
    INVALID_REQUEST,
    IDENTITY_REJECTED,
    SERVICE_IDENTITY_REJECTED,
    WORKSPACE_CONFIGURATION_REJECTED,
    COORDINATOR_IDENTITY_REJECTED,
    WORKER_BINDING_IDENTITY_REJECTED,
    REGISTRATION_REJECTED,
    RECEIPT_REJECTED,
    LIFECYCLE_TRANSITION,
    RECOVERY_REQUIRED,
    CAPACITY_REJECTED,
    STARTUP_REJECTED,
    DEADLINE_EXCEEDED,
    RETIREMENT_UNPROVEN,
}
