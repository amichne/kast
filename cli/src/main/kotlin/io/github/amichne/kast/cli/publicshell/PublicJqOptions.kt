package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement

internal sealed interface PublicOutputProjection {
    data object Unfiltered : PublicOutputProjection

    data class Jq(val options: PublicJqOptions) : PublicOutputProjection
}

/** Admission preserves a bounded program and independent presentation limits, never a semantic grant. */
internal class PublicJqOptions
private constructor(
    val program: String,
    val limits: PublicJqLimits,
) {
    companion object {
        fun parse(arguments: LexicalArguments): Refinement<PublicOutputProjection, PublicCliArgumentFailure> {
            val program = arguments[PublicCliFlag.JQ]
            if (program == null) {
                return if (arguments.options.keys.any { it in jqFlags })
                    Refinement.Rejected(PublicCliArgumentFailure.INVALID_JQ_OPTIONS)
                else Refinement.Refined(PublicOutputProjection.Unfiltered)
            }
            if (
                program.isBlank() || '\u0000' in program || program.toByteArray(Charsets.UTF_8).size > MAX_PROGRAM_BYTES
            )
                return Refinement.Rejected(PublicCliArgumentFailure.INVALID_JQ_OPTIONS)
            val limits =
                when (val parsed = PublicJqLimits.parse(arguments)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
            return Refinement.Refined(PublicOutputProjection.Jq(PublicJqOptions(program, limits)))
        }
    }
}

internal class PublicJqLimits private constructor(val timeoutMillis: Long, val bytes: Int) {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_BYTES = 16 * 1_024 * 1_024
        const val MAXIMUM_TIMEOUT_MILLIS = 60_000L
        const val MAXIMUM_BYTES = 64 * 1_024 * 1_024
        const val STDERR_BYTES = 32 * 1_024

        fun parse(arguments: LexicalArguments): Refinement<PublicJqLimits, PublicCliArgumentFailure> {
            val timeout =
                arguments[PublicCliFlag.JQ_TIMEOUT]?.toLongOrNull()
                    ?: if (arguments.has(PublicCliFlag.JQ_TIMEOUT)) null else DEFAULT_TIMEOUT_MILLIS
            val bytes =
                arguments[PublicCliFlag.JQ_BYTES]?.toIntOrNull()
                    ?: if (arguments.has(PublicCliFlag.JQ_BYTES)) null else DEFAULT_BYTES
            if (timeout == null || timeout !in 1..MAXIMUM_TIMEOUT_MILLIS)
                return Refinement.Rejected(PublicCliArgumentFailure.INVALID_JQ_OPTIONS)
            if (bytes == null || bytes !in 1..MAXIMUM_BYTES)
                return Refinement.Rejected(PublicCliArgumentFailure.INVALID_JQ_OPTIONS)
            return Refinement.Refined(PublicJqLimits(timeout, bytes))
        }
    }
}

internal val jqFlags = setOf(PublicCliFlag.JQ, PublicCliFlag.JQ_TIMEOUT, PublicCliFlag.JQ_BYTES)
