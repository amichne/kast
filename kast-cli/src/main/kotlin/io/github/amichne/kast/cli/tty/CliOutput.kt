package io.github.amichne.kast.cli.tty

internal sealed interface CliOutput {
    data class JsonValue(val value: Any) : CliOutput
    data class JsonValueWithExitCode(val value: Any, val exitCode: Int) : CliOutput
    data class Text(val value: String) : CliOutput
    data class ExternalProcess(val process: CliExternalProcess) : CliOutput
    data object None : CliOutput
}
