package io.github.amichne.kast.cli

internal class CliFailure(
    val code: String,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)
