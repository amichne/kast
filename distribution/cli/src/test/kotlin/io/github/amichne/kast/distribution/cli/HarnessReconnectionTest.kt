package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HarnessReconnectionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Pi reconnect preserves both compositions and the recorded directory`() {
        for (composition in HarnessComposition.entries) {
            val case = Files.createDirectory(temporary.resolve(composition.name))
            val (root, home) = integrationFixture(case, HarnessConnection.PI, "old adapter", composition)
            val selected = admittedConnectionDirectory(case.resolve("selected-pi").toString())
            connectHarness(root, home, HarnessConnection.PI, directory = selected, composition = composition)
            val prior = (readReceipt(root) as ReceiptRead.Read).receipt.registrations.single()
            assertEquals(composition, prior.composition)
            integrationFixture(case, HarnessConnection.PI, "new adapter", composition)

            reconnectHarness(
                root,
                home,
                prior,
                mapOf("PI_CODING_AGENT_DIR" to case.resolve("unrelated-pi").toString()),
            )

            assertEquals("new adapter", Files.readString(selected.path.resolve("extensions/kast.ts")))
            val restored = (readReceipt(root) as ReceiptRead.Read).receipt.registrations.single()
            assertEquals(composition, restored.composition)
            assertEquals(selected.path.toString(), restored.directory)
            assertFalse(Files.exists(case.resolve("unrelated-pi")))
        }
    }
}
