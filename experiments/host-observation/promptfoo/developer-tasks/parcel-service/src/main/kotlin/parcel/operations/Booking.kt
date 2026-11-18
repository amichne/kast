package parcel.operations

import parcel.audit.ActorId
import parcel.audit.AuditSink
import parcel.audit.ParcelId

class Booking(private val audit: AuditSink) {
    fun confirm(parcel: ParcelId, clerk: ActorId) {
        audit.record("BOOKED", parcel.value, clerk.value)
    }
}
