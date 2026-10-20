package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Diagnostic evidence keeps the exact return question. It grants no callback transfer. */
class ValueSeedCallbackReturnObligationOracleTest {
    @Test
    fun `a qualified zero-invocation return question keeps every source fact and raw cause`() {
        val case = SeedCallbackReturnOracleCase()
        val causes =
            setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED, CallbackInvocationFlowCause.BYTE_LIMIT_REACHED)
        val flow = qualified(case.callback, causes = causes)
        val obligation = ValueFlowObligation.fromSeedCallbackReturn(case.source, flow).flowRefined()
        assertSame(case.source, obligation.site)
        assertSame(case.path, obligation.site.valuePath)
        assertSame(case.predicates, obligation.site.predicates)
        assertEquals(ValueCallStack.Empty, obligation.site.callStack)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_FLOW_UNPROVEN, obligation.cause)
        val evidence = obligation.evidence as ValueFlowObligationEvidence.CallbackFlow
        assertSame(flow, evidence.flow)
        assertEquals(emptyList<CallbackParameterInvocation>(), evidence.flow.invocations)
        assertEquals(causes, evidence.flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, evidence.flow.scan)
    }

    @Test
    fun `a selected invocation and incomplete scan remain qualified in the return question`() {
        val case = SeedCallbackReturnOracleCase()
        val flow = qualified(case.callback, invocations = listOf(case.callback.selected))
        val obligation = ValueFlowObligation.fromSeedCallbackReturn(case.source, flow).flowRefined()
        val evidence = obligation.evidence as ValueFlowObligationEvidence.CallbackFlow
        assertSame(flow, evidence.flow)
        assertEquals(listOf(case.callback.selected), evidence.flow.invocations)
        assertEquals(setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED), evidence.flow.obligations)
        assertEquals(CallbackInvocationScan.INCOMPLETE, evidence.flow.scan)
    }

    @Test
    fun `another anonymous body cannot replace the source return body`() {
        val case = SeedCallbackReturnOracleCase()
        val other = callbackOracleOwner(case.callback.host, 70, 90).body as ValueExecutionBody.Anonymous
        rejected(case.source, qualified(case.callback, body = other.callable))
    }

    @Test
    fun `a foreign callback basis preserves the finite basis rejection`() {
        val case = SeedCallbackReturnOracleCase()
        val foreign = SeedCallbackReturnOracleCase(generation = 2)
        assertEquals(
            Refinement.Rejected(ValueFlowObligationFailure.BASIS_MISMATCH),
            ValueFlowObligation.fromSeedCallbackReturn(case.source, qualified(foreign.callback)),
        )
    }

    @Test
    fun `a different declared host cannot supply the original anonymous return`() {
        val case = SeedCallbackReturnOracleCase()
        val host = flowEndpoint("OtherHost.readOffers", 0, 300) as RelationEndpoint.Resolved
        rejected(case.source, qualified(case.callback, binding = bound(case.callback, host = host)))
    }

    @Test
    fun `the same host identity under another full scope cannot supply the question`() {
        val case = SeedCallbackReturnOracleCase()
        val host = seedReturnChangedScope(case.callback.host)
        assertEquals(case.callback.host.valueIdentity, host.valueIdentity)
        rejected(case.source, qualified(case.callback, binding = bound(case.callback, host = host)))
    }

    @Test
    fun `a nested caller execution cannot masquerade as a declaration supplier`() {
        val case = SeedCallbackReturnOracleCase()
        val execution = callbackOracleOwner(case.callback.host, 10, 110)
        val binding = bound(case.callback, execution = execution)
        val causes =
            setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        rejected(case.source, qualified(case.callback, binding = binding, causes = causes))
    }

    @Test
    fun `an active callback context cannot become an empty return seed question`() {
        val case = SeedCallbackReturnOracleCase()
        val active = case.source.withState(callStack = case.callback.entered().callStack).flowRefined()
        rejected(active, qualified(case.callback))
    }

    @Test
    fun `a named return cannot become an anonymous return seed question`() {
        val case = SeedCallbackReturnOracleCase()
        val named =
            ValueSite.fromCompiler(
                    enclosing = case.callback.host,
                    range = case.source.range,
                    role = ValueRole.Return,
                )
                .flowRefined()
        rejected(named, qualified(case.callback))
    }

    @Test
    fun `a local read cannot masquerade as a normal return question`() {
        val case = SeedCallbackReturnOracleCase()
        val local =
            ValueSite.fromCompiler(
                    enclosing = case.callback.host,
                    range = case.source.range,
                    role = ValueRole.LocalRead,
                    executionOwner = case.callback.callback,
                )
                .flowRefined()
        rejected(local, qualified(case.callback))
    }

    @Test
    fun `an unavailable supplier does not grant a bound return question`() {
        val case = SeedCallbackReturnOracleCase()
        val cause = CallbackInvocationFlowCause.PARAMETER_ESCAPES
        val binding = CallbackBindingEvidence.Unavailable(cause)
        rejected(case.source, qualified(case.callback, binding = binding, causes = setOf(cause)))
    }

    @Test
    fun `a supplier outside the anonymous body cannot form the compiler flow proof`() {
        val case = SeedCallbackReturnOracleCase()
        val binding = bound(case.callback, range = flowRange(20, 35))
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT),
            CallbackInvocationFlow.fromCompiler(
                basis = case.source.basis,
                body = case.callback.flow.body,
                binding = binding,
                invocations = emptyList(),
                obligations = setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                scan = CallbackInvocationScan.INCOMPLETE,
            ),
        )
    }

    @Test
    fun `an empty anonymous return keeps the raw unavailable proof limit`() {
        val case = SeedCallbackReturnOracleCase()
        val obligation =
            ValueFlowObligation.fromCallbackUnavailable(case.source, CallbackInvocationFlowCause.PROOF_LIMIT_REACHED)
                .flowRefined()
        assertSame(case.source, obligation.site)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED, obligation.cause)
        val evidence = obligation.evidence as ValueFlowObligationEvidence.CallbackUnavailable
        assertEquals(CallbackInvocationFlowCause.PROOF_LIMIT_REACHED, evidence.cause)
    }

    @Test
    fun `an empty anonymous return keeps the raw rejected proof limit`() {
        val case = SeedCallbackReturnOracleCase()
        val obligation =
            ValueFlowObligation.fromCallbackContractRejected(
                    case.source,
                    CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED,
                )
                .flowRefined()
        assertSame(case.source, obligation.site)
        assertEquals(ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED, obligation.cause)
        val evidence = obligation.evidence as ValueFlowObligationEvidence.CallbackContractRejected
        assertEquals(CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED, evidence.cause)
    }

    @Test
    fun `diagnostic refusals cannot reinterpret a local read as an empty return`() {
        val case = SeedCallbackReturnOracleCase()
        val source =
            ValueSite.fromCompiler(
                    enclosing = case.callback.host,
                    range = case.source.range,
                    role = ValueRole.LocalRead,
                    executionOwner = case.callback.callback,
                )
                .flowRefined()
        assertDiagnosticRejections(source)
    }

    @Test
    fun `diagnostic refusals cannot reinterpret a named empty return as an anonymous callback`() {
        val case = SeedCallbackReturnOracleCase()
        val source = ValueSite.fromCompiler(case.callback.host, case.source.range, ValueRole.Return).flowRefined()
        assertDiagnosticRejections(source)
    }
}

