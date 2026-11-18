package parcel.carriers

import parcel.audit.ParcelId
import parcel.dispatch.Carrier
import parcel.dispatch.Label

class NetworkCarrier(private val journal: MutableList<String>) : Carrier {
    override fun prepare(parcel: ParcelId): Label {
        journal.add("carrier:network")
        return Label("NET-${parcel.value}")
    }
}

class CounterCarrier(private val journal: MutableList<String>) : Carrier {
    override fun prepare(parcel: ParcelId): Label {
        journal.add("carrier:counter")
        return Label("COUNTER-${parcel.value}")
    }
}
