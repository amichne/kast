package io.github.amichne.kast.cli.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotterDemoLayoutTest {
    private val subject = KotterDemoLayoutCalculator()

    @Test
    fun `operation rail stays persistent while the live query shell keeps the active command`() {
        val request = KotterDemoLayoutRequest(
            terminalWidth = 140,
            operations = listOf("Rename", "Call Hierarchy", "Find References"),
            activeOperation = "Find References",
            query = "kast references --symbol io.acme.demo.execute --depth 2",
            cursorVisible = true,
        )

        val expected = KotterDemoLayoutDecision.Ready(
            shell = KotterDemoShellLayout(
                persistent = KotterDemoPersistentShell(
                    operationRail = listOf(
                        KotterDemoOperationChip("Rename", active = false),
                        KotterDemoOperationChip("Call Hierarchy", active = false),
                        KotterDemoOperationChip("Find References", active = true),
                    ),
                ),
                live = KotterDemoLiveShell(
                    queryBar = KotterDemoQueryBar(
                        renderedCommand = "kast references --symbol io.acme.demo.execute --depth 2",
                        cursorVisible = true,
                    ),
                    branchGrid = null,
                ),
            ),
        )

        assertEquals(expected, subject.layout(request))
    }

    @Test
    fun `branch grid width math truncates oversized branch cells without breaking the layout`() {
        val columnWidth = 28 // (124 - 9 chars of inter-column padding) / 4 branches
        val request = KotterDemoLayoutRequest(
            terminalWidth = 124,
            operations = listOf("Rename", "Call Hierarchy", "Find References"),
            activeOperation = "Rename",
            query = "kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
            cursorVisible = false,
            branches = listOf(
                KotterDemoBranchSpec(
                    header = "UserProfileService.kt",
                    lines = listOf(
                        "rename call -> resolveApplicationCallSite()",
                        "override chain -> UserProfileServiceImpl",
                    ),
                    summary = "3 confirmed edits",
                ),
                KotterDemoBranchSpec(
                    header = "LegacyBillingFacade.kt",
                    lines = listOf(
                        "rename call -> resolveBillingCallSite()",
                        "risk flag -> textual alias still unresolved",
                    ),
                    summary = "2 edits, 1 flag",
                ),
                KotterDemoBranchSpec(
                    header = "SearchIndexBackfill.kt",
                    lines = listOf(
                        "rename call -> replayBackfillMigration()",
                        "constructor reference -> SearchIndexer",
                    ),
                    summary = "2 confirmed edits",
                ),
                KotterDemoBranchSpec(
                    header = "AuditRenameEmitter.kt",
                    lines = listOf(
                        "rename call -> emitSemanticRenameTelemetry()",
                        "override chain -> AuditTelemetryEmitter",
                    ),
                    summary = "1 confirmed edit",
                ),
            ),
        )

        val expected = KotterDemoLayoutDecision.Ready(
            shell = KotterDemoShellLayout(
                persistent = KotterDemoPersistentShell(
                    operationRail = listOf(
                        KotterDemoOperationChip("Rename", active = true),
                        KotterDemoOperationChip("Call Hierarchy", active = false),
                        KotterDemoOperationChip("Find References", active = false),
                    ),
                ),
                live = KotterDemoLiveShell(
                    queryBar = KotterDemoQueryBar(
                        renderedCommand = "kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
                        cursorVisible = false,
                    ),
                    branchGrid = KotterDemoBranchGrid(
                        columnWidth = columnWidth,
                        columns = request.branches.map { branch ->
                            KotterDemoBranchColumn(
                                header = truncated(branch.header, columnWidth),
                                lines = branch.lines.map { truncated(it, columnWidth) },
                                summary = truncated(branch.summary, columnWidth),
                            )
                        },
                    ),
                ),
            ),
        )

        assertEquals(expected, subject.layout(request))
    }

    @Test
    fun `too narrow terminals halt with warning instead of degrading the branch grid`() {
        val request = KotterDemoLayoutRequest(
            terminalWidth = 96,
            operations = listOf("Rename", "Call Hierarchy", "Find References"),
            activeOperation = "Rename",
            query = "kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
            cursorVisible = true,
            branches = listOf(
                KotterDemoBranchSpec("A.kt", lines = listOf("line"), summary = "done"),
                KotterDemoBranchSpec("B.kt", lines = listOf("line"), summary = "done"),
                KotterDemoBranchSpec("C.kt", lines = listOf("line"), summary = "done"),
                KotterDemoBranchSpec("D.kt", lines = listOf("line"), summary = "done"),
            ),
        )

        val expected = KotterDemoLayoutDecision.Halted(
            warning = "Terminal width 96 is too narrow for faithful Kotter demo rendering with 4 branches; need at least 100 columns.",
        )

        assertEquals(expected, subject.layout(request))
    }

    private companion object {
        fun truncated(text: String, width: Int): String =
            if (text.length <= width) text else text.take(width - 1) + "…"
    }
}
