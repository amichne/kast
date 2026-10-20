package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValueReceiverCallbackOracleTest {
    @Test
    fun `builtin argument zero enters the anonymous extension receiver`() {
        val case = ReceiverCallbackCase()
        val position = ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)
        val transfer = enter(case, index = 0, position = position).flowRefined()
        assertEquals(ValueRole.Formal(position), transfer.target.role)
        assertEquals(case.frame.callee, transfer.target.executionOwner)
        assertEquals(listOf(case.supplying, case.frame), transfer.target.callStack.frames)
    }

    @Test
    fun `builtin argument one enters ordinary parameter zero after the receiver slot`() {
        val case = ReceiverCallbackCase()
        val position = ValueFormalPosition.Parameter(case.formal)
        val transfer = enter(case, index = 1, position = position).flowRefined()
        assertEquals(0, case.formal.position.value)
        assertEquals(ValueRole.Formal(position), transfer.target.role)
        assertEquals(case.formal.range, transfer.target.range)
    }

    @Test
    fun `ordinary argument one cannot substitute for the anonymous extension receiver`() {
        val case = ReceiverCallbackCase()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            enter(case, index = 1, position = ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
        )
    }

    @Test
    fun `receiver argument zero cannot substitute for ordinary parameter zero`() {
        val case = ReceiverCallbackCase()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            enter(case, index = 0, position = ValueFormalPosition.Parameter(case.formal)),
        )
    }

    private fun enter(case: ReceiverCallbackCase, index: Int, position: ValueFormalPosition) =
        ValueTransfer.fromCompiler(
            source = argument(case, index),
            target = target(case, position),
            kind = ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(case.frame, position)),
        )

    private fun argument(case: ReceiverCallbackCase, index: Int) =
        ValueSite.fromCompiler(
                enclosing = case.invocation.enclosing,
                range = flowRange(502 + index * 8, 506 + index * 8),
                role = ValueRole.FunctionArgument(case.invocation, ValueArgumentPosition.parse(index).flowRefined()),
                callStack = ValueCallStack.Empty.enter(case.supplying).flowRefined(),
            )
            .flowRefined()

    private fun target(case: ReceiverCallbackCase, position: ValueFormalPosition): ValueSite {
        val owner = case.frame.callee
        val range =
            when (position) {
                is ValueFormalPosition.Receiver -> owner.range
                is ValueFormalPosition.Parameter -> position.parameter.range
            }
        return ValueSite.fromCompiler(
                enclosing = owner.enclosing,
                range = range,
                role = ValueRole.Formal(position),
                executionOwner = owner,
                callStack = ValueCallStack.Empty.enter(case.supplying).flowRefined().enter(case.frame).flowRefined(),
            )
            .flowRefined()
    }
}

private class ReceiverCallbackCase {
    val host = flowEndpoint("handle", 0, 300) as RelationEndpoint.Resolved
    val callable =
        flowEndpoint(name = "withScope", start = 400, end = 600, parameters = listOf("Context.(String)->String"))
            as RelationEndpoint.Resolved
    val supply = ValueInvocation.fromCompiler(host, flowRange(20, 100), callable).flowRefined()
    val supplying = ValueCallFrame.direct(supply, ValueExecutionOwner.declaration(callable)).flowRefined()
    val bodyRange = flowRange(40, 80)
    val signature =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(host.file, bodyRange),
                rawReceiverType = "Context",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("String"),
                rawTypeParameterCount = 0,
            )
            .flowRefined() as CanonicalCompilerSignature.Function
    val body = RelationCallableBody.Anonymous.fromCompiler(host.file, bodyRange, signature).flowRefined()
    val owner = ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).flowRefined()
    val binding =
        CallbackArgumentBinding.fromCompiler(
                invocation = supply,
                invocationOwner = RelationCallableBody.Named.fromCompiler(host.evidence).flowRefined(),
                position = callbackOracleIndex(),
                parameter = RelationOccurrence.fromBoundary(callable.file, 410, 430).flowRefined(),
            )
            .flowRefined()
    val selected =
        CallbackParameterInvocation.fromCompiler(
                RelationOccurrence.fromBoundary(callable.file, 500, 520).flowRefined(),
                RelationCallableBody.Named.fromCompiler(callable.evidence).flowRefined(),
            )
            .flowRefined()
    val flow =
        CallbackInvocationFlow.fromCompiler(
                basis = host.lease.identity,
                body = body,
                binding = CallbackBindingEvidence.Bound(binding),
                invocations = listOf(selected),
                obligations = emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .flowRefined()
    val builtin =
        SourceLessCallable.fromCompiler(
                signature =
                    CanonicalCompilerSignature.function(
                            rawQualifiedIdentity = "kotlin.Function2.invoke",
                            rawReceiverType = null,
                            rawContextReceiverTypes = emptyList(),
                            rawValueParameterTypes = listOf("Context", "String"),
                            rawTypeParameterCount = 0,
                        )
                        .flowRefined(),
                kind = CompilerSymbolKind.FUNCTION,
                origin = SourceLessCallableOrigin.LIBRARY,
                moduleKind = SourceLessCallableModuleKind.BUILTINS,
                moduleName = SourceLessCallableModuleName.parse("kotlin-stdlib").flowRefined(),
            )
            .flowRefined()
    val invocation = ValueFunctionInvocation.fromCompiler(callable, selected.occurrence.range, builtin).flowRefined()
    val frame =
        ValueCallFrame.callback(
                invocation = invocation,
                callee = owner,
                flow = flow,
                selected = selected,
                supplyingFrame = supplying,
            )
            .flowRefined()
    val formal =
        ValueFormalParameter.fromCompiler(
                callable = host,
                position = callbackOracleIndex(),
                name = SymbolDiscoveryCandidateName.parse("item").flowRefined(),
                range = flowRange(45, 49),
                executionOwner = owner,
            )
            .flowRefined()
}
