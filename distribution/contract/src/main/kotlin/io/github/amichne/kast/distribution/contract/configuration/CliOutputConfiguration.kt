package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

enum class CliOutputFormat {
    JSON,
    TOON,
}

@Serializable
enum class CliOutputFailure {
    INVALID_OUTPUT_FORMAT,
    OUTPUT_ENCODING_REJECTED,
    OUTPUT_LIMIT_EXCEEDED,
}

fun selectCliOutputFormat(value: String?): Refinement<CliOutputFormat, CliOutputFailure> =
    when (value) {
        null,
        "",
        "1" -> Refinement.Refined(CliOutputFormat.TOON)
        "0" -> Refinement.Refined(CliOutputFormat.JSON)
        else -> Refinement.Rejected(CliOutputFailure.INVALID_OUTPUT_FORMAT)
    }
