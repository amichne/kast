package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactStringConstructionValidationTest {
    private val fixture = ImpactFindingFixture()

    @Test
    fun `null construction admits nested source and rejects an escaped template or entry`() {
        val record =
            ImpactStringConstructionDocument(
                fixture.producer,
                range(8, 15),
                range(9, 13),
                range(10, 12),
                ImpactStringConstructionKindDocument.CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT,
            )
        assertTrue(record.admitsConstruction())
        assertFalse(record.copy(templateRange = range(8, 101)).admitsConstruction())
        assertFalse(record.copy(entryRange = range(7, 13)).admitsConstruction())
        assertFalse(record.copy(inputRange = range(11, 12)).admitsConstruction())
        assertFalse(record.copy(entryRange = record.templateRange).admitsConstruction())
    }

    @Test
    fun `conversion admits the zero argument function and rejects other canonical signature shapes`() {
        val valid = signature(emptyList(), emptyList())
        assertTrue(conversion(valid).admitsConversion(fixture.producer))
        assertFalse(conversion(signature(listOf("kotlin.String"), emptyList())).admitsConversion(fixture.producer))
        assertFalse(conversion(signature(emptyList(), listOf("kotlin.String"))).admitsConversion(fixture.producer))
        val property =
            CanonicalCompilerSignature.property("fixture.Token.toString", null, emptyList(), "kotlin.String").refined()
        assertFalse(conversion(property).admitsConversion(fixture.producer))
    }

    @Test
    fun `conversion rejects a different compiler identity and a range outside the original input`() {
        val original = conversion(signature(emptyList(), emptyList()))
        assertFalse(
            original.copy(compilerIdentity = fixture.declaration.compilerIdentity).admitsConversion(fixture.producer)
        )
        assertFalse(original.copy(inputRange = range(11, 12)).admitsConversion(fixture.producer))
        assertFalse(original.copy(entryRange = range(9, 101)).admitsConversion(fixture.producer))
    }

    private fun signature(parameters: List<String>, contexts: List<String>) =
        CanonicalCompilerSignature.function("fixture.Token.toString", null, contexts, parameters, 0).refined()

    private fun conversion(signature: CanonicalCompilerSignature) =
        ImpactFlowObligationEvidenceDocument.StringConversion(
            fixture.text(CompilerSymbolIdentity.fromCanonicalSignature(signature).value),
            fixture.text(signature.canonicalEncoding().value),
            ImpactStringConversionOriginDocument.SOURCE,
            range(9, 13),
            range(10, 12),
        )

    private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(fixture.offset(start), fixture.offset(end))
}

private fun <T, F> Refinement<T, F>.refined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid conversion fixture: $failure")
    }
