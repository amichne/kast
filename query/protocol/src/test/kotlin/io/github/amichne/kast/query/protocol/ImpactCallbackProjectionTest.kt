package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.admitsExecutionOwner
import io.github.amichne.kast.protocol.contract.callbackWire
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackProjectionTest {
    @Test
    fun `native callback entry and matched return keep full function and supplier evidence`() {
        val f = ImpactCallbackProjectionCase()
        val entry = f.entered.impactDocument(f.references).value()
        assertTrue(entry.admitsEvidence())
        assertTrue(f.returned().impactDocument(f.references).value().admitsEvidence())
        assertTrue(entry.target.matchesSite(f.formal, f.references))
        val encoded = Json.encodeToJsonElement(ImpactCompilerTransferDocument.serializer(), entry).jsonObject
        val frame =
            encoded.getValue("evidence").jsonObject.getValue("operation").jsonObject.getValue("frame").jsonObject
        val invocation = frame.getValue("invocation").jsonObject
        assertEquals(setOf("type", "invocation"), invocation.keys)
        assertEquals("FUNCTION", invocation.getValue("type").jsonPrimitive.content)
        val callable = invocation.getValue("invocation").jsonObject.getValue("callable").jsonObject
        assertEquals(setOf("compiler_evidence", "kind", "origin", "module_kind", "module_name"), callable.keys)
        assertEquals("BUILTINS", callable.getValue("module_kind").jsonPrimitive.content)
        val target = frame.getValue("target").jsonObject
        assertEquals(setOf("type", "flow", "selected", "supplyingFrame"), target.keys)
        assertEquals("CALLBACK", target.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("basis", "body", "binding", "invocations", "obligations", "owner_bindings", "scan", "forwarding"),
            target.getValue("flow").jsonObject.keys,
        )
        assertEquals(
            "BOUND",
            target.getValue("flow").jsonObject.getValue("binding").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `callback shape rejects another basis body invoke and supplying call`() {
        val f = ImpactCallbackProjectionCase()
        val formal = f.formal.impactDocument(f.references).value()
        val frame = formal.callStack.values.last()
        val target = frame.target as ImpactCallTargetDocument.Callback
        fun accepts(proof: ImpactCallTargetDocument.Callback): Boolean =
            formal
                .copy(callStack = bounded(formal.callStack.values.dropLast(1) + frame.copy(target = proof)))
                .admitsExecutionOwner()
        assertTrue(accepts(target))
        val foreignBasis =
            (target.flow.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = ImpactEvidenceRevisionDocument.parse(2).value()
            )
        assertFalse(accepts(target.copy(flow = target.flow.copy(basis = foreignBasis))))
        val sibling = CallbackProjection(f.references, f.base.lease).anonymous(f.siblingBody())!!.callbackWire()
        assertFalse(accepts(target.copy(flow = target.flow.copy(body = sibling))))
        assertFalse(
            accepts(
                target.copy(
                    selected =
                        target.selected.copy(
                            occurrence =
                                target.selected.occurrence.copy(
                                    range = target.selected.occurrence.range.copy(startInclusive = 231)
                                )
                        )
                )
            )
        )
        assertFalse(
            accepts(
                target.copy(
                    supplyingFrame =
                        target.supplyingFrame.copy(
                            invocation =
                                target.supplyingFrame.invocation.copy(
                                    range =
                                        target.supplyingFrame.invocation.range.copy(
                                            start =
                                                io.github.amichne.kast.protocol.contract.ProtocolOffset.parse(31)
                                                    .value()
                                        )
                                )
                        )
                )
            )
        )
    }

    @Test
    fun `qualified callback flow keeps its exact obligation instead of asserting complete execution`() {
        val f = ImpactCallbackProjectionCase(qualified = true)
        val frame = f.frame.impactDocument(f.references).value()
        val target = frame.target as ImpactCallTargetDocument.Callback
        assertEquals(QueryCallbackInvocationScanDocument.INCOMPLETE, target.flow.scan)
        assertEquals(listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED), target.flow.obligations)
        assertTrue(f.entered.impactDocument(f.references).value().admitsEvidence())
        val obligation = ValueFlowObligation.fromCallback(f.argument, f.flow).value()
        val proof =
            obligation.evidence.impactDocument(f.references, f.argument.enclosing.lease).value()
                as ImpactFlowObligationEvidenceDocument.CallbackFlow
        assertEquals(target.flow, proof.flow)
        assertTrue(frame.invocation is ImpactCallOccurrenceDocument.Function)
        assertTrue(frame.callee.executionOwner is ImpactExecutionOwnerDocument.Anonymous)
    }
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
