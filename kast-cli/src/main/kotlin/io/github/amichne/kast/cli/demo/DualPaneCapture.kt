package io.github.amichne.kast.cli.demo

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
internal data class DualPaneCapture(
    val scenario: DualPaneScenario,
    val symbolFqn: String,
)

internal fun loadCapture(path: Path): DualPaneCapture =
    dualPaneCaptureJson.decodeFromString(path.readText())

internal fun saveCapture(path: Path, capture: DualPaneCapture) {
    path.writeText(dualPaneCaptureJson.encodeToString(capture))
}

private val dualPaneCaptureJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}
