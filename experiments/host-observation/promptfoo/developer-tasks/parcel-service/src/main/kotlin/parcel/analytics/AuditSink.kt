package parcel.analytics

class AuditSink {
    private val entries = mutableListOf<String>()

    fun record(kind: String, parcelId: String, actorId: String) {
        entries += "$kind:$parcelId:$actorId"
    }

    fun snapshot(): List<String> = entries.toList()
}

class Dashboard(private val audit: AuditSink) {
    fun view(page: String, browser: String) {
        audit.record("view", page, browser)
    }
}
