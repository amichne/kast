package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.DemoTextMatchCategory
import kotlinx.serialization.Serializable

@Serializable
internal data class DualPaneScenario(
    val rounds: List<DualPaneRound>,
)

@Serializable
internal data class DualPaneRound(
    val title: String,
    val leftCommand: String,
    val rightCommand: String,
    val leftLines: List<DualPaneLeftLine>,
    val rightLines: List<KotterDemoTranscriptLine>,
    val leftFooter: String,
    val rightFooter: String,
    val scoreboard: List<ScoreboardRow>,
)

@Serializable
internal data class DualPaneLeftLine(
    val text: String,
    val category: DemoTextMatchCategory,
    val codePreview: String? = null,
)

@Serializable
internal data class ScoreboardRow(
    val metric: String,
    val grepValue: String,
    val kastValue: String,
    val delta: String,
    val isNewCapability: Boolean,
)
