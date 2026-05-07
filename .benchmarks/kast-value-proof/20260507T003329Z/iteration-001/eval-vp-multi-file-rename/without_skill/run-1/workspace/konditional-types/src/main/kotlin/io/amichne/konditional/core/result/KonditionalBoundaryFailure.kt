package io.amichne.konditional.core.result

/**
 * Structured failure wrapper used as the error channel payload for Kotlin [Result] boundary APIs.
 *
 * This type preserves the typed [ParseError] taxonomy while allowing public APIs to return
 * `Result<T>` instead of custom result wrappers.
 */
class KonditionalBoundaryFailure(
    val parseError: ParseError,
) : RuntimeException(parseError.message)

/**
 * Converts a [ParseError] into a failed Kotlin [Result] with a structured boundary payload.
 */
fun <T> parseFailure(error: ParseError): Result<T> = Result.failure(KonditionalBoundaryFailure(error))

/**
 * Returns the structured [ParseError] if this throwable originated from a Konditional boundary.
 */
fun Throwable.parseErrorOrNull(): ParseError? = (this as? KonditionalBoundaryFailure)?.parseError

/**
 * Returns the structured [ParseError] when this [Result] is failed by a Konditional boundary.
 */
fun <T> Result<T>.parseErrorOrNull(): ParseError? = exceptionOrNull()?.parseErrorOrNull()
