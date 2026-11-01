package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactLoopContractTest {
    @Test
    fun `wire distinguishes phases and explicitly encodes complete normal true advance proof`() {
        val f = ImpactFindingFixture()
        val protocol = protocol(f)
        val iteration = ImpactLoopIterationDocument(protocol)
        val iterator = site(protocol, ImpactLoopResultDocument.Iterator(protocol))
        val element = site(protocol, ImpactLoopResultDocument.Element(iteration))
        assertNotEquals(iterator, element)
        for ((site, phase) in listOf(iterator to "ITERATOR", element to "ELEMENT")) {
            val encoded = Json.encodeToString(site)
            val role = Json.parseToJsonElement(encoded).jsonObject.getValue("role").jsonObject
            assertEquals(setOf("type", "result"), role.keys)
            assertEquals("LOOP_RESULT", role.getValue("type").jsonPrimitive.content)
            assertEquals(phase, role.getValue("result").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(site, Json.decodeFromString<ImpactValueSiteReferenceDocument>(encoded))
        }
        val proof: ImpactTransferEvidenceDocument =
            ImpactTransferEvidenceDocument.LoopAdvance(iteration, ImpactReceiverKindDocument.DISPATCH)
        val encoded = Json.encodeToString(proof)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "iteration", "receiverKind"), root.keys)
        assertEquals("LOOP_ADVANCE", root.getValue("type").jsonPrimitive.content)
        assertEquals("DISPATCH", root.getValue("receiverKind").jsonPrimitive.content)
        val guard = root.getValue("iteration").jsonObject
        assertEquals(setOf("protocol", "condition"), guard.keys)
        assertEquals("NORMAL_TRUE", guard.getValue("condition").jsonPrimitive.content)
        val calls = guard.getValue("protocol").jsonObject
        assertEquals(setOf("enclosing", "iterator", "hasNext", "next"), calls.keys)
        for ((name, identity) in listOf("iterator" to 'b', "hasNext" to 'c', "next" to 'd')) {
            val call = calls.getValue(name).jsonObject
            assertEquals(setOf("range", "callable", "executionOwner"), call.keys)
            assertEquals(
                "canonical-signature-sha256-v1|" + identity.toString().repeat(64),
                call.getValue("callable").jsonObject.getValue("compilerIdentity").jsonPrimitive.content,
            )
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ImpactTransferEvidenceDocument>(encoded.replace("NORMAL_TRUE", "TERMINATES"))
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ImpactTransferEvidenceDocument>(encoded.replace(",\"condition\":\"NORMAL_TRUE\"", ""))
        }
    }

    @Test
    fun `advance rejects swapped guard result receiver slot and state`() {
        val f = ImpactFindingFixture()
        val protocol = protocol(f)
        val source = site(protocol, ImpactLoopResultDocument.Iterator(protocol))
        val target =
            source.copy(role = ImpactValueRoleDocument.Receiver(protocol.next, ImpactReceiverKindDocument.DISPATCH))
        val proof =
            ImpactTransferEvidenceDocument.LoopAdvance(
                ImpactLoopIterationDocument(protocol),
                ImpactReceiverKindDocument.DISPATCH,
            )
        val transfer = ImpactCompilerTransferDocument(source, target, ImpactTransferKindDocument.RECEIVER, proof)
        assertTrue(transfer.admitsEvidence())
        assertFalse(transfer.copy(evidence = ImpactTransferEvidenceDocument.Direct).admitsEvidence())
        assertFalse(
            transfer.copy(evidence = proof.copy(receiverKind = ImpactReceiverKindDocument.EXTENSION)).admitsEvidence()
        )
        assertFalse(
            transfer
                .copy(
                    evidence =
                        proof.copy(
                            iteration =
                                ImpactLoopIterationDocument(
                                    protocol.copy(hasNext = protocol.hasNext.copy(range = f.destination.range))
                                )
                        )
                )
                .admitsEvidence()
        )
        assertFalse(
            transfer.copy(source = site(protocol, ImpactLoopResultDocument.Element(proof.iteration))).admitsEvidence()
        )
        assertFalse(
            transfer
                .copy(target = target.copy(valuePath = f.bounded(listOf(ImpactValuePathSegmentDocument.Element))))
                .admitsEvidence()
        )
        assertFalse(source.copy(range = f.destination.range).admitsExecutionOwner())
        assertFalse(
            source.copy(enclosing = source.enclosing.copy(file = f.text("/workspace/Other.kt"))).admitsExecutionOwner()
        )
    }

    @Test
    fun `returns reject crossed phase and guard frames`() {
        val f = ImpactFindingFixture()
        val protocol = protocol(f)
        val iteration = ImpactLoopIterationDocument(protocol)
        val iterator = site(protocol, ImpactLoopResultDocument.Iterator(protocol))
        val element = site(protocol, ImpactLoopResultDocument.Element(iteration))
        val frame =
            ImpactCallFrameDocument(
                protocol.enclosing,
                protocol.next,
                ImpactExecutionReferenceDocument(protocol.next.callable),
            )
        val returned =
            ImpactValueSiteReferenceDocument(
                protocol.next.callable,
                protocol.next.callable.range,
                ImpactValueRoleDocument.Return,
                callStack = f.bounded(listOf(frame)),
            )
        val transfer =
            ImpactCompilerTransferDocument(
                returned,
                element,
                ImpactTransferKindDocument.SOURCE_CALL_RETURN,
                ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.Return(frame)),
            )
        assertTrue(transfer.admitsEvidence())
        assertFalse(transfer.copy(target = iterator).admitsEvidence())
        assertFalse(
            element.isResultOf(frame.copy(invocation = ImpactCallOccurrenceDocument.Declared(protocol.hasNext)))
        )
    }

    @Test
    fun `Direct only admits element receiver`() {
        val f = ImpactFindingFixture()
        val protocol = protocol(f)
        val iterator = site(protocol, ImpactLoopResultDocument.Iterator(protocol))
        val element = site(protocol, ImpactLoopResultDocument.Element(ImpactLoopIterationDocument(protocol)))
        val component =
            protocol.next.copy(
                range = f.destination.range,
                callable =
                    protocol.next.callable.copy(
                        compilerIdentity = f.text("canonical-signature-sha256-v1|" + "e".repeat(64))
                    ),
            )
        val receiver =
            element.copy(
                range = component.range,
                role = ImpactValueRoleDocument.Receiver(component, ImpactReceiverKindDocument.DISPATCH),
            )
        assertTrue(
            ImpactCompilerTransferDocument(element, receiver, ImpactTransferKindDocument.RECEIVER).admitsEvidence()
        )
        assertFalse(
            ImpactCompilerTransferDocument(iterator, receiver, ImpactTransferKindDocument.RECEIVER).admitsEvidence()
        )
    }

    private fun protocol(f: ImpactFindingFixture): ImpactLoopProtocolDocument {
        fun call(identity: Char) =
            ImpactInvocationReferenceDocument(
                ImpactSourceRangeDocument(f.offset(10), f.offset(80)),
                f.declaration.copy(
                    compilerIdentity = f.text("canonical-signature-sha256-v1|" + identity.toString().repeat(64))
                ),
            )
        return ImpactLoopProtocolDocument(f.declaration, call('b'), call('c'), call('d'))
    }

    private fun site(protocol: ImpactLoopProtocolDocument, result: ImpactLoopResultDocument) =
        ImpactValueSiteReferenceDocument(
            protocol.enclosing,
            protocol.iterator.range,
            ImpactValueRoleDocument.LoopResult(result),
        )
}
