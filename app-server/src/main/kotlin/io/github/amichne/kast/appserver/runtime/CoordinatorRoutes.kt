package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.util.concurrent.atomic.AtomicInteger

/** Local control and query routes share the existing connection budget and owned Unix socket. */
internal fun Route.coordinatorRoutes(control: CoordinatorControl) {
    val connections = AtomicInteger(0)
    controlRoute(BrokerControlRoute.RUNTIME.path, connections, control::handle)
    controlRoute(BrokerControlRoute.MANAGEMENT.path, connections, control::handleManagement)
}

private fun Route.controlRoute(
    path: String,
    connections: AtomicInteger,
    handle: suspend (DefaultWebSocketServerSession) -> Unit,
) {
    webSocket(path) {
        val count = connections.incrementAndGet()
        try {
            if (count > BrokerOperationalLimits.maximumRuntimeConnections)
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "runtime connection limit exceeded"))
            else handle(this)
        } finally {
            connections.decrementAndGet()
        }
    }
}
