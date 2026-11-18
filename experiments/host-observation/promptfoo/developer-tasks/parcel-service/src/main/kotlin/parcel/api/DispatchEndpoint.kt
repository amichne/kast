package parcel.api

import parcel.audit.ParcelId
import parcel.dispatch.DispatchRequest
import parcel.dispatch.DispatchResult
import parcel.dispatch.Dispatcher
import parcel.dispatch.Lane

class DispatchEndpoint(private val dispatcher: Dispatcher) {
    fun dispatch(parcel: ParcelId, remote: Boolean): DispatchResult {
        val lane = if (remote) Lane.NETWORK else Lane.COUNTER
        return dispatcher.dispatch(DispatchRequest(parcel, lane))
    }
}
