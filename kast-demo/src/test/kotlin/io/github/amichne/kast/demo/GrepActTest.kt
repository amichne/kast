package io.github.amichne.kast.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrepActTest {

    private val sampleResult = GrepResult(
        command = "grep -rn \"execute\" --include=\"*.kt\"",
        totalHits = 38,
        categories = listOf(
            GrepCategory("String literals", 12, "\"execute this command\""),
            GrepCategory("Comments", 9, "// TODO: execute after init"),
            GrepCategory("Unrelated scope", 8, "SqlRunner.execute(): Unit"),
            GrepCategory("Possible matches", 19, null),
        ),
    )

    @Test
    fun `grep act renders header with act info`() = testSession { terminal ->
        section {
            renderGrepAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Act 1 of 3" in it }, "Should contain act number")
        assertTrue(lines.any { "Text Search" in it }, "Should contain title")
    }

    @Test
    fun `grep act renders category table with all rows`() = testSession { terminal ->
        section {
            renderGrepAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        // Table header
        assertTrue(lines.any { "Category" in it && "Count" in it && "Example" in it },
            "Should have table header row. Got: $lines")
        // Data rows
        assertTrue(lines.any { "String literals" in it && "12" in it },
            "Should have String literals row")
        assertTrue(lines.any { "Comments" in it && "9" in it },
            "Should have Comments row")
        assertTrue(lines.any { "Unrelated scope" in it && "8" in it },
            "Should have Unrelated scope row")
        assertTrue(lines.any { "Possible matches" in it && "19" in it },
            "Should have Possible matches row")
    }

    @Test
    fun `grep act renders summary line with hit count`() = testSession { terminal ->
        section {
            renderGrepAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "38" in it && "grep hits" in it },
            "Should have summary line with hit count. Got: $lines")
    }

    @Test
    fun `grep act summary describes noise`() = testSession { terminal ->
        section {
            renderGrepAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "No type information" in it || "noise" in it.lowercase() },
            "Summary should describe noise problem. Got: $lines")
    }

    @Test
    fun `grep act with empty categories renders without errors`() = testSession { terminal ->
        val emptyResult = GrepResult(
            command = "grep -rn \"foo\"",
            totalHits = 0,
            categories = emptyList(),
        )
        section {
            renderGrepAct(emptyResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Act 1 of 3" in it }, "Should still render header")
        assertTrue(lines.any { "0" in it && "grep hits" in it },
            "Should show 0 hits in summary")
    }
}
