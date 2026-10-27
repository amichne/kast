@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

internal data class NativeReceiverCallRead(
    val receivers: List<ValueRole.Receiver>,
    val causes: Set<ValueFlowUnsupportedCause>,
)

/** The source receiver formal and each actual implicit slot keep distinct compiler symbols and value identities. */
internal class NativeValueImplicitReceiverCalls(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
) {
    fun read(
        function: KtNamedFunction,
        call: KtCallExpression,
    ): NativeReceiverCallRead {
        val selected =
            when (val read = projection.observation.observedAnalyze(call) { selected(function, call) }) {
                NativeImplicitCall.Unrelated -> return NativeReceiverCallRead(emptyList(), emptySet())
                is NativeImplicitCall.Unsupported -> return NativeReceiverCallRead(emptyList(), setOf(read.cause))
                is NativeImplicitCall.Matched -> read
            }
        if (valueNativeOwnership(call, function) != NativeValueOwnership.ADMITTED)
            return NativeReceiverCallRead(emptyList(), selected.causes + ValueFlowUnsupportedCause.NESTED_EXECUTION)
        val callable =
            when (
                val value =
                    nativeValueCallableEndpoint(
                        declaration = selected.declaration,
                        enclosing = source.enclosing,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return NativeReceiverCallRead(emptyList(), selected.causes + value.failure)
            }
        val invocation =
            when (
                val value =
                    ValueInvocation.fromCompiler(
                        enclosing = source.enclosing,
                        range = sourceValueRange(call.valueInvocationExpression()),
                        callable = callable,
                        executionOwner = source.executionOwner,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected ->
                    return NativeReceiverCallRead(
                        emptyList(),
                        selected.causes + ValueFlowUnsupportedCause.UNMODELED_CALL,
                    )
            }
        return NativeReceiverCallRead(selected.kinds.map { ValueRole.Receiver(invocation, it) }, selected.causes)
    }

    private fun KaSession.selected(function: KtNamedFunction, call: KtCallExpression): NativeImplicitCall {
        val position =
            (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver
                ?: return NativeImplicitCall.Unsupported(ValueFlowUnsupportedCause.UNMODELED_CALL)
        val expected =
            when (position.kind) {
                ValueReceiverKind.EXTENSION -> function.symbol.receiverParameter
                ValueReceiverKind.DISPATCH -> function.symbol.containingDeclaration
            } ?: return NativeImplicitCall.Unsupported(ValueFlowUnsupportedCause.UNMODELED_CALL)
        val resolved =
            call.resolveCall() ?: return NativeImplicitCall.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        val kinds = mutableListOf<ValueReceiverKind>()
        val causes = mutableSetOf<ValueFlowUnsupportedCause>()
        for (kind in ValueReceiverKind.entries) {
            val receiver =
                when (kind) {
                    ValueReceiverKind.EXTENSION -> resolved.extensionReceiver
                    ValueReceiverKind.DISPATCH -> resolved.dispatchReceiver
                }
            when (implicitMatch(receiver, expected)) {
                NativeReceiverMatch.EXACT -> kinds += kind
                NativeReceiverMatch.OTHER -> Unit
                NativeReceiverMatch.UNAVAILABLE -> causes += ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION
            }
        }
        if (kinds.isEmpty())
            return if (causes.isEmpty()) NativeImplicitCall.Unrelated
            else NativeImplicitCall.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        val declaration =
            resolved.signature.symbol.psi as? PsiNamedElement
                ?: return NativeImplicitCall.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        return NativeImplicitCall.Matched(declaration, kinds, causes)
    }

    private fun KaSession.implicitMatch(receiver: KaReceiverValue?, expected: KaSymbol): NativeReceiverMatch =
        when (receiver) {
            is KaImplicitReceiverValue -> valueReceiverMatch(receiver, expected)
            is KaSmartCastedReceiverValue -> implicitMatch(receiver.original, expected)
            // Explicit receivers enter through their expression reads, including explicit this.
            is KaExplicitReceiverValue,
            null -> NativeReceiverMatch.OTHER
        }
}

internal fun nativeImplicitReceiverArrival(
    source: ValueSite,
    receiver: ValueRole.Receiver,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val target =
        when (
            val value =
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = receiver.call.range,
                    role = receiver,
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                    conditions = source.conditions,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return implicitRejected()
        }
    return when (val value = ValueTransfer.fromCompiler(source, target, ValueTransferKind.RECEIVER)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> implicitRejected()
    }
}

private sealed interface NativeImplicitCall {
    data object Unrelated : NativeImplicitCall

    data class Matched(
        val declaration: PsiNamedElement,
        val kinds: List<ValueReceiverKind>,
        val causes: Set<ValueFlowUnsupportedCause>,
    ) : NativeImplicitCall

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeImplicitCall
}

private fun implicitRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
