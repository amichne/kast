package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ExistingIdeReadinessTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val descriptor = ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `status admits observed cold and ready states and rejects invented importing state`() {
        for (name in listOf("cold", "ready")) {
            val document = checkNotNull(javaClass.getResource("/hosted-readiness/$name.json")).readText()
            assertInstanceOf(
                ExistingIdeExchange.Received::class.java,
                ExistingIdeDocuments.response(document.toByteArray(), root, ExistingIdeOperation.Status, descriptor),
            )
            val unknown = document.replace(if (name == "cold") "unavailable" else "admission_ready", "importing")
            assertInstanceOf(
                ExistingIdeExchange.Rejected::class.java,
                ExistingIdeDocuments.response(unknown.toByteArray(), root, ExistingIdeOperation.Status, descriptor),
            )
        }
    }
}
