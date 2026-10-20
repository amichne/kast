package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackReturnQuestionTest {
    @Test
    fun `a qualified zero answer keeps its exact callback return question`() {
        val f = CallbackIdentityFixture()
        val fresh = qualified(f)
        val binding = fresh.binding as QueryCallbackBindingWireDocument.Bound
        val renewed =
            fresh.copy(
                body =
                    fresh.body.copy(occurrence = fresh.body.occurrence.copy(candidateSelector = "renewed:return-body")),
                binding =
                    binding.copy(
                        invocationOccurrence = binding.invocationOccurrence.copy(candidateSelector = "renewed:supplier")
                    ),
            )
        val evidence = ImpactFlowObligationEvidenceDocument.CallbackFlow(renewed)
        assertTrue(
            evidence.admitsObligation(returned(f), f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        )
    }

    @Test
    fun `a callback return rejects another body supplier or basis`() {
        val f = CallbackIdentityFixture()
        val source = returned(f)
        fun accepts(flow: QueryCallbackFlowWireDocument.Observed) =
            ImpactFlowObligationEvidenceDocument.CallbackFlow(flow)
                .admitsObligation(
                    source,
                    f.basis,
                    ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
                )
        val fresh = qualified(f)
        assertFalse(accepts(fresh.copy(body = f.anonymousBody(45, 75))))
        assertFalse(
            accepts(fresh.copy(basis = f.basis.copy(generation = ImpactEvidenceRevisionDocument.parse(8).value())))
        )
        val binding = fresh.binding as QueryCallbackBindingWireDocument.Bound
        val range = SourceRangeWireDocument(31, 89)
        assertFalse(
            accepts(
                fresh.copy(
                    binding =
                        binding.copy(
                            invocation =
                                binding.invocation.copy(
                                    range =
                                        ImpactSourceRangeDocument(
                                            ProtocolOffset.parse(31).value(),
                                            ProtocolOffset.parse(89).value(),
                                        )
                                ),
                            invocationOccurrence = binding.invocationOccurrence.copy(range = range),
                        )
                )
            )
        )
    }

    @Test
    fun `a callback return cannot substitute another execution owner`() {
        val f = CallbackIdentityFixture()
        val source = returned(f).copy(executionOwner = ImpactExecutionOwnerDocument.Declaration)
        assertFalse(
            ImpactFlowObligationEvidenceDocument.CallbackFlow(f.flow)
                .admitsObligation(
                    source,
                    f.basis,
                    ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
                )
        )
    }

    @Test
    fun `an unavailable callback return cannot substitute another execution owner`() {
        val f = CallbackIdentityFixture()
        val source = returned(f).copy(executionOwner = ImpactExecutionOwnerDocument.Declaration)
        assertFalse(
            ImpactFlowObligationEvidenceDocument.CallbackUnavailable(QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES)
                .admitsObligation(source, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        )
    }

    @Test
    fun `a rejected callback return cannot substitute another execution owner`() {
        val f = CallbackIdentityFixture()
        val source = returned(f).copy(executionOwner = ImpactExecutionOwnerDocument.Declaration)
        assertFalse(
            ImpactFlowObligationEvidenceDocument.CallbackContractRejected(
                    QueryCallbackFlowFailureDocument.INVALID_FORWARDING_PATH
                )
                .admitsObligation(source, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        )
    }

    @Test
    fun `a supplying occurrence cannot substitute another invocation execution owner`() {
        val f = CallbackIdentityFixture()
        val fresh = qualified(f)
        val binding = fresh.binding as QueryCallbackBindingWireDocument.Bound
        val body = f.anonymousBody(10, 150)
        val original = (f.frame.callee.executionOwner as ImpactExecutionOwnerDocument.Anonymous).body
        val other =
            ImpactExecutionOwnerDocument.Anonymous(
                original.copy(
                    range =
                        ImpactSourceRangeDocument(ProtocolOffset.parse(10).value(), ProtocolOffset.parse(150).value()),
                    compilerIdentity = ProtocolText.parse(body.compilerEvidence.identity).value(),
                )
            )
        val evidence =
            ImpactFlowObligationEvidenceDocument.CallbackFlow(
                fresh.copy(binding = binding.copy(invocation = binding.invocation.copy(executionOwner = other)))
            )
        assertTrue(evidence.flow.admitsFlow(f.basis))
        assertFalse(
            evidence.admitsObligation(returned(f), f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN)
        )
    }

    private fun returned(f: CallbackIdentityFixture): ImpactValueSiteReferenceDocument {
        val owner = f.frame.callee.executionOwner as ImpactExecutionOwnerDocument.Anonymous
        return f.argument.copy(
            enclosing = f.frame.callee.enclosing,
            range = owner.body.range,
            role = ImpactValueRoleDocument.Return,
            executionOwner = owner,
            callStack = BoundedProtocolList.create(listOf(f.target.supplyingFrame.asFrame(), f.frame)).value(),
        )
    }

    private fun qualified(f: CallbackIdentityFixture) =
        f.flow.copy(
            invocations = emptyList(),
            obligations = listOf(QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED),
            scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
        )
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
