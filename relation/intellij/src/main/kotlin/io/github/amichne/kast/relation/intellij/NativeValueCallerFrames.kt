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
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.call
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol

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
        call: NativeValueCallSyntax,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
        expectedOwner: ValueExecutionOwner,
        allowance: IntellijRelationAllowance,
    ): Refinement<ValueInvocation, ValueFlowUnsupportedCause> =
        boundInvocation(
            call = call,
            host = host,
            enclosing = enclosing,
            admission = NativeCallerOwnerAdmission.Retained(expectedOwner),
            allowance = allowance,
        )

    fun initialInvocation(
        call: NativeValueCallSyntax,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
        allowance: IntellijRelationAllowance,
    ): Refinement<ValueInvocation, ValueFlowUnsupportedCause> =
        boundInvocation(
            call = call,
            host = host,
            enclosing = enclosing,
            admission = NativeCallerOwnerAdmission.Initial(allowance),
            allowance = allowance,
        )

    private fun boundInvocation(
        call: NativeValueCallSyntax,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
        admission: NativeCallerOwnerAdmission,
        allowance: IntellijRelationAllowance,
    ): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
        val bound =
            projection.observation.call(IntellijReadCall.VALUE_RETURN_CALL_BINDING) {
                selectedCallable(call, enclosing, allowance)
            }
        projection.observation.count(
            if (bound is Refinement.Refined) IntellijReadCounter.VALUE_RETURN_CALL_BINDINGS_ADMITTED
            else IntellijReadCounter.VALUE_RETURN_CALL_BINDINGS_REJECTED
        )
        val callee =
            when (bound) {
                is Refinement.Refined -> bound.value
                is Refinement.Rejected -> return bound
            }
        return admittedInvocation(call, host, enclosing, admission, callee)
    }

    private fun admittedInvocation(
        call: NativeValueCallSyntax,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
        admission: NativeCallerOwnerAdmission,
        callee: RelationEndpoint,
    ): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
        val expression = call.anchor
        val admitted =
            projection.observation.call(IntellijReadCall.VALUE_RETURN_OWNER_ADMISSION) {
                executionOwner(expression, host, enclosing, admission)
            }
        projection.observation.count(
            if (admitted is Refinement.Refined) IntellijReadCounter.VALUE_RETURN_OWNERS_ADMITTED
            else IntellijReadCounter.VALUE_RETURN_OWNERS_REJECTED
        )
        val execution =
            when (admitted) {
                is Refinement.Refined -> admitted.value.owner
                is Refinement.Rejected -> return admitted
            }
        return when (
            val value =
                if (
                    (call is NativeValueCallSyntax.Component || call is NativeValueCallSyntax.PropertyRead) &&
                        callee.signature is CanonicalCompilerSignature.Property
                )
                    ValueInvocation.getterFromCompiler(
                        enclosing = enclosing,
                        range = sourceValueRange(expression),
                        callable = callee,
                        executionOwner = execution,
                    )
                else
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

    private fun selectedCallable(
        call: NativeValueCallSyntax,
        enclosing: RelationEndpoint,
        allowance: IntellijRelationAllowance,
    ): Refinement<RelationEndpoint, ValueFlowUnsupportedCause> {
        if (call is NativeValueCallSyntax.StringConversion)
            return nativeValueCallableEndpoint(
                declaration = call.function,
                enclosing = enclosing,
                scope = scope,
                projection = projection,
            )
        if (call is NativeValueCallSyntax.Component) return selectedComponent(call, enclosing, allowance)
        if (call is NativeValueCallSyntax.PropertyRead)
            return when (val selected = nativeNamedGetter(call, projection)) {
                is Refinement.Refined ->
                    nativeValueCallableEndpoint(
                        declaration = selected.value,
                        enclosing = enclosing,
                        scope = scope,
                        projection = projection,
                    )
                is Refinement.Rejected -> selected
            }
        val declaration =
            projection.observation.observedAnalyze(call.element) {
                (resolveNativeValueCall(call)?.signature?.symbol as? KaNamedFunctionSymbol)?.psi as? PsiNamedElement
            } ?: return rejected()
        return nativeValueCallableEndpoint(
            declaration = declaration,
            enclosing = enclosing,
            scope = scope,
            projection = projection,
        )
    }

    private fun selectedComponent(
        call: NativeValueCallSyntax.Component,
        enclosing: RelationEndpoint,
        allowance: IntellijRelationAllowance,
    ): Refinement<RelationEndpoint, ValueFlowUnsupportedCause> {
        val admission =
            when (
                val value =
                    call.admit(
                        enclosing = enclosing,
                        scope = scope,
                        projection = projection,
                        permit = { allowance.permitDestructuring(scope) },
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected ->
                    return if (value.failure == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION) rejected() else value
            }
        return when (val target = admission.target(call.entry)) {
            is Refinement.Rejected -> target
            is Refinement.Refined ->
                when (val selected = target.value) {
                    is NativeComponentTarget.Field -> rejected()
                    is NativeComponentTarget.Callable ->
                        nativeValueCallableEndpoint(
                            declaration = selected.declaration,
                            enclosing = enclosing,
                            scope = scope,
                            projection = projection,
                        )
                }
        }
    }

    private fun executionOwner(
        expression: PsiElement,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
        admission: NativeCallerOwnerAdmission,
    ): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> =
        when (admission) {
            is NativeCallerOwnerAdmission.Retained ->
                when (
                    val result =
                        restoreCapturedValueExecution(
                            element = expression,
                            host = host,
                            expected = admission.owner,
                            scope = scope,
                            projection = projection,
                        )
                ) {
                    is Refinement.Refined -> result
                    is Refinement.Rejected -> rejected()
                }
            is NativeCallerOwnerAdmission.Initial ->
                initialExecutionOwner(expression, host, enclosing, admission.allowance)
        }

    private fun initialExecutionOwner(
        expression: PsiElement,
        host: PsiNamedElement,
        enclosing: RelationEndpoint,
        allowance: IntellijRelationAllowance,
    ): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> =
        when (val plain = nativeValueExecutionOwner(expression, host, enclosing)) {
            is Refinement.Refined -> plain
            is Refinement.Rejected ->
                if (plain.failure != ValueFlowRejection.NESTED_EXECUTION) rejected()
                else
                    nativeValueCaptureOwner(
                        element = expression,
                        host = host,
                        enclosing = enclosing,
                        scope = scope,
                        projection = projection,
                        allowance = allowance,
                        admitWork = { allowance.admitCallbackWork(scope.request.budget.resources) },
                        expiry = {
                            if (allowance.elapsedLimitReached(scope.request.budget.resources))
                                CallbackExpiryAdmission.EXPIRED
                            else CallbackExpiryAdmission.CURRENT
                        },
                    )
        }

    private fun rejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
        Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
}

private sealed interface NativeCallerOwnerAdmission {
    data class Initial(val allowance: IntellijRelationAllowance) : NativeCallerOwnerAdmission

    data class Retained(val owner: ValueExecutionOwner) : NativeCallerOwnerAdmission
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
    val failures = mutableListOf<io.github.amichne.kast.relation.contract.ValueCallbackInputFailure>()
    val captured =
        nativeValueCaptureOwner(
            element,
            host,
            enclosing,
            scope,
            projection,
            allowance,
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
            recordFailure = { failures.add(it) },
        )
    return when (captured) {
        is Refinement.Refined -> Refinement.Refined(initialValueRead(captured.value, allowance.examined))
        is Refinement.Rejected ->
            Refinement.Rejected(NativeInitialValueFailure.Unsupported(captured.failure, failures.toList()))
    }
}

internal data class NativeInitialValueRead(
    val execution: NativeValueExecution,
    val examinedWork: io.github.amichne.kast.relation.contract.RelationWorkCount,
) {
    val owner: ValueExecutionOwner
        get() = execution.owner
}

private fun initialValueRead(execution: NativeValueExecution, work: Long): NativeInitialValueRead =
    when (val count = io.github.amichne.kast.relation.contract.RelationWorkCount.parse(work)) {
        is Refinement.Refined -> NativeInitialValueRead(execution, count.value)
        is Refinement.Rejected -> error("Native capture work is nonnegative")
    }

internal sealed interface NativeInitialValueFailure {
    data class Native(val cause: ValueFlowRejection) : NativeInitialValueFailure

    data class Unsupported(
        val cause: ValueFlowUnsupportedCause,
        val callbackFailures: List<io.github.amichne.kast.relation.contract.ValueCallbackInputFailure> = emptyList(),
    ) : NativeInitialValueFailure

    fun seedRejection(): ValueFlowRejection =
        when (this) {
            is Native -> cause
            is Unsupported -> ValueFlowRejection.NESTED_EXECUTION
        }
}
