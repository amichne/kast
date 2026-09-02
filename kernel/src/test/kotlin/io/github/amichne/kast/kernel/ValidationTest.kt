package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValidationTest {
    @Test
    fun `zip accumulates every failure in definition order`() {
        val result =
            Validation.rejected<Int, Failure>(Failure.First)
                .zipAccumulating(Validation.rejected<Int, Failure>(Failure.Second), Int::plus)

        assertEquals(listOf(Failure.First, Failure.Second), result.rejectedFailures())
    }

    @Test
    fun `definitions accumulate independent proofs for one weak value`() {
        val nonBlank = RefinementDefinition<String, String, Failure> { candidate ->
            if (candidate.isNotBlank()) {
                Validation.validated(candidate)
            } else {
                Validation.rejected(Failure.Blank)
            }
        }
        val kastPrefixed = RefinementDefinition<String, String, Failure> { candidate ->
            if (candidate.startsWith("kast-")) {
                Validation.validated(candidate)
            } else {
                Validation.rejected(Failure.MissingKastPrefix)
            }
        }

        val definition = nonBlank.zipAccumulating(kastPrefixed) { admitted, _ -> admitted }
        val result = definition.refine("")

        assertEquals(listOf(Failure.Blank, Failure.MissingKastPrefix), result.rejectedFailures())
    }

    @Test
    fun `map preserves an already proven failure set`() {
        val rejected = Validation.rejected<Int, Failure>(Failure.First)

        val result = rejected.map(Int::toString)

        assertEquals(rejected, result)
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.rejectedFailures(): List<Failure> =
        when (this) {
            is Validation.Validated -> throw AssertionError("Expected rejection, received $this")
            is Validation.Rejected -> failures.toList()
        }

    private enum class Failure {
        First,
        Second,
        Blank,
        MissingKastPrefix,
    }
}
