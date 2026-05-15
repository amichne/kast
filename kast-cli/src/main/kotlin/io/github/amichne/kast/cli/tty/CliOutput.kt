package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.indexstore.api.graph.MetricsGraph
import java.nio.file.Path

internal sealed interface CliOutput {
    data class JsonValue(val value: Any) : CliOutput
    data class JsonValueWithExitCode(
        val value: Any,
        val exitCode: Int,
    ) : CliOutput

    data class Text(val value: String) : CliOutput
    data class InteractiveGraph(val graph: MetricsGraph) : CliOutput
    data class InteractiveGraphPicker(
        val workspaceRoot: Path,
        val depth: Int,
        val initialQuery: String? = null,
    ) : CliOutput

    data class ExternalProcess(val process: CliExternalProcess) : CliOutput
    data object None : CliOutput
}
