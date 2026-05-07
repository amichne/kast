package io.amichne.konditional.core

import io.amichne.konditional.core.result.KonditionalBoundaryFailure
import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.ParseOutcome
import io.amichne.konditional.core.result.parseErrorOrNull
import io.amichne.konditional.core.result.parseFailure
import io.amichne.konditional.core.result.toParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseResultTest {
    @Test
    fun `parseFailure preserves the structured parse error`() {
        val parseError = ParseError.invalidSnapshot("bad payload")
        val result: Result<String> = parseFailure(parseError)

        assertTrue(result.isFailure)
        val failure = assertIs<KonditionalBoundaryFailure>(result.exceptionOrNull())
        assertEquals(parseError, failure.parseError)
        assertEquals(parseError, result.parseErrorOrNull())
    }

    @Test
    fun `toParseResult converts success and failure deterministically`() {
        val success: ParseOutcome<Int> = Result.success(7).toParseResult()
        val failure: ParseOutcome<Int> = parseFailure<Int>(ParseError.invalidJson("boom")).toParseResult()

        assertEquals(ParseOutcome.Success(7), success)
        assertEquals(ParseError.invalidJson("boom"), assertIs<ParseOutcome.Failure>(failure).error)
        assertNull(IllegalStateException("x").parseErrorOrNull())
    }
}
