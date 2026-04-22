package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.input.CharKey
import com.varabyte.kotter.foundation.input.Key
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.input.runUntilKeyPressed
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotter.runtime.render.RenderScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class KotterDemoOperationPresentation(
    val id: String,
    val label: String,
    val shortcutKey: Char,
    val query: String,
    val branches: List<KotterDemoBranchSpec> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Operation presentation id must not be blank." }
        require(label.isNotBlank()) { "Operation presentation label must not be blank." }
        require(!shortcutKey.isWhitespace()) { "Operation presentation shortcut must be a non-whitespace character." }
        require(query.isNotBlank()) { "Operation presentation query must not be blank." }
    }

    val railLabel: String
        get() = "${shortcutKey.uppercaseChar()} ${label}"
}

internal data class KotterDemoSessionPresentation(
    val scenario: KotterDemoSessionScenario,
    val operations: List<KotterDemoOperationPresentation>,
    val replayKey: Char = 'r',
    val quitKey: Char = 'q',
) {
    private val operationsById: Map<String, KotterDemoOperationPresentation> = operations.associateBy(KotterDemoOperationPresentation::id)

    init {
        require(operations.isNotEmpty()) { "Kotter demo presentation must declare at least one operation." }
        require(operationsById.size == operations.size) { "Kotter demo presentation operation ids must be unique." }

        val scenarioOperationIds = scenario.operations.map(KotterDemoOperationScenario::id).toSet()
        require(scenarioOperationIds == operationsById.keys) {
            "Kotter demo presentation ids must match scenario ids. Scenario=$scenarioOperationIds presentation=${operationsById.keys}"
        }

        val reservedKeys = setOf(replayKey.normalizedShortcut(), quitKey.normalizedShortcut())
        val operationKeys = operations.map { it.shortcutKey.normalizedShortcut() }
        require(operationKeys.distinct().size == operationKeys.size) { "Kotter demo operation shortcuts must be unique." }
        require(reservedKeys.intersect(operationKeys.toSet()).isEmpty()) {
            "Replay / quit shortcuts must not overlap operation shortcuts."
        }
        require(replayKey.normalizedShortcut() != quitKey.normalizedShortcut()) {
            "Replay and quit shortcuts must be different."
        }
    }

    fun operation(operationId: String): KotterDemoOperationPresentation =
        operationsById[operationId] ?: error("Unknown demo operation presentation: $operationId")

    fun haltWarningFor(
        terminalWidth: Int,
        layoutCalculator: KotterDemoLayoutCalculator = KotterDemoLayoutCalculator(),
    ): String? = operations
        .firstNotNullOfOrNull { operation ->
            val decision = layoutCalculator.layout(
                KotterDemoLayoutRequest(
                    terminalWidth = terminalWidth,
                    operations = operations.map(KotterDemoOperationPresentation::railLabel),
                    activeOperation = operation.railLabel,
                    query = operation.query,
                    cursorVisible = false,
                    branches = operation.branches,
                ),
            )
            (decision as? KotterDemoLayoutDecision.Halted)?.warning
        }
}

internal fun Session.runKotterDemoSession(
    presentation: KotterDemoSessionPresentation,
    terminalWidth: Int,
    clearScreen: () -> Unit = {},
    layoutCalculator: KotterDemoLayoutCalculator = KotterDemoLayoutCalculator(),
    blinkInterval: Duration = 400.milliseconds,
) {
    val keyBindings = KotterDemoKeyBindings(presentation)
    presentation.haltWarningFor(terminalWidth, layoutCalculator)?.let { warning ->
        section {
            renderKotterDemoHaltWarning(warning, quitKey = presentation.quitKey)
        }.runUntilKeyPressed(Keys.ESC, CharKey(presentation.quitKey), CharKey(presentation.quitKey.uppercaseChar()))
        return
    }

    var activeOperationId = presentation.scenario.initialOperationId
    while (true) {
        clearScreen()
        when (
            val command = runKotterDemoOperation(
                presentation = presentation,
                operationId = activeOperationId,
                keyBindings = keyBindings,
                layoutCalculator = layoutCalculator,
                blinkInterval = blinkInterval,
            )
        ) {
            KotterDemoCommand.Replay -> Unit
            KotterDemoCommand.Quit -> return
            is KotterDemoCommand.SwitchOperation -> activeOperationId = command.operationId
        }
    }
}

