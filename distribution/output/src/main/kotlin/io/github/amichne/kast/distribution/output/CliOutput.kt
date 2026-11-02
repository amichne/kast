package io.github.amichne.kast.distribution.output

import dev.toonformat.jtoon.JToon
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The input is the complete JSON projection from its existing serializer, including fixed fields. */
fun renderCliOutput(document: String, format: CliOutputFormat): Refinement<String, CliOutputFailure> =
    when (format) {
        CliOutputFormat.JSON -> Refinement.Refined(document)
        CliOutputFormat.TOON ->
            try {
                Refinement.Refined(JToon.encodeJson(document))
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED)
            }
    }

@Serializable
private data class CliOutputRejection(
    val failure: CliOutputFailure,
    @Required val type: String = "rejected",
)

fun cliOutputRejection(failure: CliOutputFailure, format: CliOutputFormat): String {
    val document = Json.encodeToString(CliOutputRejection(failure))
    return when (val rendered = renderCliOutput(document, format)) {
        is Refinement.Refined -> rendered.value
        is Refinement.Rejected -> error("Output rejection cannot be encoded")
    }
}
