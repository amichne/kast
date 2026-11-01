@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtUnaryExpression

/** The native inventory remains read-local. Only exact anchors and the admitted execution identity escape. */
internal data class NativeLocalValueVersion(val binding: KtNamedDeclaration, val proof: ValueLocalVersion.Defined)

/** A component's implicit result has the entry anchor; the containing declaration owns its block position. */
private sealed interface NativeVersionBinding {
    val declaration: KtNamedDeclaration
    val statement: KtExpression
    val initialValue: KtExpression

    data class Property(override val declaration: KtProperty, override val initialValue: KtExpression) :
        NativeVersionBinding {
        override val statement: KtExpression
            get() = declaration
    }

    data class Component(
        override val declaration: KtDestructuringDeclarationEntry,
        override val statement: KtDestructuringDeclaration,
    ) : NativeVersionBinding {
        override val initialValue: KtExpression
            get() = declaration
    }
}

internal fun nativeLocalValueVersion(
    definition: PsiElement,
    execution: ValueExecutionOwner,
    observation: IntellijReadObservation,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<NativeLocalValueVersion, ValueFlowUnsupportedCause> {
    val expression = definition as? KtExpression ?: return mutableVersionRejected()
    return observation.observedAnalyze(expression) {
        val binding = localVersionBinding(expression) ?: return@observedAnalyze mutableVersionRejected()
        val block = binding.statement.parent as? KtBlockExpression ?: return@observedAnalyze mutableVersionRejected()
        val function =
            PsiTreeUtil.getParentOfType(block, KtNamedFunction::class.java)
                ?: return@observedAnalyze mutableVersionRejected()
        if (
            valueNativeOwnership(binding.declaration, function) != NativeValueOwnership.ADMITTED ||
                PsiTreeUtil.getParentOfType(block, KtLoopExpression::class.java) != null ||
                !hasVersionSymbol(binding.declaration)
        )
            return@observedAnalyze mutableVersionRejected()
        if (!definition.isVersionPosition(binding, block)) return@observedAnalyze mutableVersionRejected()
        when (
            val writes =
                collectLocalVersionWrites(
                    binding = binding.declaration,
                    block = block,
                    function = function,
                    permit = permit,
                )
        ) {
            is Refinement.Rejected -> writes
            is Refinement.Refined ->
                localVersionProof(
                    definition = definition,
                    execution = execution,
                    binding = binding,
                    block = block,
                    writes = writes.value,
                )
        }
    }
}

private fun PsiElement.isVersionPosition(binding: NativeVersionBinding, block: KtBlockExpression): Boolean =
    this is KtNameReferenceExpression || this === binding.declaration || parent === block

private fun KaSession.hasVersionSymbol(binding: KtNamedDeclaration): Boolean {
    val symbol = binding.symbol as? KaLocalVariableSymbol ?: return false
    return !symbol.isVal && !symbol.isLateInit && symbol.returnType !is KaErrorType
}

private fun KaSession.collectLocalVersionWrites(
    binding: KtNamedDeclaration,
    block: KtBlockExpression,
    function: KtNamedFunction,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<List<KtBinaryExpression>, ValueFlowUnsupportedCause> {
    val writes = mutableListOf<KtBinaryExpression>()
    var admission: Refinement<Unit, ValueFlowUnsupportedCause> = Refinement.Refined(Unit)
    PsiTreeUtil.processElements(block) { element ->
        admission =
            when (val allowed = permit()) {
                is Refinement.Rejected -> allowed
                is Refinement.Refined ->
                    classifyVersionElement(
                        element = element,
                        binding = binding,
                        block = block,
                        function = function,
                        writes = writes,
                    )
            }
        admission is Refinement.Refined
    }
    return when (val completed = admission) {
        is Refinement.Rejected -> completed
        is Refinement.Refined -> Refinement.Refined(writes)
    }
}

private fun localVersionProof(
    definition: PsiElement,
    execution: ValueExecutionOwner,
    binding: NativeVersionBinding,
    block: KtBlockExpression,
    writes: List<KtBinaryExpression>,
): Refinement<NativeLocalValueVersion, ValueFlowUnsupportedCause> {
    val actualDefinition =
        if (definition is KtNameReferenceExpression)
            (listOf(binding.declaration) + writes)
                .filter { it.textRange.endOffset <= definition.textRange.startOffset }
                .maxByOrNull { it.textRange.endOffset } ?: return mutableVersionRejected()
        else definition
    val rhs =
        when (actualDefinition) {
            binding.declaration -> binding.initialValue
            is KtBinaryExpression -> if (actualDefinition in writes) actualDefinition.right else null
            else -> null
        } ?: return mutableVersionRejected()
    val next =
        writes
            .filter { it.textRange.startOffset >= actualDefinition.textRange.endOffset }
            .minByOrNull { it.textRange.startOffset }
    val end =
        if (next == null) ValueLocalVersion.End.BlockEnd(sourceValueRange(block))
        else ValueLocalVersion.End.NextWrite(sourceValueRange(next), sourceValueRange(checkNotNull(next.right)))
    val admitted =
        when (
            val proof =
                ValueLocalVersion.Defined.fromCompiler(
                    execution = execution,
                    declaration = sourceValueRange(binding.declaration),
                    block = sourceValueRange(block),
                    definition = sourceValueRange(actualDefinition),
                    rhs = sourceValueRange(rhs),
                    end = end,
                )
        ) {
            is Refinement.Refined -> proof.value
            is Refinement.Rejected -> return mutableVersionRejected()
        }
    if (definition is KtNameReferenceExpression && !admitted.admitsRead(sourceValueRange(definition)))
        return mutableVersionRejected()
    return Refinement.Refined(NativeLocalValueVersion(binding.declaration, admitted))
}

private fun KaSession.classifyVersionElement(
    element: PsiElement,
    binding: KtNamedDeclaration,
    block: KtBlockExpression,
    function: KtNamedFunction,
    writes: MutableList<KtBinaryExpression>,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    if (element !is KtNameReferenceExpression || element.getReferencedName() != binding.name)
        return Refinement.Refined(Unit)
    val call = element.resolveCall() as? KaVariableAccessCall ?: return mutableVersionRejected()
    if (call.signature.symbol.psi !== binding) return Refinement.Refined(Unit)
    if (valueNativeOwnership(element, function) != NativeValueOwnership.ADMITTED)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (element.isCompoundVersionWrite()) return mutableVersionRejected()
    return when (val kind = call.kind) {
        is KaVariableAccessCall.Kind.Read -> Refinement.Refined(Unit)
        is KaVariableAccessCall.Kind.Write ->
            recordDirectWrite(element = element, rhs = kind.value, block = block, writes = writes)
    }
}

private fun recordDirectWrite(
    element: KtNameReferenceExpression,
    rhs: KtExpression?,
    block: KtBlockExpression,
    writes: MutableList<KtBinaryExpression>,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val assignment = element.parent as? KtBinaryExpression ?: return mutableVersionRejected()
    if (rhs == null || assignment.operationToken != KtTokens.EQ) return mutableVersionRejected()
    return if (assignment.left !== element || assignment.parent !== block || assignment.right !== rhs)
        mutableVersionRejected()
    else {
        writes += assignment
        Refinement.Refined(Unit)
    }
}

/** Control syntax cannot invalidate a definition by itself; only an actual write to this binding can. */
private fun KtNameReferenceExpression.isCompoundVersionWrite(): Boolean {
    var operand: PsiElement = this
    while (operand.parent is KtParenthesizedExpression) operand = operand.parent
    return when (val operation = operand.parent) {
        is KtBinaryExpression ->
            operation.left === operand && operation.operationToken in KtTokens.AUGMENTED_ASSIGNMENTS
        is KtUnaryExpression ->
            operation.operationToken == KtTokens.PLUSPLUS || operation.operationToken == KtTokens.MINUSMINUS
        else -> false
    }
}

private fun KaSession.localVersionBinding(expression: KtExpression): NativeVersionBinding? =
    when (expression) {
        is KtProperty,
        is KtDestructuringDeclarationEntry -> versionBindingDeclaration(expression)
        is KtNameReferenceExpression -> {
            val call = expression.resolveCall() as? KaVariableAccessCall
            if (call?.kind is KaVariableAccessCall.Kind.Read) versionBindingDeclaration(call.signature.symbol.psi)
            else null
        }
        is KtBinaryExpression -> {
            val left = expression.left as? KtNameReferenceExpression
            val call = left?.resolveCall() as? KaVariableAccessCall
            if (
                expression.operationToken == KtTokens.EQ &&
                    (call?.kind as? KaVariableAccessCall.Kind.Write)?.value === expression.right
            )
                versionBindingDeclaration(call?.signature?.symbol?.psi)
            else null
        }
        else -> null
    }

private fun versionBindingDeclaration(declaration: PsiElement?): NativeVersionBinding? =
    when (declaration) {
        is KtProperty -> {
            val initializer = declaration.initializer
            if (!declaration.isLocal || !declaration.isVar || declaration.hasDelegate()) null
            else initializer?.let { NativeVersionBinding.Property(declaration, it) }
        }
        is KtDestructuringDeclarationEntry -> {
            val statement = declaration.parent as? KtDestructuringDeclaration
            if (statement == null || !statement.isVar || statement.initializer == null) null
            else if (declaration.name == "_") null else NativeVersionBinding.Component(declaration, statement)
        }
        else -> null
    }

internal fun nativeLocalVersionRead(
    element: PsiElement,
    binding: KtNamedDeclaration,
    observation: IntellijReadObservation,
): Boolean =
    element is KtNameReferenceExpression &&
        observation.observedAnalyze(element) {
            val call = element.resolveCall() as? KaVariableAccessCall
            call?.signature?.symbol?.psi === binding && call.kind is KaVariableAccessCall.Kind.Read
        }

private fun mutableVersionRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
