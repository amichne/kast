@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowSuspensionCause
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** Only this compiler-owned implicit formal uses body enumeration; declared bindings keep their indexed scan. */
internal class NativeImplicitLambdaReads(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val observation: IntellijReadObservation,
    private val started: Long,
) {
    private val allowance = IntellijRelationAllowance(started) { System.nanoTime() }
    private var enumerationSuspension: ValueFlowSuspensionCause? = null

    fun read(
        literal: KtFunctionLiteral,
        onWork: (RelationWorkCount) -> Unit,
        additionalWork: () -> Long,
        target: (KtExpression, NativeValueBranchAdmission) -> LocalReferenceResolution,
    ): ValueFlowRead {
        val formal = restoreFormal(literal) ?: return staleImplicitBindingRead()
        val body = literal.bodyExpression ?: return staleImplicitBindingRead()
        enumerationSuspension = null
        return LocalBindingReferenceScan<KtNameReferenceExpression>(
                request = request,
                domain = scope.request,
                enumerate = { visit -> enumerate(body, formal, visit) },
                key = ::key,
                confirm = { expression ->
                    confirm(expression = expression, literal = literal, formal = formal, target = target)
                },
                checkCanceled = { ProgressManager.checkCanceled() },
                elapsedMillis = { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) },
                onWork = onWork,
                additionalWork = additionalWork,
                enumerationSuspension = { enumerationSuspension },
            )
            .read()
    }

    private fun restoreFormal(literal: KtFunctionLiteral): ValueFormalParameter? {
        val formal =
            ((request.source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Parameter)?.parameter
                ?: return null
        return observation.observedAnalyze(literal) {
            if (nativeImplicitLambdaParameter(literal, formal.position) == null) return@observedAnalyze null
            val admitted = nativeCallbackInputParameter(literal, request.source.executionOwner, formal.position)
            formal.takeIf { admitted is Refinement.Refined && admitted.value == formal }
        }
    }

    private fun enumerate(
        body: KtExpression,
        formal: ValueFormalParameter,
        visit: (KtNameReferenceExpression) -> Boolean,
    ) {
        observeCallbackBodyScan(observation) {
            PsiTreeUtil.processElements(body) { element ->
                ProgressManager.checkCanceled()
                if (allowance.elapsedLimitReached(request.budget.resources)) {
                    enumerationSuspension = ValueFlowSuspensionCause.TIME_LIMIT_REACHED
                    false
                } else if (element is KtNameReferenceExpression && element.getReferencedName() == formal.name.value) {
                    visit(element)
                } else true
            }
        }
    }

    private fun key(expression: KtNameReferenceExpression): LocalReferenceKey {
        val range = sourceValueRange(expression)
        return LocalReferenceKey(
            range,
            range,
            (LocalReferenceKind.fromBoundary(expression.javaClass.name) as Refinement.Refined).value,
        )
    }

    private fun confirm(
        expression: KtNameReferenceExpression,
        literal: KtFunctionLiteral,
        formal: ValueFormalParameter,
        target: (KtExpression, NativeValueBranchAdmission) -> LocalReferenceResolution,
    ): LocalReferenceResolution =
        when (
            confirmImplicitLambdaParameterReference(
                expression = expression,
                literal = literal,
                position = formal.position,
                observation = observation,
            )
        ) {
            LocalBindingReferenceConfirmation.UNRESOLVED ->
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
            LocalBindingReferenceConfirmation.OTHER_BINDING -> LocalReferenceResolution.OtherBinding
            LocalBindingReferenceConfirmation.EXACT_BINDING ->
                admittedTarget(expression = expression, literal = literal, formal = formal, target = target)
        }

    private fun admittedTarget(
        expression: KtNameReferenceExpression,
        literal: KtFunctionLiteral,
        formal: ValueFormalParameter,
        target: (KtExpression, NativeValueBranchAdmission) -> LocalReferenceResolution,
    ): LocalReferenceResolution {
        val admission =
            observation.observedAnalyze(expression) {
                val expected =
                    nativeImplicitLambdaParameter(literal, formal.position)
                        ?: return@observedAnalyze NativeValueBranchAdmission.UNPROVEN
                branchAdmission(
                    source = request.source,
                    expression = expression,
                    expected = expected,
                    scope = scope,
                    projection = projection,
                    workAllowance = request.budget.resources.workAllowance,
                )
            }
        return when (admission) {
            NativeValueBranchAdmission.ADMITTED,
            NativeValueBranchAdmission.NOT_APPLICABLE,
            NativeValueBranchAdmission.UNPROVEN -> target(expression, admission)
            NativeValueBranchAdmission.EXCLUDED -> LocalReferenceResolution.ExcludedBranch
            NativeValueBranchAdmission.WORK_LIMIT_REACHED ->
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        }
    }
}

private fun staleImplicitBindingRead(): ValueFlowRead =
    when (val work = RelationWorkCount.parse(1L)) {
        is Refinement.Refined -> ValueFlowRead.Rejected(ValueFlowRejection.STALE_SITE, work.value)
        is Refinement.Rejected -> error("Binding authority admission has one work unit")
    }
