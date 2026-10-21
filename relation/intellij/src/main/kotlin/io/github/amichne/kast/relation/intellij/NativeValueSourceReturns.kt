@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Native callers come from actual references to the declaration or its compiler-proven overridden declarations. */
internal class NativeValueSourceReturns(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
) {
    private val source = request.source
    private val frames = NativeValueCallerFrames(source, scope, projection)
    val isMatched: Boolean
        get() = source.callStack.frames.isNotEmpty()

    fun matched(consume: (NativeValueReturnEffect) -> Boolean): Boolean {
        val expected = source.callStack.frames.lastOrNull() ?: return consume(unsupported())
        if (source.executionOwner != expected.callee) return consume(unsupported())
        val invocation =
            when (val value = matchedInvocation(expected.invocation)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(NativeValueReturnEffect.Transfer(value))
            }
        if (invocation != expected.invocation) return consume(unsupported())
        val target =
            when (val value = target(invocation.callable)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(value.failure)
            }
        if (!announce(target, consume)) return false
        return consume(outcome(matchedTransfer(expected, invocation, target), target))
    }

    private fun matchedInvocation(expected: ValueInvocation): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
        val host =
            when (val native = projection.subject(scope, expected.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> native.declaration
                is IntellijRelationSubjectLookup.Rejected -> return returnRejected()
            }
        val expression = host.exactValueElement(expected.range) ?: return returnRejected()
        val call =
            when (expression) {
                is KtCallElement -> expression
                is KtQualifiedExpression -> expression.selectorExpression as? KtCallElement
                else -> null
            } ?: return returnRejected()
        return frames.invocation(call, host, expected.enclosing)
    }

    private fun matchedTransfer(
        expected: ValueCallFrame,
        invocation: ValueInvocation,
        target: NativeValueCallerTarget,
    ): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
        val frame =
            when (val value = frames.frame(invocation, target)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        if (frame != expected) return returnRejected()
        val stack =
            when (val value = source.callStack.exit(frame)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return returnRejected()
            }
        return returned(frame, stack, ValueSourceOperation.Return(frame))
    }

    fun forEachCaller(
        owner: PsiNamedElement,
        permit: () -> Boolean,
        consume: (NativeValueReturnEffect) -> Boolean,
    ): Boolean {
        val inventory = overridden(owner, permit)
        for (declaration in (listOf(owner) + inventory.declarations).distinctBy { it.originalElement }) {
            if (!forDeclaration(declaration, permit, consume)) return false
        }
        return when (inventory) {
            is NativeValueOverrideInventory.Complete -> true
            is NativeValueOverrideInventory.Partial -> false
            is NativeValueOverrideInventory.Unsupported -> {
                consume(unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE))
                false
            }
        }
    }

    private fun overridden(owner: PsiNamedElement, permit: () -> Boolean): NativeValueOverrideInventory {
        if (owner !is KtNamedFunction) return NativeValueOverrideInventory.Complete(emptyList())
        return projection.observation.observedAnalyze(owner) {
            val declarations = mutableListOf<PsiNamedElement>()
            for (symbol in owner.symbol.allOverriddenSymbols) {
                if (!permit()) return@observedAnalyze NativeValueOverrideInventory.Partial(declarations)
                val declaration =
                    symbol.psi as? PsiNamedElement
                        ?: return@observedAnalyze NativeValueOverrideInventory.Unsupported(declarations)
                declarations += declaration
            }
            NativeValueOverrideInventory.Complete(declarations)
        }
    }

    private fun forDeclaration(
        declaration: PsiNamedElement,
        permit: () -> Boolean,
        consume: (NativeValueReturnEffect) -> Boolean,
    ): Boolean {
        if (!permit()) return false
        val callable =
            when (
                val value =
                    nativeValueCallableEndpoint(
                        declaration = declaration,
                        enclosing = source.enclosing,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(NativeValueReturnEffect.Transfer(value))
            }
        val selected by lazy(LazyThreadSafetyMode.NONE) { target(callable) }
        return NativeValueCallerReferences(
                request = request,
                scope = scope,
                projection = projection,
                allowance = allowance,
            )
            .read(
                declaration = declaration,
                callable = callable,
                process = { reference ->
                    caller(
                        reference = reference,
                        callable = callable,
                        permit = permit,
                        consume = consume,
                        selected = { selected },
                    )
                },
                consume = consume,
            )
    }

    private fun caller(
        reference: PsiReference,
        callable: RelationEndpoint,
        permit: () -> Boolean,
        consume: (NativeValueReturnEffect) -> Boolean,
        selected: () -> Refinement<NativeValueCallerTarget, NativeValueReturnEffect>,
    ): Boolean {
        if (!permit()) return false
        val invocation =
            when (val value = seedInvocation(reference)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(NativeValueReturnEffect.Transfer(value))
            }
        if (invocation.callable.valueIdentity != callable.valueIdentity) return consume(unsupported())
        return when (val value = selected()) {
            is Refinement.Refined ->
                announce(value.value, consume) && consume(outcome(seedReturn(invocation, value.value), value.value))
            is Refinement.Rejected -> consume(value.failure)
        }
    }

    private fun target(callable: RelationEndpoint): Refinement<NativeValueCallerTarget, NativeValueReturnEffect> =
        when (
            val dispatch =
                NativeValueImplementationDispatch(
                        request = request,
                        scope = scope,
                        projection = projection,
                        allowance = allowance,
                    )
                    .forCallable(callable)
        ) {
            NativeValueArgumentDispatch.Direct -> Refinement.Refined(NativeValueCallerTarget.Direct(callable))
            is NativeValueArgumentDispatch.Implementations ->
                Refinement.Refined(NativeValueCallerTarget.Implementation(callable, dispatch.evidence))
            is NativeValueArgumentDispatch.Unsupported -> Refinement.Rejected(unsupported(dispatch.cause))
            is NativeValueArgumentDispatch.Rejected ->
                Refinement.Rejected(NativeValueReturnEffect.Rejected(dispatch.cause))
        }

    private fun announce(target: NativeValueCallerTarget, consume: (NativeValueReturnEffect) -> Boolean): Boolean =
        when (target) {
            is NativeValueCallerTarget.Direct -> true
            is NativeValueCallerTarget.Implementation -> consume(NativeValueReturnEffect.Dispatch(target.dispatch))
        }

    private fun seedInvocation(reference: PsiReference): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
        val call =
            PsiTreeUtil.getParentOfType(reference.element, KtCallExpression::class.java, false)
                ?: return returnRejected()
        if (call.calleeExpression?.textRange?.contains(reference.element.textRange) != true) return returnRejected()
        val lexical =
            when (val value = call.nearestDeclaration()) {
                is ContainingDeclaration.Deferred -> value.enclosingDeclaration()
                else -> value
            }
                as? ContainingDeclaration.Found ?: return returnRejected()
        val enclosing =
            when (
                val value =
                    nativeValueCallableEndpoint(
                        declaration = lexical.declaration,
                        enclosing = source.enclosing,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        return frames.invocation(call, lexical.declaration, enclosing)
    }

    private fun seedReturn(
        invocation: ValueInvocation,
        target: NativeValueCallerTarget,
    ): Refinement<ValueTransfer, ValueFlowUnsupportedCause> =
        when (val frame = frames.frame(invocation, target)) {
            is Refinement.Refined ->
                returned(frame.value, source.callStack, ValueSourceOperation.SeedReturn(frame.value))
            is Refinement.Rejected -> frame
        }

    private fun returned(
        frame: ValueCallFrame,
        stack: ValueCallStack,
        operation: ValueSourceOperation,
    ): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
        val target =
            when (val value = frame.invocation.resultSite().withState(source.valuePath, stack)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return returnRejected()
            }
        return sourceTransfer(
            source = source,
            target = target,
            kind = ValueTransferKind.SOURCE_CALL_RETURN,
            operation = operation,
        )
    }

    private fun outcome(
        result: Refinement<ValueTransfer, ValueFlowUnsupportedCause>,
        target: NativeValueCallerTarget,
    ): NativeValueReturnEffect =
        if (result is Refinement.Rejected && target is NativeValueCallerTarget.Implementation)
            NativeValueReturnEffect.UnsupportedDispatch(result.failure, target.dispatch)
        else NativeValueReturnEffect.Transfer(result)
}

private fun returnRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)

private fun unsupported(
    cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.UNSUPPORTED_RETURN
): NativeValueReturnEffect = NativeValueReturnEffect.Transfer(Refinement.Rejected(cause))

private sealed interface NativeValueOverrideInventory {
    val declarations: List<PsiNamedElement>

    data class Complete(override val declarations: List<PsiNamedElement>) : NativeValueOverrideInventory

    data class Partial(override val declarations: List<PsiNamedElement>) : NativeValueOverrideInventory

    data class Unsupported(override val declarations: List<PsiNamedElement>) : NativeValueOverrideInventory
}
