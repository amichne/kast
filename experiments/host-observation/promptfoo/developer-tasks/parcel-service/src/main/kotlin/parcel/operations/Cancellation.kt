package parcel.operations

import parcel.audit.ActorId
import parcel.audit.AuditSink
import parcel.audit.ParcelId

class Cancellation(private val audit: AuditSink) {
    fun cancel(parcel: ParcelId, operator: ActorId) {
        audit.record(kind = "CANCELLED", parcelId = parcel.value, actorId = operator.value)
    }
}
