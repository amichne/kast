package oracle

import parcel.analytics.AuditSink as AnalyticsSink
import parcel.analytics.Dashboard
import parcel.audit.ActorId
import parcel.audit.AuditEvent
import parcel.audit.AuditKind
import parcel.audit.AuditSink
import parcel.audit.JournalAuditSink
import parcel.audit.ParcelId
import parcel.operations.Booking
import parcel.operations.Cancellation
import parcel.replay.ImportReplay

fun main() {
    val parcel = ParcelId("parcel-alpha")
    val clerk = ActorId("actor-zen")
    val operator = ActorId("actor-bex")
    val importer = ActorId("actor-nova")
    val journal = JournalAuditSink()
    val calls = mutableListOf<AuditEvent>()
    val sink = object : AuditSink {
        override fun record(event: AuditEvent) {
            calls += event
            journal.record(event)
        }
    }

    Booking(sink).confirm(parcel, clerk)
    Cancellation(sink).cancel(parcel, operator)
    ImportReplay(sink).replay(parcel, importer)

    check(calls == listOf(
        AuditEvent(AuditKind.BOOKED, parcel, clerk),
        AuditEvent(AuditKind.CANCELLED, parcel, operator),
        AuditEvent(AuditKind.REPLAYED, parcel, importer),
    ))
    check(journal.snapshot() == listOf(
        "BOOKED|parcel-alpha|actor-zen",
        "CANCELLED|parcel-alpha|actor-bex",
        "REPLAYED|parcel-alpha|actor-nova",
    ))
    check(AuditSink::class.java.declaredMethods.filter { it.name == "record" }.map { it.parameterTypes.toList() } ==
        listOf(listOf(AuditEvent::class.java)))

    val analytics = AnalyticsSink()
    Dashboard(analytics).view("order-detail", "terminal-7")
    analytics.record("click", "order-list", "terminal-9")
    check(analytics.snapshot() == listOf("view:order-detail:terminal-7", "click:order-list:terminal-9"))
    println("PASS")
}
