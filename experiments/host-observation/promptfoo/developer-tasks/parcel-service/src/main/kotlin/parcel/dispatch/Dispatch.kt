package parcel.dispatch

import parcel.audit.ParcelId
import parcel.carriers.CounterCarrier
import parcel.carriers.NetworkCarrier

enum class Lane { NETWORK, COUNTER }

data class DispatchRequest(val parcel: ParcelId, val lane: Lane)

data class Label(val code: String)

fun interface Carrier {
    fun prepare(parcel: ParcelId): Label
}

sealed interface DispatchResult {
    data class Queued(val label: Label) : DispatchResult
    data object Duplicate : DispatchResult
}

class DispatchLedger(private val journal: MutableList<String>) {
    private val seen = mutableSetOf<ParcelId>()

    fun claim(parcel: ParcelId): Boolean {
        journal.add("ledger:claim")
        return seen.add(parcel)
    }
}

class DispatchQueue(private val journal: MutableList<String>) {
    fun enqueue(label: Label): DispatchResult.Queued {
        journal.add("queue:${label.code}")
        return DispatchResult.Queued(label)
    }
}

class Dispatcher(
    private val ledger: DispatchLedger,
    private val network: Carrier,
    private val counter: Carrier,
    private val queue: DispatchQueue,
) {
    fun dispatch(parcel: ParcelId): DispatchResult = dispatch(DispatchRequest(parcel, Lane.COUNTER))

    fun dispatch(request: DispatchRequest): DispatchResult =
        if (ledger.claim(request.parcel)) {
            val carrier = when (request.lane) {
                Lane.NETWORK -> network
                Lane.COUNTER -> counter
            }
            queue.enqueue(carrier.prepare(request.parcel))
        } else {
            DispatchResult.Duplicate
        }
}

fun createDispatcher(journal: MutableList<String>): Dispatcher =
    Dispatcher(DispatchLedger(journal), NetworkCarrier(journal), CounterCarrier(journal), DispatchQueue(journal))
