package io.github.amichne.kast.appserver

import kotlinx.serialization.encodeToString

/** Typed codecs and assertions for a real native coordinator, without a substituted coordinator or upstream. */
object NativeCoordinatorProbe {
    val route: String = DaemonManagementProtocol.route

    fun statusRequest(): String =
        DaemonManagementProtocol.json.encodeToString<DaemonManagementRequest>(DaemonManagementRequest.Status())

    fun ready(raw: String, identity: String): Boolean =
        when (val state = BROKER_SERVICE_STATE_JSON.decodeFromString<BrokerServiceStateDocument>(raw)) {
            is BrokerServiceStateDocument.Starting -> {
                check(state.serviceIdentity == identity)
                false
            }
            is BrokerServiceStateDocument.Ready -> {
                check(state.schemaVersion == 3)
                check(state.serviceIdentity == identity)
                true
            }
            is BrokerServiceStateDocument.Rejected -> error("Native coordinator rejected: ${state.failure}")
        }

    fun assertReadyWithPendingHost(raw: String) {
        val response = DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(raw)
        check(response is DaemonManagementResponse.Status) { "Expected coordinator status: $response" }
        check(response.coordinator.status == CoordinatorServiceState.READY)
        check(response.coordinator.hostAttachment == CoordinatorHostAttachment.PENDING)
    }
}
