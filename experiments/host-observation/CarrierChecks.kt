import java.util.UUID

/** Runs against a byte-for-byte copy of observer.kts declarations; no model of its owner. */
fun main() {
    check(kastEngineFailure(IllegalStateException("opaque", OutOfMemoryError())) == KastEngineFailure.RESOURCE_EXHAUSTED)
    check(kastEngineFailure(IllegalStateException("unknown")) == KastEngineFailure.EXECUTION_UNCONFIRMED)
    val gate = KastHostObservation.CallbackGate()
    check(gate.enter() === KastHostObservation.CallbackGate.Admission.Closed)
    gate.open()
    val permit = (gate.enter() as KastHostObservation.CallbackGate.Admission.Admitted).permit
    gate.revoke()
    check(gate.active() == 1) { "Revocation erased an admitted callback" }
    check(gate.enter() === KastHostObservation.CallbackGate.Admission.Closed)
    permit.close(); permit.close()
    check(gate.active() == 0) { "Permit retirement was not idempotent" }
    gate.open()
    check(gate.enter() === KastHostObservation.CallbackGate.Admission.Closed) { "Revoked gate reopened" }

    val id = "00000000-0000-4000-8000-000000000001"
    val accepted = KastHostObservation.parseStop("""{"type":"STOP","version":1,"sessionId":"$id"}""")
    check(accepted is KastHostObservation.Result.Admitted && accepted.value == UUID.fromString(id))
    val duplicate = """{"type":"WRONG","type":"STOP","version":1,"sessionId":"$id"}"""
    check(KastHostObservation.parseStop(duplicate) is KastHostObservation.Result.Rejected) { "Ambiguous duplicate field authorized stop" }
    val saturated = KastHostObservation.CallbackGate()
    saturated.open()
    val entries = (1..33).map { saturated.enter() }
    check(saturated.active() <= 32) { "Callback admission exceeded its bound" }
    entries.filterIsInstance<KastHostObservation.CallbackGate.Admission.Admitted>().forEach { it.permit.close() }
    check(saturated.active() == 0)
    val request = """{"type":"PREFLIGHT","version":1,"requestId":"$id","artifactSha256":"${"a".repeat(64)}","expectedBuild":"262.10315.125","outputDirectory":"/private/tmp/fixture"}"""
    check(KastHostObservation.Request.parse(request) is KastHostObservation.Result.Admitted)
    for (invalid in listOf(request.replace("\"version\":1", "\"version\":true"),
        request.replace("\"version\":1", "\"version\":2"),
        request.replace("\"type\":\"PREFLIGHT\"", "\"type\":\"PREFLIGHT\",\"type\":\"PREFLIGHT\""),
        request.dropLast(1), request.replace("262.10315.125", "262.1"))) {
        check(KastHostObservation.Request.parse(invalid) is KastHostObservation.Result.Rejected)
    }
    val directory = java.nio.file.Files.createTempDirectory("kast-carrier-boundary-")
    val input = directory.resolve("request.json")
    val alias = directory.resolve("alias.json")
    try {
        java.nio.file.Files.writeString(input, request)
        check(KastHostObservation.readRequest(input) is KastHostObservation.Result.Admitted)
        java.nio.file.Files.createSymbolicLink(alias, input)
        check(KastHostObservation.readRequest(alias) is KastHostObservation.Result.Rejected)
        java.nio.file.Files.write(input, byteArrayOf(0xff.toByte()))
        check(KastHostObservation.readRequest(input) is KastHostObservation.Result.Rejected)
        java.nio.file.Files.write(input, ByteArray(16 * 1024 + 1))
        check(KastHostObservation.readRequest(input) is KastHostObservation.Result.Rejected)
    } finally {
        java.nio.file.Files.deleteIfExists(alias)
        java.nio.file.Files.deleteIfExists(input)
        java.nio.file.Files.delete(directory)
    }
    println("carrier-checks: passed")
}