private fun Session.runKotterDemoOperation(
    presentation: KotterDemoSessionPresentation,
    operationId: String,
    keyBindings: KotterDemoKeyBindings,
    layoutCalculator: KotterDemoLayoutCalculator,
    blinkInterval: Duration,
): KotterDemoCommand {
    val initialState = presentation.scenario.initialStateFor(operationId)
    var sessionState by liveVarOf(initialState)
    var pulseVisible by liveVarOf(true)
    var nextCommand: KotterDemoCommand = KotterDemoCommand.Quit

    section {
        renderKotterDemoScreen(
            buildKotterDemoScreen(
                presentation = presentation,
                sessionState = sessionState,
                pulseVisible = pulseVisible,
                terminalWidth = width,
                layoutCalculator = layoutCalculator,
            ),
        )
    }.runUntilSignal {
        val controller = KotterDemoSessionController.create(section.coroutineScope, presentation.scenario)

        section.coroutineScope.launch {
            while (isActive) {
                delay(blinkInterval)
                pulseVisible = if (sessionState.isRunning()) !pulseVisible else false
            }
        }

        section.coroutineScope.launch {
            var emittedAsideCount = 0
            controller.states().collect { snapshot ->
                val newAsideLines = snapshot.asideLines.drop(emittedAsideCount)
                if (newAsideLines.isNotEmpty()) {
                    aside {
                        renderStreamBlock(newAsideLines.toStreamBlock())
                    }
                }
                emittedAsideCount = snapshot.asideLines.size
                sessionState = snapshot
            }
        }

        controller.start(operationId)

        onKeyPressed {
            keyBindings.commandFor(key, activeOperationId = operationId)?.let { command ->
                nextCommand = command
                signal()
            }
        }
    }

    return nextCommand
}

private sealed interface KotterDemoScreen {
    data class Running(
        val actHeader: KotterDemoActHeader,
        val statusPanel: KotterDemoStatusPanel,
        val transcriptLines: List<String>,
        val branchSection: KotterDemoBranchSection?,
        val panelContentWidth: Int,
    ) : KotterDemoScreen

    data class Halted(val warning: String) : KotterDemoScreen
}

private data class KotterDemoBranchSection(
    val title: String,
    val caption: String,
    val grid: KotterDemoBranchGrid,
)

private fun buildKotterDemoScreen(
    presentation: KotterDemoSessionPresentation,
    sessionState: KotterDemoSessionState,
    pulseVisible: Boolean,
    terminalWidth: Int,
    layoutCalculator: KotterDemoLayoutCalculator,
): KotterDemoScreen {
    val activeOperation = presentation.operation(sessionState.activeOperationId)
    val activeOperationIndex = presentation.operations.indexOfFirst { it.id == sessionState.activeOperationId }
    val layoutDecision = layoutCalculator.layout(
        KotterDemoLayoutRequest(
            terminalWidth = terminalWidth,
            operations = presentation.operations.map(KotterDemoOperationPresentation::railLabel),
            activeOperation = activeOperation.railLabel,
            query = activeOperation.query,
            cursorVisible = pulseVisible && sessionState.isRunning(),
            branches = activeOperation.branches,
        ),
    )

    return when (layoutDecision) {
        is KotterDemoLayoutDecision.Halted -> KotterDemoScreen.Halted(layoutDecision.warning)
        is KotterDemoLayoutDecision.Ready -> {
            val running = sessionState.isRunning()
            val completedPreview = sessionState.asideLines.lastOrNull()
                ?.let { streamLines(listOf(it).toStreamBlock()).last() }
                ?: "• Waiting for the first completed phase"
            val livePreview = when {
                sessionState.liveLines.isNotEmpty() -> streamLines(listOf(sessionState.liveLines.last()).toStreamBlock()).last()
                running -> "• Streaming next demo event…"
                else -> "✓ Operation complete"
            }
            KotterDemoScreen.Running(
                actHeader = KotterDemoActHeader(
                    title = "Act ${activeOperationIndex + 1} of ${presentation.operations.size} — ${activeOperation.label}",
                    queryBar = layoutDecision.shell.live.queryBar,
                ),
                statusPanel = KotterDemoStatusPanel(
                    operationRail = layoutDecision.shell.persistent.operationRail,
                    phaseBar = KotterDemoPhaseBar(
                        phases = presentation.scenario.operation(sessionState.activeOperationId).phases.map { phaseId ->
                            KotterDemoPhaseChip(
                                label = phaseId.phaseLabel(),
                                status = sessionState.phaseStates.getValue(phaseId),
                            )
                        },
                    ),
                    activityIndicator = KotterDemoActivityIndicator(
                        status = if (running) KotterDemoActivityStatus.RUNNING else KotterDemoActivityStatus.COMPLETE,
                        pulseVisible = pulseVisible,
                    ),
                    controls = "Keys   [${presentation.replayKey.uppercaseChar()}] Replay  [${presentation.quitKey.uppercaseChar()}] Quit  [${presentation.operations.joinToString("/") { it.shortcutKey.uppercaseChar().toString() }}] Switch act",
                ),
                transcriptLines = listOf(
                    transcriptLine("Completed", completedPreview),
                    transcriptLine("Live", livePreview),
                ),
                branchSection = layoutDecision.shell.live.branchGrid?.let { branchGrid ->
                    KotterDemoBranchSection(
                        title = "Impact Grid",
                        caption = "Compiler-verified file fan-out across ${branchGrid.columns.size} branches.",
                        grid = branchGrid,
                    )
                },
                panelContentWidth = (terminalWidth - PANEL_FRAME_WIDTH).coerceAtLeast(1),
            )
        }
        else -> error("Unexpected layout decision: $layoutDecision")
    }
}

