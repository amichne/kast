package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

enum class CliOutputFormat(val switchValue: String) {
    JSON("0"),
    TOON("1");

    companion object {
        val DEFAULT = JSON
    }
}

@Serializable
enum class CliOutputFailure {
    INVALID_OUTPUT_FORMAT,
    OUTPUT_ENCODING_REJECTED,
    OUTPUT_LIMIT_EXCEEDED,
    OUTPUT_VALUE_LOSS,
}

fun selectCliOutputFormat(value: String?): Refinement<CliOutputFormat, CliOutputFailure> =
    when (value) {
        null,
        "" -> Refinement.Refined(CliOutputFormat.DEFAULT)
        "1" -> Refinement.Refined(CliOutputFormat.TOON)
        "0" -> Refinement.Refined(CliOutputFormat.JSON)
        else -> Refinement.Rejected(CliOutputFailure.INVALID_OUTPUT_FORMAT)
    }
