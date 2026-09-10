// Faults and barriers around the actual carrier owner, only for explicit hosted tests.
class HostAcceptanceHooks(private val directory: java.nio.file.Path) : KastHostObservation.Hooks {
    private val data = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(directory.resolve("test.json"))).asJsonObject
    private val mode = data.get("mode").asString
    private val held = java.util.concurrent.atomic.AtomicBoolean()
    override fun acquired(resource: KastHostObservation.Resource) {
        if (mode == "FAIL_" + resource.name) throw IllegalStateException("injected acquisition failure")
    }
    private fun barrier(kind: String) {
        if (mode != kind || !held.compareAndSet(false, true)) return
        java.nio.file.Files.writeString(directory.resolve("entered"), kind, java.nio.file.StandardOpenOption.CREATE_NEW)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40)
        while (!java.nio.file.Files.exists(directory.resolve("release"))) {
            check(System.nanoTime() < deadline) { "test barrier deadline" }
            Thread.sleep(10)
        }
    }
    override fun beforeProjection() {
        if (mode == "PROJECTION_FAILURE") throw IllegalStateException("injected projection failure")
        barrier("CALLBACK")
    }
    override fun beforeWrite() {
        if (mode == "WRITE_FAILURE") throw IllegalStateException("injected write failure")
        barrier("WRITER")
    }
    override fun retirementStarted() {
        java.nio.file.Files.writeString(directory.resolve("retiring"), "RETIRING", java.nio.file.StandardOpenOption.CREATE_NEW)
    }
}
