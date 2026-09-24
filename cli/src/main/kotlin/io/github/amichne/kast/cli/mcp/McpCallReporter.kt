package io.github.amichne.kast.cli.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Emits bounded stage and outcome facts; result contents never enter the diagnostic stream. */
internal class McpCallReporter(private val diagnostic: PrintStream) {
    operator fun invoke(
        tool: String,
        stage: McpCallStage,
        outcome: McpCallOutcome,
        resultVariant: McpResultVariant? = null,
        schemaFailure: McpResultSchemaFailure? = null,
        schemaField: McpResultSchemaField? = null,
    ) {
        diagnostic.println(
            mcpWire.encodeToString(
                McpCallEvent(
                    tool = tool,
                    stage = stage,
                    outcome = outcome,
                    resultVariant = resultVariant,
                    schemaFailure = schemaFailure,
                    schemaField = schemaField,
                )
            )
        )
    }
}

private const val MAX_FRAME_BYTES = 1_048_576

internal fun readMcpLine(input: BufferedInputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
        if (next == '\n'.code) return bytes.toString(Charsets.UTF_8)
        if (bytes.size() >= MAX_FRAME_BYTES) return null
        bytes.write(next)
    }
}
