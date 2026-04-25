package io.github.amichne.kast.cli.demo

internal data class KotterDemoLayoutRequest(
    val terminalWidth: Int,
    val operations: List<String>,
    val activeOperation: String,
    val query: String,
    val cursorVisible: Boolean,
    val branches: List<KotterDemoBranchSpec> = emptyList(),
    val mode: KotterDemoLayoutMode = KotterDemoLayoutMode.Single,
) {
    init {
        require(activeOperation in operations) {
            "Active operation '$activeOperation' must exist in operations: $operations"
        }
    }
}

internal enum class KotterDemoLayoutMode {
    Single,
    DualPane,
}

internal data class KotterDemoBranchSpec(
    val header: String,
    val lines: List<String>,
    val summary: String,
)

internal sealed interface KotterDemoLayoutDecision {
    data class Ready(
        val shell: KotterDemoShellLayout,
        val dualPane: KotterDemoDualPaneLayout? = null,
        val fallbackToSingle: Boolean = false,
    ) : KotterDemoLayoutDecision

    data class Halted(val warning: String) : KotterDemoLayoutDecision
}

internal data class KotterDemoDualPaneLayout(
    val paneWidth: Int,
    val gap: Int,
    val totalWidth: Int,
)

internal data class KotterDemoShellLayout(
    val persistent: KotterDemoPersistentShell,
    val live: KotterDemoLiveShell,
)

internal data class KotterDemoPersistentShell(
    val operationRail: List<KotterDemoOperationChip>,
)

internal data class KotterDemoOperationChip(
    val label: String,
    val active: Boolean,
)

internal data class KotterDemoLiveShell(
    val queryBar: KotterDemoQueryBar,
    val branchGrid: KotterDemoBranchGrid?,
)

internal data class KotterDemoQueryBar(
    val renderedCommand: String,
    val cursorVisible: Boolean,
)

internal data class KotterDemoBranchGrid(
    val columnWidth: Int,
    val columns: List<KotterDemoBranchColumn>,
)

internal data class KotterDemoBranchColumn(
    val header: String,
    val lines: List<String>,
    val summary: String,
)

internal class KotterDemoLayoutCalculator(
    private val shellInsetWidth: Int = SHELL_INSET_WIDTH,
    private val branchColumnGapWidth: Int = BRANCH_COLUMN_GAP_WIDTH,
    private val faithfulBranchColumnWidth: Int = MIN_FAITHFUL_BRANCH_COLUMN_WIDTH,
) {
    fun layout(request: KotterDemoLayoutRequest): KotterDemoLayoutDecision {
        if (request.mode == KotterDemoLayoutMode.DualPane && request.terminalWidth < MIN_SINGLE_PANE_WIDTH) {
            return KotterDemoLayoutDecision.Halted(
                warning = "Terminal width ${request.terminalWidth} is too narrow for faithful Kotter demo rendering; need at least $MIN_SINGLE_PANE_WIDTH columns.",
            )
        }
        return when (val branchGridDecision = branchGridFor(request)) {
            is BranchGridDecision.Halted -> KotterDemoLayoutDecision.Halted(branchGridDecision.warning)
            is BranchGridDecision.Ready -> KotterDemoLayoutDecision.Ready(
                shell = KotterDemoShellLayout(
                    persistent = KotterDemoPersistentShell(
                        operationRail = request.operations.map { operation ->
                            KotterDemoOperationChip(
                                label = operation,
                                active = operation == request.activeOperation,
                            )
                        },
                    ),
                    live = KotterDemoLiveShell(
                        queryBar = KotterDemoQueryBar(
                            renderedCommand = request.query,
                            cursorVisible = request.cursorVisible,
                        ),
                        branchGrid = branchGridDecision.grid,
                    ),
                ),
                dualPane = dualPaneLayoutFor(request),
                fallbackToSingle = request.mode == KotterDemoLayoutMode.DualPane && request.terminalWidth < MIN_DUAL_PANE_WIDTH,
            )
        }
    }

    private fun dualPaneLayoutFor(request: KotterDemoLayoutRequest): KotterDemoDualPaneLayout? {
        if (request.mode != KotterDemoLayoutMode.DualPane || request.terminalWidth < MIN_DUAL_PANE_WIDTH) {
            return null
        }

        val totalWidth = request.terminalWidth - shellInsetWidth
        val paneWidth = ((totalWidth - DUAL_PANE_GAP) / 2).coerceAtLeast(1)
        return KotterDemoDualPaneLayout(
            paneWidth = paneWidth,
            gap = DUAL_PANE_GAP,
            totalWidth = paneWidth * 2 + DUAL_PANE_GAP,
        )
    }

    private fun branchGridFor(request: KotterDemoLayoutRequest): BranchGridDecision {
        if (request.branches.isEmpty()) return BranchGridDecision.Ready(grid = null)

        val minimumWidth = faithfulWidthFor(request.branches.size)
        if (request.terminalWidth < minimumWidth) {
            return BranchGridDecision.Halted(
                warning = "Terminal width ${request.terminalWidth} is too narrow for faithful Kotter demo rendering " +
                    "with ${request.branches.size} branches; need at least $minimumWidth columns.",
            )
        }

        val columnWidth = ((request.terminalWidth - shellInsetWidth - totalGapWidthFor(request.branches.size)) / request.branches.size)
            .coerceAtLeast(1)

        return BranchGridDecision.Ready(
            grid = KotterDemoBranchGrid(
                columnWidth = columnWidth,
                columns = request.branches.map { branch ->
                    KotterDemoBranchColumn(
                        header = TextFit.truncate(branch.header, columnWidth),
                        lines = branch.lines.map { TextFit.truncate(it, columnWidth) },
                        summary = TextFit.truncate(branch.summary, columnWidth),
                    )
                },
            ),
        )
    }

    private fun faithfulWidthFor(branchCount: Int): Int =
        shellInsetWidth + (branchCount * faithfulBranchColumnWidth) + totalGapWidthFor(branchCount)

    private fun totalGapWidthFor(branchCount: Int): Int =
        (branchCount - 1).coerceAtLeast(0) * branchColumnGapWidth

    private sealed interface BranchGridDecision : KotterDemoLayoutDecision {
        data class Halted(val warning: String) : BranchGridDecision

        data class Ready(val grid: KotterDemoBranchGrid?) : BranchGridDecision
    }

    private companion object {
        const val SHELL_INSET_WIDTH: Int = 3
        const val BRANCH_COLUMN_GAP_WIDTH: Int = 3
        const val MIN_FAITHFUL_BRANCH_COLUMN_WIDTH: Int = 22
        const val MIN_SINGLE_PANE_WIDTH: Int = 80
        const val MIN_DUAL_PANE_WIDTH: Int = 120
        const val DUAL_PANE_GAP: Int = 1
    }
}