private fun rejected(source: ValueSite, flow: CallbackInvocationFlow) {
    assertEquals(
        Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
        ValueFlowObligation.fromSeedCallbackReturn(source, flow),
    )
}

private fun assertDiagnosticRejections(source: ValueSite) {
    assertEquals(
        Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
        ValueFlowObligation.fromCallbackUnavailable(source, CallbackInvocationFlowCause.PROOF_LIMIT_REACHED),
    )
    assertEquals(
        Refinement.Rejected(ValueFlowObligationFailure.SOURCE_MISMATCH),
        ValueFlowObligation.fromCallbackContractRejected(source, CallbackInvocationFlowFailure.PROOF_LIMIT_REACHED),
    )
}

private fun qualified(
    fixture: ValueCallbackOracleCase,
    body: RelationCallableBody.Anonymous = fixture.flow.body,
    binding: CallbackBindingEvidence = fixture.flow.binding,
    invocations: List<CallbackParameterInvocation> = emptyList(),
    causes: Set<CallbackInvocationFlowCause> = setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
): CallbackInvocationFlow =
    CallbackInvocationFlow.fromCompiler(
            basis = fixture.flow.basis,
            body = body,
            binding = binding,
            invocations = invocations,
            obligations = causes,
            scan = CallbackInvocationScan.INCOMPLETE,
        )
        .flowRefined()

private fun bound(
    fixture: ValueCallbackOracleCase,
    host: RelationEndpoint.Resolved = fixture.host,
    execution: ValueExecutionOwner = ValueExecutionOwner.declaration(host),
    range: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange = flowRange(20, 100),
): CallbackBindingEvidence.Bound {
    val invocation =
        ValueInvocation.fromCompiler(
                enclosing = host,
                range = range,
                callable = fixture.mapper,
                executionOwner = execution,
            )
            .flowRefined()
    val owner =
        when (val body = execution.body) {
            ValueExecutionBody.Declaration -> RelationCallableBody.Named.fromCompiler(host.evidence).flowRefined()
            is ValueExecutionBody.Anonymous -> body.callable
        }
    val original = (fixture.flow.binding as CallbackBindingEvidence.Bound).binding
    return CallbackBindingEvidence.Bound(
        CallbackArgumentBinding.fromCompiler(
                invocation = invocation,
                invocationOwner = owner,
                position = original.position,
                parameter = original.parameter,
            )
            .flowRefined()
    )
}
