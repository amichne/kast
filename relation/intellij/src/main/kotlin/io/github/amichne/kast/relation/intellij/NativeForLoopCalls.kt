@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueLoopIteration
import io.github.amichne.kast.relation.contract.ValueLoopProtocol
import io.github.amichne.kast.relation.contract.ValueLoopResult
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** The three synthetic receiver relationships come from one K2 loop resolution, never an operator-name search. */
internal class NativeForLoopCalls
private constructor(
    val protocol: ValueLoopProtocol,
    val iterator: KtNamedFunction,
    val hasNext: KtNamedFunction,
    val next: KtNamedFunction,
) {
    fun result(
        invocation: ValueInvocation,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    ): Refinement<ValueLoopResult, ValueFlowUnsupportedCause> =
        when (invocation) {
            protocol.iterator -> Refinement.Refined(protocol.iteratorResult)
            protocol.next ->
                when (val admitted = normalIteration(scope, projection, permit)) {
                    is Refinement.Rejected -> admitted
                    is Refinement.Refined ->
                        when (val outcome = admitted.value) {
                            NativeLoopGuard.Empty -> unsupportedLoopCall()
                            is NativeLoopGuard.Iteration -> Refinement.Refined(outcome.proof.elementResult)
                        }
                }
            else -> unsupportedLoopCall()
        }

    fun normalIteration(
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    ): Refinement<NativeLoopGuard, ValueFlowUnsupportedCause> {
        val guarded = guard(projection, permit)
        if (guarded !is Refinement.Refined || guarded.value == NativeLoopGuard.Empty) return guarded
        val bodies = NativeNormalComponentBody(protocol.iterator.enclosing, scope, projection, permit)
        for (function in listOf(iterator, next)) {
            when (val admitted = bodies.readLoop(function)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
        }
        return guarded
    }

    fun guard(
        projection: IntellijK2RelationProjection,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    ): Refinement<NativeLoopGuard, ValueFlowUnsupportedCause> {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        if (hasNext.hasBlockBody()) return unsupportedLoopCall()
        val body = hasNext.bodyExpression as? KtConstantExpression ?: return unsupportedLoopCall()
        return projection.observation.observedAnalyze(body) {
            when ((body.evaluate() as? KaConstantValue.BooleanValue)?.value) {
                true ->
                    Refinement.Refined(NativeLoopGuard.Iteration(ValueLoopIteration.fromCompilerNormalTrue(protocol)))
                false -> Refinement.Refined(NativeLoopGuard.Empty)
                null -> unsupportedLoopCall()
            }
        }
    }

    companion object {
        fun read(
            loop: KtForExpression,
            execution: ValueExecutionOwner,
            scope: CompiledRelationScope,
            projection: IntellijK2RelationProjection,
            permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        ): Refinement<NativeForLoopCalls, ValueFlowUnsupportedCause> {
            when (val allowed = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            return projection.observation.observedAnalyze(loop) {
                val calls = loop.resolveCall() ?: return@observedAnalyze unsupportedLoopCall()
                val selected = mutableListOf<Pair<KtNamedFunction, ValueInvocation>>()
                for (call in listOf(calls.iteratorCall, calls.hasNextCall, calls.nextCall)) {
                    when (val allowed = permit()) {
                        is Refinement.Refined -> Unit
                        is Refinement.Rejected -> return@observedAnalyze allowed
                    }
                    when (
                        val result =
                            select(
                                loop = loop,
                                call = call,
                                execution = execution,
                                scope = scope,
                                projection = projection,
                            )
                    ) {
                        is Refinement.Refined -> selected += result.value
                        is Refinement.Rejected -> return@observedAnalyze result
                    }
                }
                assemble(selected)
            }
        }

        private fun assemble(
            selected: List<Pair<KtNamedFunction, ValueInvocation>>
        ): Refinement<NativeForLoopCalls, ValueFlowUnsupportedCause> {
            val (iterator, hasNext, next) = selected
            return when (val protocol = ValueLoopProtocol.fromCompiler(iterator.second, hasNext.second, next.second)) {
                is Refinement.Refined ->
                    Refinement.Refined(NativeForLoopCalls(protocol.value, iterator.first, hasNext.first, next.first))
                is Refinement.Rejected -> unsupportedLoopCall()
            }
        }

        private fun KaSession.select(
            loop: KtForExpression,
            call: KaFunctionCall<KaNamedFunctionSymbol>,
            execution: ValueExecutionOwner,
            scope: CompiledRelationScope,
            projection: IntellijK2RelationProjection,
        ): Refinement<Pair<KtNamedFunction, ValueInvocation>, ValueFlowUnsupportedCause> {
            val symbol = call.signature.symbol
            val function = symbol.psi as? KtNamedFunction ?: return unsupportedLoopCall()
            val receiver = call.dispatchReceiver?.type as? KaClassType
            if (symbol.receiverParameter != null || call.extensionReceiver != null) return unsupportedLoopCall()
            if (!symbol.isOperator || symbol.modality != KaSymbolModality.FINAL || receiver?.isMarkedNullable != false)
                return unsupportedLoopCall()
            val callable =
                when (
                    val endpoint =
                        nativeValueCallableEndpoint(
                            declaration = function,
                            enclosing = execution.enclosing,
                            scope = scope,
                            projection = projection,
                        )
                ) {
                    is Refinement.Refined -> endpoint.value
                    is Refinement.Rejected -> return endpoint
                }
            return when (
                val invocation =
                    ValueInvocation.fromCompiler(
                        enclosing = execution.enclosing,
                        range = sourceValueRange(loop),
                        callable = callable,
                        executionOwner = execution,
                    )
            ) {
                is Refinement.Refined -> Refinement.Refined(function to invocation.value)
                is Refinement.Rejected -> unsupportedLoopCall()
            }
        }
    }
}

internal sealed interface NativeLoopGuard {
    data object Empty : NativeLoopGuard

    data class Iteration(val proof: ValueLoopIteration) : NativeLoopGuard
}

internal fun nativeLoopInvocationResult(
    loop: KtForExpression,
    invocation: ValueInvocation,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueLoopResult, ValueFlowUnsupportedCause> =
    when (val calls = NativeForLoopCalls.read(loop, invocation.executionOwner, scope, projection, permit)) {
        is Refinement.Rejected -> calls
        is Refinement.Refined -> calls.value.result(invocation, scope, projection, permit)
    }

private fun unsupportedLoopCall(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
