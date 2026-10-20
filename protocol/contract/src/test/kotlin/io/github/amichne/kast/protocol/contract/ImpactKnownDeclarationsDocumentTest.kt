package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactKnownDeclarationsDocumentTest {
    private val values = ImpactFindingFixture()
    private val endpoint = ValueModelStructureCase().binding.enclosing

    @Test
    fun `both endpoint variants encode independent tags and every existing endpoint field`() {
        val inputs =
            listOf(
                "SUBJECT" to ImpactKnownDeclarationDocument.Subject(endpoint),
                "RESOLVED" to ImpactKnownDeclarationDocument.Resolved(endpoint),
            )
        for ((tag, input) in inputs) {
            val encoded = Json.encodeToJsonElement(ImpactKnownDeclarationDocument.serializer(), input).jsonObject
            assertEquals(setOf("type", "endpoint"), encoded.keys)
            assertEquals(tag, encoded.getValue("type").jsonPrimitive.content)
            val proof = encoded.getValue("endpoint").jsonObject
            assertEquals(setOf("declaration", "compilerTarget", "domain", "fingerprint"), proof.keys)
            assertEquals(endpoint.fingerprint.value, proof.getValue("fingerprint").jsonPrimitive.content)
            assertEquals(
                endpoint.declaration.file.value,
                proof.getValue("declaration").jsonObject.getValue("file").jsonPrimitive.content,
            )
            val text = Json.encodeToString(ImpactKnownDeclarationDocument.serializer(), input)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(
                    ImpactKnownDeclarationDocument.serializer(),
                    text.replace("\"$tag\"", "\"UNKNOWN_ENDPOINT\""),
                )
            }
        }
    }

    @Test
    fun `native receipts and investigated output encode empty known declarations explicitly`() {
        val receipt =
            ImpactNativeReadReceiptDocument(values.flowDomain, values.count(1), values.count(0), values.count(0))
        val encodedReceipt = Json.encodeToJsonElement(ImpactNativeReadReceiptDocument.serializer(), receipt).jsonObject
        assertEquals(0, encodedReceipt.getValue("knownDeclarations").jsonArray.size)
        val accounting =
            ImpactAccountingDocument.Investigated(
                values.bounded(listOf(values.producer)),
                ImpactRequestedBoundaryDocument.Workspace,
                ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
                values.bounded(emptyList()),
                values.bounded(emptyList()),
                values.count(0),
                values.count(0),
                values.count(0),
                values.count(0),
                ImpactAccountingStatusDocument.Unresolved(
                    values.bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW))
                ),
                ImpactAccountingViewDocument.Paths,
                values.bounded(emptyList()),
            )
        val encoded = Json.encodeToJsonElement(ImpactAccountingDocument.serializer(), accounting).jsonObject
        assertEquals("INVESTIGATED", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(0, encoded.getValue("knownDeclarations").jsonArray.size)
    }

    @Test
    fun `qualified output keeps order duplicates basis and the exact question across receipts`() {
        val subject = ImpactKnownDeclarationDocument.Subject(endpoint)
        val resolved = ImpactKnownDeclarationDocument.Resolved(endpoint)
        val inputs = values.bounded(listOf(subject, resolved, subject))
        assertTrue(inputs.admitsKnownDeclarations(endpoint.declaration.basis))
        assertEquals(listOf(subject, resolved, subject), inputs.values)
        assertFalse(
            values
                .bounded(List<ImpactKnownDeclarationDocument>(129) { subject })
                .admitsKnownDeclarations(endpoint.declaration.basis)
        )
        val foreign =
            (endpoint.declaration.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = ImpactEvidenceRevisionDocument.parse(8).value()
            )
        assertFalse(inputs.admitsKnownDeclarations(foreign))
        val receipt =
            ImpactNativeReadReceiptDocument(
                values.flowDomain,
                values.count(1),
                values.count(0),
                values.count(0),
                inputs,
            )
        val witness =
            ImpactWitnessDocument.ReadRejection(
                ImpactReadRejectionDocument.Native(
                    values.producer,
                    ImpactRequestedBoundaryDocument.Workspace,
                    ImpactNativeReadRejectionDocument.AUTHORITY_MOVED,
                    values.count(1),
                ),
                values.bounded(listOf(receipt)),
            )
        assertTrue(witness.hasKnownDeclarations(inputs))
        assertFalse(witness.hasKnownDeclarations(values.bounded(listOf(resolved, subject, subject))))
    }
}

private fun <T, F> io.github.amichne.kast.kernel.Refinement<T, F>.value(): T =
    when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Known declaration fixture rejected: $failure")
    }
