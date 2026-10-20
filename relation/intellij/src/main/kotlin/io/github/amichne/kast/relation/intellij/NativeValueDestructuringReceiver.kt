@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction

/** The native coordinator supplies every entry allowance and keeps the resulting one-hop facts. */
internal class NativeValueDestructuringReceivers(
    private val source: ValueSite,
    private val nativeOwner: PsiElement,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val retained: NativeValueFlowTransfers,
) {
    fun read(expression: KtExpression, declaration: KtDestructuringDeclaration, permit: () -> Boolean) {
        if (declaration.initializer !== expression) {
            retained.unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        for (entry in declaration.entries) {
            if (entry.name == "_") continue
            if (!permit()) return
            when (
                val receiver = nativeDestructuringReceiver(entry, source.executionOwner, nativeOwner, scope, projection)
            ) {
                is Refinement.Refined -> retained.emit(entry, receiver.value, ValueTransferKind.RECEIVER)
                is Refinement.Rejected -> retained.unresolved(receiver.failure)
            }
        }
    }
}

/** K2 owns the implicit operator occurrence and both receivers at the real destructuring entry. */
internal fun nativeDestructuringReceiver(
    entry: KtDestructuringDeclarationEntry,
    executionOwner: ValueExecutionOwner,
    nativeOwner: PsiElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    val enclosing = executionOwner.enclosing
    val declaration =
        projection.observation.observedAnalyze(entry) {
            val call = entry.resolveCall() as? KaFunctionCall<*> ?: return@observedAnalyze null
            val receiver = call.extensionReceiver as? KaExplicitReceiverValue ?: return@observedAnalyze null
            if (
                receiver.expression.originalElement != entry.originalElement ||
                    sourceValueRange(receiver.expression) != sourceValueRange(entry) ||
                    !sameExecutionReceiver(call.dispatchReceiver, nativeOwner)
            )
                return@observedAnalyze null
            (call.signature.symbol as? KaNamedFunctionSymbol)?.psi as? PsiNamedElement
        } ?: return destructuringRejected()
    val callable =
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
    return when (
        val invocation =
            ValueInvocation.fromCompiler(
                enclosing = enclosing,
                range = sourceValueRange(entry),
                callable = callable,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Receiver(invocation.value, ValueReceiverKind.EXTENSION))
        is Refinement.Rejected -> destructuringRejected()
    }
}

private fun KaSession.sameExecutionReceiver(receiver: KaReceiverValue?, owner: PsiElement): Boolean {
    if (receiver == null) return true
    val implicit = receiver as? KaImplicitReceiverValue ?: return false
    val execution =
        when (owner) {
            is KtFunctionLiteral -> owner.symbol
            is KtNamedFunction -> owner.symbol
            else -> return false
        }
    return implicit.symbol == execution.receiverParameter
}

private fun destructuringRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
