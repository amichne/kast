package oracle

import parcel.api.DispatchEndpoint
import parcel.audit.ParcelId
import parcel.dispatch.DispatchResult
import parcel.dispatch.Label
import parcel.dispatch.createDispatcher

fun main() {
    val journal = mutableListOf<String>()
    val dispatcher = createDispatcher(journal)
    val endpoint = DispatchEndpoint(dispatcher)
    val target = ParcelId("P-104")
    check(endpoint.dispatch(target, remote = true) == DispatchResult.Queued(Label("NET-P-104")))
    check(journal == listOf("ledger:claim", "carrier:network", "queue:NET-P-104"))
    journal.clear()
    check(endpoint.dispatch(target, remote = true) == DispatchResult.Duplicate)
    check(journal == listOf("ledger:claim"))
    journal.clear()
    check(dispatcher.dispatch(ParcelId("P-105")) == DispatchResult.Queued(Label("COUNTER-P-105")))
    check(journal == listOf("ledger:claim", "carrier:counter", "queue:COUNTER-P-105"))
    println("PASS")
}
