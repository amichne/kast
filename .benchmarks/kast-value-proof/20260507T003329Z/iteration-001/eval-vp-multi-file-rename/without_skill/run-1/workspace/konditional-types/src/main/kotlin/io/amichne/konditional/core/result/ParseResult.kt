package io.amichne.konditional.core.result

/**
 * Explicit result type for trust-boundary parsing in the enterprise modules.
 */
sealed interface ParseOutcome<out T> {
    data class Success<T>(val value: T) : ParseOutcome<T>

    data class Failure(val error: ParseError) : ParseOutcome<Nothing>
}

fun <T> Result<T>.toParseResult(): ParseOutcome<T> =
    fold(
        onSuccess = { ParseOutcome.Success(it) },
        onFailure = { throwable ->
            ParseOutcome.Failure(
                throwable.parseErrorOrNull()
                    ?: ParseError.invalidSnapshot(throwable.message ?: "Unknown parse failure"),
            )
        },
    )
