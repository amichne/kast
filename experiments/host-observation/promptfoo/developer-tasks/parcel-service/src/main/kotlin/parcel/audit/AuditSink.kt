package parcel.audit

enum class AuditKind { BOOKED, CANCELLED, REPLAYED }

@JvmInline
value class ParcelId(val value: String)

@JvmInline
value class ActorId(val value: String)

interface AuditSink {
    fun record(kind: String, parcelId: String, actorId: String)
}

class JournalAuditSink : AuditSink {
    private val entries = mutableListOf<String>()

    override fun record(kind: String, parcelId: String, actorId: String) {
        entries += "$kind|$parcelId|$actorId"
    }

    fun snapshot(): List<String> = entries.toList()
}
