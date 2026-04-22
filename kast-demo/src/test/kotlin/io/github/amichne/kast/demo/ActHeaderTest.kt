package io.github.amichne.kast.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActHeaderTest {

    @Test
    fun `act header renders bordered box with act number, title, and subtitle`() = testSession { terminal ->
        section {
            renderActHeader(
                actNumber = 1,
                totalActs = 3,
                title = "Text Search",
                subtitle = "grep -rn \"execute\" --include=\"*.kt\"",
            )
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        val contentLines = lines.filter { it.isNotBlank() }
        assertTrue(contentLines.any { "Act 1 of 3" in it }, "Should contain act number. Got: $lines")
        assertTrue(contentLines.any { "Text Search" in it }, "Should contain title. Got: $lines")
        assertTrue(contentLines.any { "grep -rn" in it }, "Should contain subtitle. Got: $lines")
        // Border chars should be present
        val rawLines = terminal.resolveRerenders()
        assertTrue(rawLines.any { '┌' in it || '╭' in it }, "Should have top border char. Got: $rawLines")
    }
}
