package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotterDemoStreamRendererTest {
    @Test
    fun `stream block renders readable tone prefixes and preserves separators`() = testSession { terminal ->
        section {
            renderStreamBlock(
                KotterDemoStreamBlock(
                    entries = listOf(
                        KotterDemoStreamEntry.Content(
                            text = "kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
                            tone = KotterDemoStreamTone.COMMAND,
                        ),
                        KotterDemoStreamEntry.Separator,
                        KotterDemoStreamEntry.Content(
                            text = "[confirmed] semantic rename target resolved",
                            tone = KotterDemoStreamTone.CONFIRMED,
                        ),
                        KotterDemoStreamEntry.Content(
                            text = "[flagged] textual alias remains for review",
                            tone = KotterDemoStreamTone.FLAGGED,
                        ),
                    ),
                ),
            )
        }.run()

        assertEquals(
            listOf(
                "$ kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
                "",
                "✓ [confirmed] semantic rename target resolved",
                "⚑ [flagged] textual alias remains for review",
            ),
            terminal.resolveRerenders().stripFormatting().dropLastWhile(String::isEmpty),
        )
    }
}
