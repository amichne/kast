@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

/** All live elements and K2 values remain inside the caller's one native read action. */
internal class IntellijValueFlowNative(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val observation: IntellijReadObservation,
    private val started: Long,
    private val onWork: (RelationWorkCount) -> Unit,
) {
    private val retained =
        NativeValueFlowTransfers(request = request, owner = owner, scope = scope, observation = observation)
    private val edges
        get() = retained.edges

    private val obligations
        get() = retained.obligations

    private val allowance = IntellijRelationAllowance(started) { System.nanoTime() }.apply { examine() }
    private val examined: Long
        get() = allowance.examined

    fun read(element: PsiElement): ValueFlowRead {
        val compiledFormal =
            request.source.role is ValueRole.Formal && (element.containingFile as? KtFile)?.isCompiled == true
        if (!compiledFormal && element.isNativeReferenceBinding(request.source.role))
            return IntellijLocalBindingReads(request, owner, scope, observation, started, onWork).read(element)

        val base = ValueFlowStep.detachedByteCount(request.source, scope.request, emptyList(), emptyList()).value
        val reserved = reservedValueObligationBytes(request.source)
        if (base > request.budget.returnedBytes.value || reserved > request.budget.returnedBytes.value - base) {

            return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, nativeWorkCount())
        }
        when (val read = readPermittedRole(element, compiledFormal)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return ValueFlowRead.Rejected(read.failure, nativeWorkCount())
        }
        val terminal =
            if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted else ValueFlowTerminal.Unresolved
        return when (
            val step =
                ValueFlowStep.fromCompiler(
                    request.source,
                    edges,
                    obligations,
                    terminal,
                    scope.request,
                    nativeWorkCount(),
                )
        ) {
            is Refinement.Refined -> ValueFlowRead.Observed(step.value)
            is Refinement.Rejected -> ValueFlowRead.ContractRejected(step.failure, nativeWorkCount())
        }
    }

    private fun readPermittedRole(element: PsiElement, compiledFormal: Boolean): Refinement<Unit, ValueFlowRejection> {
        if (permitted() is Refinement.Rejected) return Refinement.Refined(Unit)
        if (!compiledFormal) return readRole(element)
        unresolved(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        return Refinement.Refined(Unit)
    }

    private fun readRole(element: PsiElement): Refinement<Unit, ValueFlowRejection> {
        when (request.source.role) {
            ValueRole.LocalBinding -> unresolved(element.nativeLocalBindingCause())
            is ValueRole.Argument -> return argumentReturn()
            is ValueRole.Formal -> receiverFields(element)
            is ValueRole.Receiver -> retained.adopt(nativeValueSourceReceiver(request.source, scope, projection))
            ValueRole.Return -> return sourceReturn()
            ValueRole.PropertyAssignment -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
            ValueRole.ExpressionResult,
            ValueRole.LocalRead -> {
                val expression =
                    element as? KtExpression
                        ?: run {
                            unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                            return Refinement.Refined(Unit)
                        }
                transferExpression(expression)
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun argumentReturn(): Refinement<Unit, ValueFlowRejection> {
        val result =
            NativeValueArgumentFlow(
                    request = request,
                    scope = scope,
                    projection = projection,
                    allowance = allowance,
                    retained = retained,
                    permit = { permitted() is Refinement.Refined },
                )
                .read()
        onWork(nativeWorkCount())
        return result
    }

    private fun sourceReturn(): Refinement<Unit, ValueFlowRejection> {
        val read =
            NativeValueReturnFlow(
                    owner = owner,
                    returns =
                        NativeValueSourceReturns(
                            request = request,
                            scope = scope,
                            projection = projection,
                            allowance = allowance,
                        ),
                    retained = retained,
                    permit = { permitted() is Refinement.Refined },
                )
                .read()
        onWork(nativeWorkCount())
        return read
    }

    private fun receiverFields(element: PsiElement) {
        val function =
            element as? KtNamedFunction
                ?: run {
                    unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                    return
                }
        val exhausted =
            NativeValueReceiverReads(request.source, scope, projection)
                .forEachRead(
                    function = function,
                    admitTraversal = { sourceScopeAllowance() == IntellijRelationProviderEnumerationAdmission.READY },
                    permit = { permitted() is Refinement.Refined },
                    consume = retained::adopt,
                )
        if (!exhausted && obligations.isEmpty()) unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun sourceScopeAllowance(): IntellijRelationProviderEnumerationAdmission =
        nativeSourceScopeAllowance(request, allowance, ::unresolved)

    private fun nativeWorkCount(): RelationWorkCount =
        when (val count = RelationWorkCount.parse(examined)) {
            is Refinement.Refined -> count.value
            is Refinement.Rejected -> error("Native work counter cannot be negative")
        }

    private fun permitted(): Refinement<Unit, ValueFlowUnsupportedCause> {
        ProgressManager.checkCanceled()
        val grant = request.budget.resources
        return when {
            examined >= grant.workUnitLimit.value -> stopped(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
            retained.resultCount >= grant.resultLimit.value -> stopped(ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED)
            allowance.elapsedLimitReached(grant) -> stopped(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
            else -> {
                allowance.examine()
                onWork(nativeWorkCount())
                Refinement.Refined(Unit)
            }
        }
    }

    private fun stopped(cause: ValueFlowUnsupportedCause): Refinement.Rejected<ValueFlowUnsupportedCause> {
        unresolved(cause)
        return Refinement.Rejected(cause)
    }

    private fun transferExpression(expression: KtExpression) {
        when (permitted()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return
        }
        when (val parent = expression.parent) {
            is KtParenthesizedExpression -> transferExpression(parent)
            is KtProperty -> propertyDestination(expression, parent)
            is KtValueArgument -> argument(expression, parent)
            is KtQualifiedExpression -> receiver(expression, parent)
            is KtReturnExpression -> returnDestination(expression, parent)
            is KtThrowExpression -> {
                observation.count(IntellijReadCounter.VALUE_FLOW_ABRUPT_REJECTIONS)
                unresolved(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
            }
            is KtNamedFunction -> functionDestination(expression, parent)
            is KtContainerNodeForControlStructureBody -> ifDestination(expression, parent)
            is KtWhenEntry -> {
                val branch = parent.parent as? KtWhenExpression
                if (branch != null && parent.expression === expression) branchResult(expression, branch)
                else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            }
            is KtBlockExpression -> blockDestination(expression, parent)
            else -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun returnDestination(expression: KtExpression, returned: KtReturnExpression) {
        if (returnCrossesFinally(returned, owner)) {
            observation.count(IntellijReadCounter.VALUE_FLOW_FINALLY_REJECTIONS)
            unresolved(ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED)
        } else if (owner is KtFunctionLiteral) unresolved(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        else emit(expression, ValueRole.Return, ValueTransferKind.RETURN)
    }

    private fun blockDestination(expression: KtExpression, block: KtBlockExpression) {
        when (val position = tryBranchResultPosition(expression)) {
            is TryBranchResultPosition.Result -> tryBranchResult(expression, position)
            TryBranchResultPosition.Discarded -> Unit
            TryBranchResultPosition.Unavailable ->
                if (owner is KtFunctionLiteral && block === owner.bodyExpression)
                    unresolved(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                else if (block.parent !is KtNamedFunction) unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun propertyDestination(expression: KtExpression, property: KtProperty) {
        if (property.initializer !== expression) {
            unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        if (property.isLocal) emit(property, ValueRole.LocalBinding, ValueTransferKind.LOCAL_BINDING)
        else emit(property, ValueRole.PropertyAssignment, ValueTransferKind.PROPERTY_ASSIGNMENT)
    }

    private fun functionDestination(expression: KtExpression, function: KtNamedFunction) {
        if (function.bodyExpression === expression) emit(expression, ValueRole.Return, ValueTransferKind.RETURN)
        else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun ifDestination(expression: KtExpression, container: KtContainerNodeForControlStructureBody) {
        val branch = container.parent as? KtIfExpression
        if (branch != null && branch.condition !== expression) branchResult(expression, branch)
        else unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun branchResult(expression: KtExpression, branch: KtExpression) {
        when (val result = nativeBranchCarriesValue(expression, branch, observation)) {
            is Refinement.Refined -> emit(branch, ValueRole.ExpressionResult, ValueTransferKind.BRANCH_ALTERNATIVE)
            is Refinement.Rejected -> unresolved(result.failure)
        }
    }

    private fun tryBranchResult(expression: KtExpression, position: TryBranchResultPosition.Result) {
        when (val evidence = NativeTryBranchResult.admit(expression, position, observation)) {
            is Refinement.Refined -> {
                val before = edges.size
                emit(
                    position.enclosing,
                    ValueRole.ExpressionResult,
                    ValueTransferKind.BRANCH_ALTERNATIVE,
                    evidence.value,
                )
                if (edges.size > before) observation.count(IntellijReadCounter.VALUE_FLOW_BRANCH_RESULTS_ADMITTED)
            }
            is Refinement.Rejected -> unresolved(evidence.failure)
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
            is Refinement.Rejected -> unresolved(role.failure)
        }
    }

    private fun receiver(expression: KtExpression, qualified: KtQualifiedExpression) {
        if (qualified.selectorExpression !is org.jetbrains.kotlin.psi.KtCallElement) {
            when (val result = nativeValueFieldRead(request.source, qualified, scope, projection)) {
                is Refinement.Refined ->
                    when (val read = result.value) {
                        is NativeValueFieldRead.Transfer ->
                            retained.emitTarget(read.value.target, read.value.kind, read.value.evidence)
                        NativeValueFieldRead.DifferentField -> Unit
                    }
                is Refinement.Rejected -> unresolved(result.failure)
            }
            return
        }
        when (
            val role =
                nativeValueReceiver(
                    expression = expression,
                    qualified = qualified,
                    scope = scope,
                    projection = projection,
                    executionOwner = request.source.executionOwner,
                )
        ) {
            is Refinement.Refined -> emit(expression, role.value, ValueTransferKind.RECEIVER)
            is Refinement.Rejected -> unresolved(role.failure)
        }
    }

    private fun emit(
        element: PsiElement,
        role: ValueRole,
        kind: ValueTransferKind,
        evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
    ) = retained.emit(element = element, role = role, kind = kind, evidence = evidence)

    private fun unresolved(cause: ValueFlowUnsupportedCause) = retained.unresolved(cause)
}
