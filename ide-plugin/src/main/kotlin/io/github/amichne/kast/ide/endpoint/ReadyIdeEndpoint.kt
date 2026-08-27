package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation

/** Sole capability proving a bound exact-root UDS and an admitted published descriptor. */
class ReadyIdeEndpoint internal constructor(
    val canonicalRoot: IdeEndpointCanonicalRoot,
    val descriptor: IdeEndpointDescriptorV2,
    val location: IdeEndpointLocation,
    private val transport: IdeEndpointTransport,
    private val ownership: ReadyEndpointOwnership,
) {
    /**
     * Proof transition: `one accepted UDS session -> IdeEndpointConnectionHandling` through the
     * transport created from the retained complete runtime before readiness.
     */
    internal suspend fun serveNext(): IdeEndpointConnectionHandling = transport.serveNext()

    /** Serves sequential sessions until the owned listening socket is no longer available. */
    internal suspend fun serveUntilClosed() {
        while (true) {
            when (val result = serveNext()) {
                IdeEndpointConnectionHandling.Served -> Unit
                is IdeEndpointConnectionHandling.Rejected -> when (result.failure) {
                    IdeEndpointConnectionFailure.ACCEPT_FAILED -> return
                    IdeEndpointConnectionFailure.INVALID_REQUEST_FRAME,
                    IdeEndpointConnectionFailure.DISPATCH_REJECTED,
                    IdeEndpointConnectionFailure.RESPONSE_WRITE_FAILED,
                    IdeEndpointConnectionFailure.SESSION_CLOSE_FAILED,
                    -> Unit
                }
            }
        }
    }

    /** Test boundary only; KVP-025 owns physical READY-path retirement. */
    @JvmSynthetic
    internal fun closeListeningSocketForTest() = ownership.socket.close()
}
