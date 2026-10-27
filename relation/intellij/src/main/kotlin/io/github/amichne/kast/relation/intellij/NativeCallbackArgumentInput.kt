package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction

/** Resolves only the active supplier call and the compiler-selected callback argument. */
internal class NativeCallbackArgumentInput(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val admitWork: () -> CallbackWorkAdmission,
    private val expiry: () -> CallbackExpiryAdmission,
) {
    private val context = IntellijCallbackFlowContext(scope, projection, admitWork, projection.observation, expiry)
    private val summaries = CallbackParameterSummaries(scope.request.budget, projection.observation)

    fun read(binding: NativeCallbackArgumentBinding.Mapped): Refinement<ValueTransfer, NativeCallbackInputFailure> {
        when (val permitted = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(NativeCallbackInputFailure.Callback(permitted.failure))
        }
        if (source.range != binding.argumentRange) return callbackInputRejected()
        val frame = source.callStack.frames.lastOrNull()
        if (frame == null) return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        if (frame.callee != source.executionOwner)
            return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        if (frame.callee.context != ValueExecutionContext.Declaration)
            return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        val formal =
            when (val admitted = formal(binding)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        if (formal.formal.callable.valueIdentity != frame.callee.enclosing.valueIdentity) return callbackInputRejected()
        val supplier =
            when (
                val admitted =
                    nativeCallbackInputSupplier(
                        frames = source.callStack.frames,
                        formal = formal,
                        context = context,
                        projection = projection,
                        summaries = summaries,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val supplierFrame =
            source.callStack.frames.singleOrNull { it == supplier.binding.frame }
                ?: return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        return enter(binding, supplierFrame, supplier)
    }

    private fun formal(
        binding: NativeCallbackArgumentBinding.Mapped
    ): Refinement<NativeCallbackSupplierFormal.Found, NativeCallbackInputFailure> {
        val host =
            when (val found = projection.subject(scope, source.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> found.declaration
                is IntellijRelationSubjectLookup.Rejected -> return callbackInputRejected()
            }
        val call = host.exactValueElement(binding.invocationRange)?.nativeValueCallSyntax()
        val actualCall = (call as? NativeValueCallSyntax.Explicit)?.call as? KtCallExpression
        val expression = host.exactValueElement(binding.argumentRange)
        if (actualCall == null || expression == null) return callbackInputRejected()
        when (val fresh = nativeCallbackArgumentBinding(actualCall, expression, projection.observation)) {
            is Refinement.Refined -> if (fresh.value != binding) return callbackInputRejected()
            is Refinement.Rejected -> return callbackInputRejected(fresh.failure)
        }
        val lexical = context.receiverDeclaration(host)
        if (lexical == null) return callbackInputRejected()
        return when (val read = IntellijCallbackSupplierFormal(context).read(binding.receiver, lexical)) {
            is Refinement.Rejected -> Refinement.Rejected(NativeCallbackInputFailure.Callback(read.failure))
            is Refinement.Refined ->
                when (val upstream = read.value) {
                    NativeCallbackSupplierFormal.NotFormal ->
                        callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                    is NativeCallbackSupplierFormal.Found -> Refinement.Refined(upstream)
                }
        }
    }

    private fun enter(
        binding: NativeCallbackArgumentBinding.Mapped,
        frame: ValueCallFrame,
        supplier: CallbackParameterSupplier,
    ): Refinement<ValueTransfer, NativeCallbackInputFailure> {
        val origin = supplier.value.origin
        if (
            origin !is ImmutableCallbackValueOrigin.Anonymous ||
                supplier.value.source.enclosing.valueIdentity != frame.invocation.enclosing.valueIdentity
        )
            return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        val host =
            when (val found = projection.subject(scope, supplier.value.source.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> found.declaration
                is IntellijRelationSubjectLookup.Rejected -> return callbackInputRejected()
            }
        val literal = host.exactValueElement(origin.body.range) as? KtFunction
        if (literal == null || context.anonymous(literal) != origin.body) return callbackInputRejected()
        return execution(binding, frame, supplier, host, literal)
    }

    private fun execution(
        binding: NativeCallbackArgumentBinding.Mapped,
        frame: ValueCallFrame,
        supplier: CallbackParameterSupplier,
        host: PsiNamedElement,
        literal: KtFunction,
    ): Refinement<ValueTransfer, NativeCallbackInputFailure> {
        val lexical = context.receiverDeclaration(host)
        if (lexical == null) return callbackInputRejected()
        val read =
            readSelectedCallbackInputFlow(
                supplier = supplier,
                frames = source.callStack.frames,
                literal = literal,
                context = context,
                summaries = summaries,
            )
        return when (
            val execution =
                nativeCallbackInputOwner(
                    read = read,
                    enclosing = supplier.value.source.enclosing,
                    lexical = lexical,
                    literal = literal,
                    selection = NativeCallbackInputSelection(source, binding, supplier),
                )
        ) {
            is Refinement.Rejected -> execution
            is Refinement.Refined ->
                source.nativeCallbackInputTransfer(binding, frame, supplier, execution.value, projection.observation)
        }
    }
}
