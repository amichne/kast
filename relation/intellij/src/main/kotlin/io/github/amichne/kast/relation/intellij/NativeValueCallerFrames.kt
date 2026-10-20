@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement

/** A caller target keeps its static declaration separate from the admitted concrete execution. */
internal sealed interface NativeValueCallerTarget {
    val callable: RelationEndpoint

    data class Direct(override val callable: RelationEndpoint) : NativeValueCallerTarget

    data class Implementation(
        override val callable: RelationEndpoint,
        val dispatch: ValueImplementationDispatch,
    ) : NativeValueCallerTarget
}

/** Reacquires the exact static call before the core admits a direct or implementation-qualified frame. */
internal class NativeValueCallerFrames(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
) {
    fun frame(
        invocation: ValueInvocation,
        target: NativeValueCallerTarget,
    ): Refinement<ValueCallFrame, ValueFlowUnsupportedCause> {
        if (invocation.callable.valueIdentity != target.callable.valueIdentity) return rejected()
        return when (target) {
            is NativeValueCallerTarget.Direct -> {
                if (target.callable.valueIdentity != source.enclosing.valueIdentity) return rejected()
                when (val value = ValueCallFrame.direct(invocation, source.executionOwner)) {
                    is Refinement.Refined -> value
                    is Refinement.Rejected -> rejected()
                }
            }
            is NativeValueCallerTarget.Implementation -> implementation(invocation, target.dispatch)
        }
    }

    private fun implementation(
        invocation: ValueInvocation,
        dispatch: ValueImplementationDispatch,
    ): Refinement<ValueCallFrame, ValueFlowUnsupportedCause> {
        val relation =
            dispatch.batch.facts.singleOrNull { it.source.valueIdentity == source.enclosing.valueIdentity }
                ?: return rejected()
        val frame =
            when (
                val value =
                    nativeValueImplementationFrame(
                        invocation = invocation,
                        dispatch = dispatch,
                        relation = relation,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        return if (frame.callee == source.executionOwner) Refinement.Refined(frame) else rejected()
    }

    fun invocation(
        call: KtCallElement,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
    ): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
        val declaration =
            projection.observation.observedAnalyze(call) {
                (call.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol)?.psi as? PsiNamedElement
            } ?: return rejected()
        val callee =
            when (
                val value =
                    nativeValueCallableEndpoint(
                        declaration = declaration,
                        enclosing = enclosing,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val expression = call.valueInvocationExpression()
        val execution =
            when (
                val value =
                    nativeValueExecutionOwner(
                        element = expression,
                        host = host,
                        enclosing = enclosing,
                    )
            ) {
                is Refinement.Refined -> value.value.owner
                is Refinement.Rejected -> return rejected()
            }
        return when (
            val value =
                ValueInvocation.fromCompiler(
                    enclosing = enclosing,
                    range = sourceValueRange(expression),
                    callable = callee,
                    executionOwner = execution,
                )
        ) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> rejected()
        }
    }

    private fun rejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
        Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
}
