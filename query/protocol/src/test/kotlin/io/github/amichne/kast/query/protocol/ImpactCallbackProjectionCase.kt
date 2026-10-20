package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ImpactCallbackProjectionCase(qualified: Boolean = false) {
    val base = ImpactPathProjectionFixture()
    val references = CanonicalQueryReferences()
    private val host = base.owner as RelationEndpoint.Resolved
    private val mapper = base.call.callable as RelationEndpoint.Resolved
    private val zero = ValueArgumentPosition.parse(0).value()
    private val bodyRange = range(40, 80)
    private val body =
        RelationCallableBody.Anonymous.fromCompiler(
                host.file,
                bodyRange,
                CanonicalCompilerSignature.function(
                        RelationCallableBody.Anonymous.sourceIdentity(host.file, bodyRange),
                        null,
                        emptyList(),
                        listOf("String"),
                        0,
                    )
                    .value() as CanonicalCompilerSignature.Function,
            )
            .value()
    private val owner = ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).value()
    private val supplyingCall = ValueInvocation.fromCompiler(host, range(30, 100), mapper).value()
    private val supplyingFrame = ValueCallFrame.direct(supplyingCall, ValueExecutionOwner.declaration(mapper)).value()
    private val binding =
        CallbackArgumentBinding.fromCompiler(
                supplyingCall,
                RelationCallableBody.Named.fromCompiler(host.evidence).value(),
                zero,
                RelationOccurrence.fromBoundary(mapper.file, 211, 220).value(),
            )
            .value()
    val selected =
        CallbackParameterInvocation.fromCompiler(
                RelationOccurrence.fromBoundary(mapper.file, 230, 240).value(),
                RelationCallableBody.Named.fromCompiler(mapper.evidence).value(),
            )
            .value()
    val flow =
        CallbackInvocationFlow.fromCompiler(
                host.lease.identity,
                body,
                CallbackBindingEvidence.Bound(binding),
                listOf(selected),
                if (qualified) setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED) else emptySet(),
                scan = if (qualified) CallbackInvocationScan.INCOMPLETE else CallbackInvocationScan.EXHAUSTIVE,
            )
            .value()
    private val callable =
        SourceLessCallable.fromCompiler(
                CanonicalCompilerSignature.function("kotlin.Function1.invoke", null, emptyList(), listOf("String"), 0)
                    .value(),
                CompilerSymbolKind.FUNCTION,
                SourceLessCallableOrigin.LIBRARY,
                SourceLessCallableModuleKind.BUILTINS,
                SourceLessCallableModuleName.parse("kotlin-stdlib").value(),
            )
            .value()
    private val invocation = ValueFunctionInvocation.fromCompiler(mapper, selected.occurrence.range, callable).value()
    val frame = ValueCallFrame.callback(invocation, owner, flow, selected, supplyingFrame).value()
    private val position =
        ValueFormalPosition.Parameter(
            ValueFormalParameter.fromCompilerImplicit(
                    owner,
                    zero,
                    SymbolDiscoveryCandidateName.parse("it").value(),
                )
                .value()
        )
    private val stack = ValueCallStack.Empty.enter(supplyingFrame).value()
    val argument =
        ValueSite.fromCompiler(mapper, range(232, 238), ValueRole.FunctionArgument(invocation, zero), callStack = stack)
            .value()
    val formal =
        ValueSite.fromCompiler(
                host,
                bodyRange,
                ValueRole.Formal(position),
                owner,
                callStack = stack.enter(frame).value(),
            )
            .value()
    val entered =
        ValueTransfer.fromCompiler(
                argument,
                formal,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(frame, position)),
            )
            .value()

    fun siblingBody(): RelationCallableBody.Anonymous {
        val range = range(60, 75)
        return RelationCallableBody.Anonymous.fromCompiler(
                host.file,
                range,
                CanonicalCompilerSignature.function(
                        RelationCallableBody.Anonymous.sourceIdentity(host.file, range),
                        null,
                        emptyList(),
                        listOf("String"),
                        0,
                    )
                    .value() as CanonicalCompilerSignature.Function,
            )
            .value()
    }

    fun returned(): ValueTransfer {
        val result = invocation.resultSite().withState(callStack = stack).value()
        val returned =
            ValueSite.fromCompiler(host, range(75, 79), ValueRole.Return, owner, callStack = formal.callStack).value()
        return ValueTransfer.fromCompiler(
                returned,
                result,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
            )
            .value()
    }
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
