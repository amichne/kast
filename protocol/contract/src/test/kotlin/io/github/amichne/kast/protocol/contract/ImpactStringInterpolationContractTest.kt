package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Structural interpolation proof grants no K2 type, immediate-entry, or execution authority. */
class ImpactStringInterpolationContractTest {
    @Test
    fun `all scalar kinds keep the explicit derivation proof and independent encoded fields`() {
        val expected = listOf("STRING", "BOOLEAN", "CHAR", "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE")
        assertEquals(expected, ImpactInterpolationScalarKindDocument.entries.map { it.name })
        for (kind in ImpactInterpolationScalarKindDocument.entries) {
            val transfer = transfer(kind)
            assertTrue(transfer.admitsEvidence())
            val encoded = Json.encodeToJsonElement(ImpactCompilerTransferDocument.serializer(), transfer).jsonObject
            assertEquals("DERIVATION", encoded.getValue("kind").jsonPrimitive.content)
            val proof = encoded.getValue("evidence").jsonObject
            assertEquals(setOf("type", "template_range", "entry_range", "entry_index", "scalar_kind"), proof.keys)
            assertEquals("STRING_INTERPOLATION", proof.getValue("type").jsonPrimitive.content)
            assertEquals(kind.name, proof.getValue("scalar_kind").jsonPrimitive.content)
            assertEquals("2", proof.getValue("entry_index").jsonPrimitive.content)
            assertEquals("310", proof.getValue("template_range").jsonObject.getValue("start").jsonPrimitive.content)
            assertEquals("340", proof.getValue("template_range").jsonObject.getValue("end").jsonPrimitive.content)
            assertEquals("318", proof.getValue("entry_range").jsonObject.getValue("start").jsonPrimitive.content)
            assertEquals("332", proof.getValue("entry_range").jsonObject.getValue("end").jsonPrimitive.content)
        }
    }

    @Test
    fun `another range whole-value state and source context cannot borrow an interpolation proof`() {
        val transfer = transfer()
        val proof = transfer.evidence as ImpactTransferEvidenceDocument.StringInterpolation
        assertTrue(transfer.admitsEvidence())
        assertFalse(transfer.copy(source = transfer.source.copy(range = range(315, 317))).admitsEvidence())
        assertFalse(transfer.copy(target = transfer.target.copy(range = range(310, 339))).admitsEvidence())
        assertFalse(transfer.copy(evidence = proof.copy(entryRange = range(310, 332))).admitsEvidence())
        assertFalse(
            transfer
                .copy(
                    source =
                        transfer.source.copy(valuePath = bounded(listOf(ImpactValuePathSegmentDocument.AnyElement)))
                )
                .admitsEvidence()
        )
        assertFalse(transfer.copy(target = transfer.target.copy(callStack = EmptyImpactCallStack)).admitsEvidence())
        val other = CallbackIdentityFixture().target.supplyingFrame.enclosing
        assertFalse(transfer.copy(target = transfer.target.copy(enclosing = other)).admitsEvidence())
    }

    @Test
    fun `derivation cannot use direct evidence or another transfer kind`() {
        val transfer = transfer()
        assertFalse(transfer.copy(evidence = ImpactTransferEvidenceDocument.Direct).admitsEvidence())
        assertFalse(transfer.copy(kind = ImpactTransferKindDocument.BRANCH_ALTERNATIVE).admitsEvidence())
        assertFalse(
            transfer.copy(source = transfer.source.copy(role = ImpactValueRoleDocument.Return)).admitsEvidence()
        )
        assertTrue(
            transfer
                .copy(source = transfer.source.copy(role = ImpactValueRoleDocument.ExpressionResult))
                .admitsEvidence()
        )
    }

    @Test
    fun `unknown scalar evidence tags and negative entry indices fail typed decoding`() {
        val encoded = Json.encodeToString(ImpactCompilerTransferDocument.serializer(), transfer())
        val counterexamples =
            listOf(
                encoded.replace("\"scalar_kind\":\"STRING\"", "\"scalar_kind\":\"FUNCTION\""),
                encoded.replace("STRING_INTERPOLATION", "UNPROVEN_STRING_INTERPOLATION"),
                encoded.replace("\"entry_index\":2", "\"entry_index\":-1"),
            )
        counterexamples.forEach { malformed ->
            assertFalse(encoded == malformed)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(ImpactCompilerTransferDocument.serializer(), malformed)
            }
        }
    }

    private fun transfer(
        kind: ImpactInterpolationScalarKindDocument = ImpactInterpolationScalarKindDocument.STRING
    ): ImpactCompilerTransferDocument {
        val base = CallbackIdentityFixture().argument
        val source = base.copy(range = range(322, 326), role = ImpactValueRoleDocument.LocalRead)
        return ImpactCompilerTransferDocument(
            source,
            source.copy(range = range(310, 340), role = ImpactValueRoleDocument.ExpressionResult),
            ImpactTransferKindDocument.DERIVATION,
            ImpactTransferEvidenceDocument.StringInterpolation(
                range(310, 340),
                range(318, 332),
                ProtocolOffset.parse(2).value(),
                kind,
            ),
        )
    }
}

private fun range(start: Int, end: Int) =
    ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
