package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotterDemoBranchGridRendererTest {
    @Test
    fun `branch grid renders bounded columns with padded empty cells and a summary row`() = testSession { terminal ->
        section {
            renderBranchGrid(
                KotterDemoBranchGrid(
                    columnWidth = 10,
                    columns = listOf(
                        KotterDemoBranchColumn(
                            header = "AlphaBranchLong",
                            lines = listOf("resolve()", "emit telemetry", "done"),
                            summary = "3 edits",
                        ),
                        KotterDemoBranchColumn(
                            header = "Beta",
                            lines = listOf("walk callers"),
                            summary = "1 flag",
                        ),
                    ),
                ),
            )
        }.run()

        assertEquals(
            listOf(
                "┌────────────┬────────────┐",
                "│ AlphaBran… │ Beta       │",
                "├────────────┼────────────┤",
                "│ resolve()  │ walk call… │",
                "│ emit tele… │            │",
                "│ done       │            │",
                "├────────────┼────────────┤",
                "│ 3 edits    │ 1 flag     │",
                "└────────────┴────────────┘",
            ),
            terminal.resolveRerenders().stripFormatting().dropLastWhile(String::isEmpty),
        )
    }

    @Test
    fun `branch grid re-bounds oversized cells so renderer output stays inside the promised width`() = testSession { terminal ->
        section {
            renderBranchGrid(
                KotterDemoBranchGrid(
                    columnWidth = 8,
                    columns = listOf(
                        KotterDemoBranchColumn(
                            header = "UserProfileService.kt",
                            lines = listOf("rename call -> resolveApplicationCallSite()"),
                            summary = "confirmed edits only",
                        ),
                        KotterDemoBranchColumn(
                            header = "AuditRenameEmitter.kt",
                            lines = listOf("override chain -> AuditTelemetryEmitter"),
                            summary = "risk flag still open",
                        ),
                    ),
                ),
            )
        }.run()

        assertEquals(
            listOf(
                "┌──────────┬──────────┐",
                "│ UserPro… │ AuditRe… │",
                "├──────────┼──────────┤",
                "│ rename … │ overrid… │",
                "├──────────┼──────────┤",
                "│ confirm… │ risk fl… │",
                "└──────────┴──────────┘",
            ),
            terminal.resolveRerenders().stripFormatting().dropLastWhile(String::isEmpty),
        )
    }
}
