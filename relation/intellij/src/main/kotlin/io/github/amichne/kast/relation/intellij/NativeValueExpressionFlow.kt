@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtContainerNode
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Expression destinations preserve the admitted value, owner, and matched call stack. */
internal class NativeValueExpressionFlow(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val observation: IntellijReadObservation,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    private val typeOperations =
        NativeValueTypeOperations(observation, request.budget.resources.workAllowance, retained)

    fun readExpression(expression: KtExpression) = typeOperations.readResult(expression, permit, ::transferExpression)

    private fun transferExpression(expression: KtExpression) {
        when (permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return
        }
        if (expression is KtDestructuringDeclarationEntry) {
            NativeValueDestructuring(request.source, scope, projection)
                .bind(
                    entry = expression,
                    permit = permit,
                    consume = { emit(expression, ValueRole.LocalBinding, ValueTransferKind.LOCAL_BINDING) },
                    unsupported = { retained.unresolvedAt(it, expression) },
                )
            return
        }
        when (val parent = expression.parent) {
            is KtParenthesizedExpression -> transferExpression(parent)
            is KtBinaryExpressionWithTypeRHS -> typeOperations.readOperand(expression, parent, ::transferExpression)
            is KtIsExpression -> Unit
            is KtWhenExpression ->
                if (parent.subjectExpression !== expression)
                    retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
            is KtProperty -> propertyDestination(expression, parent)
            is KtDestructuringDeclaration -> destructuringDestination(expression, parent)
            is KtValueArgument -> argument(expression, parent)
            is KtQualifiedExpression -> receiver(expression, parent)
            is KtReturnExpression -> returnDestination(expression, parent)
            is KtThrowExpression -> {
                observation.count(IntellijReadCounter.VALUE_FLOW_ABRUPT_REJECTIONS)
                retained.unresolvedAt(ValueFlowUnsupportedCause.ABRUPT_COMPLETION, parent)
            }
            is KtNamedFunction -> functionDestination(expression, parent)
            is KtContainerNodeForControlStructureBody -> ifDestination(expression, parent)
            is KtContainerNode ->
                if ((parent.parent as? KtIfExpression)?.condition !== expression)
                    retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
            is KtWhenEntry -> {
                val branch = parent.parent as? KtWhenExpression
                if (branch != null && parent.expression === expression) branchResult(expression, branch)
                else retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
            }
            is KtBlockExpression -> blockDestination(expression, parent)
            else -> retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, parent)
        }
    }

    private fun destructuringDestination(expression: KtExpression, declaration: KtDestructuringDeclaration) =
        NativeValueDestructuring(request.source, scope, projection)
            .read(
                expression = expression,
                declaration = declaration,
                permit = permit,
                consume = { retained.emitTarget(it.target, it.kind, it.evidence) },
                unsupported = { retained.unresolvedAt(it, declaration) },
            )

    private fun returnDestination(expression: KtExpression, returned: KtReturnExpression) {
        if (returnCrossesFinally(returned, owner)) {
            observation.count(IntellijReadCounter.VALUE_FLOW_FINALLY_REJECTIONS)
            retained.unresolvedAt(ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED, returned)
        } else if (owner is KtFunctionLiteral)
            retained.unresolvedAt(ValueFlowUnsupportedCause.NESTED_EXECUTION, returned)
        else emit(expression, ValueRole.Return, ValueTransferKind.RETURN)
    }

    private fun blockDestination(expression: KtExpression, block: KtBlockExpression) {
        when (val position = tryBranchResultPosition(expression)) {
            is TryBranchResultPosition.Result -> tryBranchResult(expression, position)
            TryBranchResultPosition.Discarded -> Unit
            TryBranchResultPosition.Unavailable ->
                if (owner is KtFunctionLiteral && block === owner.bodyExpression) {
                    if (block.statements.lastOrNull() === expression)
                        request
                            .callbackReturnSemantics(expression, owner, observation)
                            .onCallbackReturn(
                                { emit(expression, ValueRole.Return, ValueTransferKind.RETURN) },
                                ::unresolved,
                            )
                } else if (block.parent !is KtNamedFunction)
                    retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
        }
    }

    private fun propertyDestination(expression: KtExpression, property: KtProperty) {
        if (property.initializer !== expression) {
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
            return
        }
        if (property.isLocal) emit(property, ValueRole.LocalBinding, ValueTransferKind.LOCAL_BINDING)
        else emit(property, ValueRole.PropertyAssignment, ValueTransferKind.PROPERTY_ASSIGNMENT)
    }

    private fun functionDestination(expression: KtExpression, function: KtNamedFunction) {
        if (function.bodyExpression === expression) emit(expression, ValueRole.Return, ValueTransferKind.RETURN)
        else retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
    }

    private fun ifDestination(expression: KtExpression, container: KtContainerNodeForControlStructureBody) {
        val branch = container.parent as? KtIfExpression
        if (branch != null && branch.condition !== expression) branchResult(expression, branch)
        else retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression)
    }

    private fun branchResult(expression: KtExpression, branch: KtExpression) {
        when (
            val result =
                nativeBranchCarriesValue(expression, branch, observation, request.budget.resources.workAllowance)
        ) {
            is Refinement.Refined -> emit(branch, ValueRole.ExpressionResult, ValueTransferKind.BRANCH_ALTERNATIVE)
            is Refinement.Rejected -> retained.unresolvedAt(result.failure, branch)
        }
    }

    private fun tryBranchResult(expression: KtExpression, position: TryBranchResultPosition.Result) {
        when (
            val evidence =
                NativeTryBranchResult.admit(expression, position, observation, request.budget.resources.workAllowance)
        ) {
            is Refinement.Refined -> {
                val before = retained.edges.size
                emit(
                    position.enclosing,
                    ValueRole.ExpressionResult,
                    ValueTransferKind.BRANCH_ALTERNATIVE,
                    evidence.value,
                )
                if (retained.edges.size > before)
                    observation.count(IntellijReadCounter.VALUE_FLOW_BRANCH_RESULTS_ADMITTED)
            }
            is Refinement.Rejected -> retained.unresolvedAt(evidence.failure, expression)
        }
    }

    private fun argument(expression: KtExpression, argument: KtValueArgument) {
        when (
            val role =
                nativeValueArgument(
                    expression = expression,
                    argument = argument,
                    scope = scope,
                    projection = projection,
                    executionOwner = request.source.executionOwner,
                )
        ) {
            is Refinement.Refined -> emit(expression, role.value, ValueTransferKind.ARGUMENT)
            is Refinement.Rejected -> retained.unresolvedAt(role.failure, argument)
        }
    }

    private fun receiver(expression: KtExpression, qualified: KtQualifiedExpression) =
        NativeValueQualifiedReceiver(
                source = request.source,
                scope = scope,
                projection = projection,
                retained = retained,
            )
            .read(expression, qualified)

    private fun emit(
        element: PsiElement,
        role: ValueRole,
        kind: ValueTransferKind,
        evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
    ) = retained.emit(element = element, role = role, kind = kind, evidence = evidence)

    private fun unresolved(cause: ValueFlowUnsupportedCause) = retained.unresolved(cause)
}
