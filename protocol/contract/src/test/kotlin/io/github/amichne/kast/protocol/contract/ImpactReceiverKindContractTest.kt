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

class ImpactReceiverKindContractTest {
    @Test
    fun `source entry admits only the matching receiver slot of the same invocation`() {
        val f = ReceiverKindFixture()
        for (kind in ImpactReceiverKindDocument.entries) {
            assertTrue(f.entry(kind, kind).admitsEvidence())
            val other = ImpactReceiverKindDocument.entries.single { it != kind }
            assertFalse(f.entry(kind, other).admitsEvidence())
        }
    }

    @Test
    fun `encoded receiver role requires its exact kind and rejects missing or unknown values`() {
        val f = ReceiverKindFixture()
        for ((kind, expected) in
            listOf(
                ImpactReceiverKindDocument.DISPATCH to "DISPATCH",
                ImpactReceiverKindDocument.EXTENSION to "EXTENSION",
            )) {
            val role = ImpactValueRoleDocument.Receiver(f.invocation, kind)
            val encoded = Json.encodeToJsonElement(ImpactValueRoleDocument.serializer(), role).jsonObject
            assertEquals(setOf("type", "invocation", "kind"), encoded.keys)
            assertEquals("RECEIVER", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(expected, encoded.getValue("kind").jsonPrimitive.content)
            val raw = Json.encodeToString(ImpactValueRoleDocument.serializer(), role)
            for (malformed in
                listOf(raw.replace(",\"kind\":\"$expected\"", ""), raw.replace(expected, "UNKNOWN_SLOT"))) {
                assertThrows(SerializationException::class.java) {
                    Json.decodeFromString(ImpactValueRoleDocument.serializer(), malformed)
                }
            }
        }
    }

    @Test
    fun `the other receiver slot never matches exact value identity`() {
        val f = ReceiverKindFixture()
        val dispatch = f.site(ImpactReceiverKindDocument.DISPATCH)
        val extension = f.site(ImpactReceiverKindDocument.EXTENSION)
        assertFalse(dispatch.hasSameValueIdentity(extension))
        assertTrue(dispatch.hasSameValueIdentity(dispatch.copy()))
    }
}

private class ReceiverKindFixture {
    private val basis =
        ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value())
    private val caller = declaration("Caller.kt", 'a')
    private val callee = declaration("Component.kt", 'b')
    val invocation = ImpactInvocationReferenceDocument(range(30, 50), callee)
    private val frame = ImpactCallFrameDocument(caller, invocation, ImpactExecutionReferenceDocument(callee))

    fun site(kind: ImpactReceiverKindDocument) =
        ImpactValueSiteReferenceDocument(caller, range(30, 35), ImpactValueRoleDocument.Receiver(invocation, kind))

    fun entry(actual: ImpactReceiverKindDocument, formal: ImpactReceiverKindDocument): ImpactCompilerTransferDocument {
        val position = ImpactFormalPositionDocument.Receiver(formal)
        val target =
            ImpactValueSiteReferenceDocument(
                callee,
                range(10, 90),
                ImpactValueRoleDocument.Formal(position),
                callStack = bounded(listOf(frame)),
            )
        return ImpactCompilerTransferDocument(
            site(actual),
            target,
            ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
            ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.Enter(frame, position)),
        )
    }

    private fun declaration(file: String, hash: Char) =
        ImpactDeclarationReferenceDocument(
            basis,
            text("/workspace/$file"),
            range(0, 100),
            text("canonical-signature-sha256-v1|" + hash.toString().repeat(64)),
        )
}

private fun text(value: String) = ProtocolText.parse(value).value()

private fun range(start: Int, end: Int) =
    ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
