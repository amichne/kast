@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection
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
        expectedOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(enclosing),
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
                    restoreCapturedValueExecution(
                        element = expression,
                        host = host,
                        expected = expectedOwner,
                        scope = scope,
                        projection = projection,
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

/** Initial anonymous seeds require the same bounded invocation proof as captured reads. */
internal fun nativeValueInitialExecution(
    element: PsiElement,
    host: PsiElement,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    reservedWork: Long = 0L,
): Refinement<NativeInitialValueRead, NativeInitialValueFailure> {
    val plain = nativeValueExecutionOwner(element, host, enclosing)
    when (plain) {
        is Refinement.Refined -> return Refinement.Refined(initialValueRead(plain.value, 0L))
        is Refinement.Rejected ->
            if (plain.failure != ValueFlowRejection.NESTED_EXECUTION)
                return Refinement.Rejected(NativeInitialValueFailure.Native(plain.failure))
    }
    val allowance = IntellijRelationAllowance(System.nanoTime()) { System.nanoTime() }
    val captured =
        nativeValueCaptureOwner(
            element,
            host,
            enclosing,
            scope,
            projection,
            admitWork = {
                when {
                    allowance.elapsedLimitReached(scope.request.budget.resources) ->
                        CallbackWorkAdmission.TIME_LIMIT_REACHED
                    !scope.request.budget.resources.hasWorkCapacity(reservedWork + allowance.examined) ->
                        CallbackWorkAdmission.WORK_LIMIT_REACHED
                    else -> {
                        allowance.examine()
                        CallbackWorkAdmission.READY
                    }
                }
            },
            expiry = {
                if (allowance.elapsedLimitReached(scope.request.budget.resources)) CallbackExpiryAdmission.EXPIRED
                else CallbackExpiryAdmission.CURRENT
            },
        )
    return when (captured) {
        is Refinement.Refined -> Refinement.Refined(initialValueRead(captured.value, allowance.examined))
        is Refinement.Rejected -> Refinement.Rejected(NativeInitialValueFailure.Unsupported(captured.failure))
    }
}

internal data class NativeInitialValueRead(
    val owner: ValueExecutionOwner,
    val examinedWork: io.github.amichne.kast.relation.contract.RelationWorkCount,
)

private fun initialValueRead(execution: NativeValueExecution, work: Long): NativeInitialValueRead =
    when (val count = io.github.amichne.kast.relation.contract.RelationWorkCount.parse(work)) {
        is Refinement.Refined -> NativeInitialValueRead(execution.owner, count.value)
        is Refinement.Rejected -> error("Native capture work is nonnegative")
    }

internal sealed interface NativeInitialValueFailure {
    data class Native(val cause: ValueFlowRejection) : NativeInitialValueFailure

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeInitialValueFailure

    fun seedRejection(): ValueFlowRejection =
        when (this) {
            is Native -> cause
            is Unsupported -> ValueFlowRejection.NESTED_EXECUTION
        }
}
