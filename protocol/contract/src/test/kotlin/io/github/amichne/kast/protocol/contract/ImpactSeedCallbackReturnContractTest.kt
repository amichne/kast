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

/** Detached output shapes preserve explicit callback returns without compiler authority. */
class ImpactSeedCallbackReturnContractTest {
    @Test
    fun `a seeded callback return keeps its supplier path predicates and distinct encoded operation`() {
        val f = ImpactSeedCallbackReturnCase()
        assertTrue(f.transfer.admitsEvidence())
        assertTrue(f.transfer.source.callStack.values.isEmpty())
        assertEquals(listOf(f.callback.target.supplyingFrame.asFrame()), f.transfer.target.callStack.values)
        assertEquals(f.source.valuePath, f.target.valuePath)
        assertEquals(f.source.predicates, f.target.predicates)
        val encoded = Json.encodeToJsonElement(ImpactSourceOperationDocument.serializer(), f.operation).jsonObject
        assertEquals(setOf("type", "frame"), encoded.keys)
        assertEquals("SEED_CALLBACK_RETURN", encoded.getValue("type").jsonPrimitive.content)
        val proof = encoded.getValue("frame").jsonObject.getValue("target").jsonObject
        assertEquals(setOf("type", "flow", "selected", "supplyingFrame"), proof.keys)
        assertEquals("CALLBACK", proof.getValue("type").jsonPrimitive.content)
        assertEquals(
            "DIRECT",
            proof
                .getValue("supplyingFrame")
                .jsonObject
                .getValue("target")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        val unknown =
            Json.encodeToString(ImpactSourceOperationDocument.serializer(), f.operation)
                .replace("SEED_CALLBACK_RETURN", "UNREVIEWED_CALLBACK_RETURN")
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactSourceOperationDocument.serializer(), unknown)
        }
    }

    @Test
    fun `seeded callback return rejects ordinary operations stack substitutions and opaque frames`() {
        val f = ImpactSeedCallbackReturnCase()
        assertTrue(f.transfer.admitsEvidence())
        assertFalse(
            f.transfer
                .copy(evidence = ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.Return(f.frame)))
                .admitsEvidence()
        )
        assertFalse(
            f.transfer
                .copy(
                    evidence = ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.SeedReturn(f.frame))
                )
                .admitsEvidence()
        )
        val active = BoundedProtocolList.create(listOf(f.callback.target.supplyingFrame.asFrame(), f.frame)).value()
        assertFalse(f.transfer.copy(source = f.source.copy(callStack = active)).admitsEvidence())
        assertFalse(f.transfer.copy(target = f.target.copy(callStack = EmptyImpactCallStack)).admitsEvidence())
        assertFalse(f.transfer.copy(target = f.target.copy(callStack = active)).admitsEvidence())
        assertFalse(f.withFrame(f.frame.copy(target = ImpactCallTargetDocument.Direct)).admitsEvidence())
        assertFalse(f.transfer.copy(kind = ImpactTransferKindDocument.RETURN).admitsEvidence())
    }

    @Test
    fun `seeded callback return rejects another body basis result invocation and predicate loss`() {
        val f = ImpactSeedCallbackReturnCase()
        assertTrue(f.transfer.admitsEvidence())
        assertFalse(
            f.transfer
                .copy(source = f.source.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration))
                .admitsEvidence()
        )
        val body = (f.source.executionOwner as ImpactExecutionOwnerDocument.Anonymous).body
        val other = body.copy(range = body.range.copy(start = ProtocolOffset.parse(41).value()))
        assertFalse(
            f.transfer
                .copy(source = f.source.copy(executionOwner = ImpactExecutionOwnerDocument.Anonymous(other)))
                .admitsEvidence()
        )
        assertFalse(
            f.transfer
                .copy(target = f.target.copy(range = f.target.range.copy(start = ProtocolOffset.parse(311).value())))
                .admitsEvidence()
        )
        val foreign = f.callback.basis.copy(generation = ImpactEvidenceRevisionDocument.parse(8).value())
        assertFalse(
            f.transfer
                .copy(target = f.target.copy(enclosing = f.target.enclosing.copy(basis = foreign)))
                .admitsEvidence()
        )
        assertFalse(f.transfer.copy(target = f.target.copy(predicates = EmptyImpactPredicates)).admitsEvidence())
        assertFalse(f.transfer.copy(target = f.target.copy(valuePath = EmptyImpactValuePath)).admitsEvidence())
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
