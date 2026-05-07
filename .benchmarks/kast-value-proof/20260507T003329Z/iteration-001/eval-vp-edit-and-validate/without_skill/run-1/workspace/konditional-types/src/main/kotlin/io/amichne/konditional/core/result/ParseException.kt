package io.amichne.konditional.core.result

/**
 * Exception wrapper for ParseError when using throwing APIs or Result<T>.
 * Contains the structured ParseError for precise error handling.
 */
class ParseException(val error: ParseError) : Exception(error.message)
