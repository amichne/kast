package parcel.replay

import parcel.audit.ActorId
import parcel.audit.AuditSink as ParcelAudit
import parcel.audit.ParcelId

class ImportReplay(private val audit: ParcelAudit) {
    fun replay(parcel: ParcelId, importer: ActorId) {
        audit.record("REPLAYED", parcel.value, importer.value)
    }
}