private fun RenderScope.renderKotterDemoScreen(screen: KotterDemoScreen) {
    when (screen) {
        is KotterDemoScreen.Halted -> renderKotterDemoHaltWarning(screen.warning)
        is KotterDemoScreen.Running -> {
            renderActHeader(screen.actHeader, screen.panelContentWidth)
            textLine()
            renderStatusPanel(screen.statusPanel, screen.panelContentWidth)
            textLine()
            renderPanel(
                title = "Live Transcript",
                panelContentWidth = screen.panelContentWidth,
                bodyLines = screen.transcriptLines,
            )
            screen.branchSection?.let { branchSection ->
                textLine()
                renderPanel(
                    title = branchSection.title,
                    panelContentWidth = screen.panelContentWidth,
                    bodyLines = listOf(branchSection.caption),
                )
                renderBranchGrid(branchSection.grid)
            }
        }
    }
}

private fun RenderScope.renderKotterDemoHaltWarning(
    warning: String,
    quitKey: Char = 'q',
) {
    red(isBright = true) { textLine("Kotter demo halted") }
    yellow(isBright = true) { textLine(warning) }
    textLine("Resize the terminal and rerun `kast demo`.")
    textLine("Press ${quitKey.uppercaseChar()} or Esc to quit.")
}

internal sealed interface KotterDemoCommand {
    data class SwitchOperation(val operationId: String) : KotterDemoCommand

    data object Replay : KotterDemoCommand

    data object Quit : KotterDemoCommand
}

internal class KotterDemoKeyBindings(
    private val presentation: KotterDemoSessionPresentation,
) {
    private val operationIdsByShortcut: Map<Char, String> =
        presentation.operations.associate { it.shortcutKey.normalizedShortcut() to it.id }

    fun commandFor(
        key: Key,
        activeOperationId: String,
    ): KotterDemoCommand? = when (key) {
        Keys.ESC -> KotterDemoCommand.Quit
        is CharKey -> when (val normalized = key.code.normalizedShortcut()) {
            presentation.replayKey.normalizedShortcut() -> KotterDemoCommand.Replay
            presentation.quitKey.normalizedShortcut() -> KotterDemoCommand.Quit
            else -> operationIdsByShortcut[normalized]
                ?.let { operationId ->
                    if (operationId == activeOperationId) {
                        KotterDemoCommand.Replay
                    } else {
                        KotterDemoCommand.SwitchOperation(operationId)
                    }
                }
        }

        else -> null
    }
}

private fun List<String>.toStreamBlock(): KotterDemoStreamBlock =
    KotterDemoStreamBlock(
        entries = map { KotterDemoStreamEntry.Content(it) },
    )

private fun KotterDemoSessionState.isRunning(): Boolean =
    phaseStates.values.any { it == KotterDemoPhaseStatus.ACTIVE }

private fun String.phaseLabel(): String =
    replace('-', ' ')
        .replace('_', ' ')
        .uppercase()

private fun transcriptLine(
    label: String,
    value: String,
): String = label.padEnd(11) + value

private fun Char.normalizedShortcut(): Char = lowercaseChar()

private const val PANEL_FRAME_WIDTH: Int = 4
