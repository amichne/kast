@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Only native observations; scan decisions and detached progress belong to LocalBindingReferenceScan. */
internal class IntellijLocalBindingReads(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val observation: IntellijReadObservation,
    private val started: Long,
    private val onWork: (RelationWorkCount) -> Unit,
) {
    private val captureAllowance = IntellijRelationAllowance(started) { System.nanoTime() }
    private var scannedWork = 1L

    fun read(element: PsiElement): ValueFlowRead {
        if (request.source.localVersion is ValueLocalVersion.Defined) return readVersion(element)
        when (val admission = admitComponents(element)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unsupportedBindingRead(admission.failure, element)
        }
        return when (element) {
            is KtFunctionLiteral -> readImplicitParameter(element)
            is KtNamedDeclaration ->
                readReferences(
                    binding = element,
                    enumerate = { visit ->
                        observation.forEachReference(element, LocalSearchScope(owner), process = visit)
                    },
                    confirm = { confirmLocalBindingReference(it, element, observation) },
                )
            else -> staleBindingRead()
        }
    }

    private fun readImplicitParameter(literal: KtFunctionLiteral): ValueFlowRead =
        NativeImplicitLambdaReads(
                request = request,
                scope = scope,
                projection = projection,
                observation = observation,
                started = started,
            )
            .read(
                literal = literal,
                onWork = { work ->
                    scannedWork = work.value - captureAllowance.examined
                    onWork(work)
                },
                additionalWork = { captureAllowance.examined },
                target = ::branchReadTarget,
            )
            .also(observation::recordValueRead)

    private fun readVersion(element: PsiElement): ValueFlowRead {
        scannedWork = 2L
        val restored = nativeLocalValueVersion(element, request.source.executionOwner, observation, ::permitComponents)
        scannedWork = 1L
        onWork(bindingWork())
        val version =
            when (restored) {
                is Refinement.Rejected -> return unsupportedBindingRead(restored.failure, element)
                is Refinement.Refined -> restored.value
            }
        if (version.proof != request.source.localVersion) return staleBindingRead()
        val binding = version.binding
        return when (val components = admitComponents(binding)) {
            is Refinement.Rejected -> unsupportedBindingRead(components.failure, binding)
            is Refinement.Refined ->
                readReferences(
                    binding = binding,
                    enumerate = { visit ->
                        observation.forEachReference(binding, LocalSearchScope(owner), process = visit)
                    },
                    confirm = { confirmLocalBindingReference(it, binding, observation) },
                )
        }
    }

    private fun admitComponents(element: PsiElement): Refinement<Unit, ValueFlowUnsupportedCause> {
        if (element !is KtDestructuringDeclarationEntry) return Refinement.Refined(Unit)
        // Reserve authority and the scan's role-admission unit before proving every sibling.
        scannedWork = 2L
        val admission =
            NativeValueCallSyntax.Component(element)
                .admit(
                    enclosing = request.source.enclosing,
                    scope = scope,
                    projection = projection,
                    permit = ::permitComponents,
                )
        scannedWork = 1L
        onWork(bindingWork())
        return when (admission) {
            is Refinement.Refined -> Refinement.Refined(Unit)
            is Refinement.Rejected -> admission
        }
    }

    private fun permitComponents(): Refinement<Unit, ValueFlowUnsupportedCause> {
        ProgressManager.checkCanceled()
        return when (admitCaptureWork()) {
            CallbackWorkAdmission.READY -> Refinement.Refined(Unit)
            CallbackWorkAdmission.WORK_LIMIT_REACHED ->
                Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
            CallbackWorkAdmission.TIME_LIMIT_REACHED ->
                Refinement.Rejected(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
        }
    }

    private fun bindingWork(): RelationWorkCount =
        when (val count = RelationWorkCount.parse(1L + captureAllowance.examined)) {
            is Refinement.Refined -> count.value
            is Refinement.Rejected -> error("Binding admission work is nonnegative")
        }

    private fun unsupportedBindingRead(cause: ValueFlowUnsupportedCause, element: PsiElement): ValueFlowRead =
        when (
            val step =
                ValueFlowStep.fromCompiler(
                    source = request.source,
                    transfers = emptyList(),
                    obligations = listOf(nativeValueObligation(request.source, cause, element)),
                    terminal = ValueFlowTerminal.Unresolved,
                    domain = scope.request,
                    examinedWorkUnits = bindingWork(),
                )
        ) {
            is Refinement.Refined -> {
                observation.count(IntellijReadCounter.VALUE_FLOW_OBLIGATIONS)
                ValueFlowRead.Observed(step.value)
            }
            is Refinement.Rejected -> ValueFlowRead.ContractRejected(step.failure, bindingWork())
        }

    private fun readReferences(
        binding: KtNamedDeclaration,
        enumerate: ((PsiReference) -> Boolean) -> Unit,
        confirm: (KtReference) -> LocalBindingReferenceConfirmation,
    ): ValueFlowRead =
        LocalBindingReferenceScan<PsiReference>(
                request,
                scope.request,
                enumerate,
                { reference ->
                    val elementRange = nativeRange(reference.element)
                    val referenceRange = reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)
                    LocalReferenceKey(
                        (elementRange as Refinement.Refined).value,
                        (ExactDeclarationTextRange.parse(referenceRange.startOffset, referenceRange.endOffset)
                                as Refinement.Refined)
                            .value,
                        (LocalReferenceKind.fromBoundary(reference.javaClass.name) as Refinement.Refined).value,
                    )
                },
                { reference ->
                    val native = reference as? KtReference
                    if (native == null)
                        LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
                    else {
                        when (confirm(native)) {
                            LocalBindingReferenceConfirmation.UNRESOLVED ->
                                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
                            LocalBindingReferenceConfirmation.OTHER_BINDING -> LocalReferenceResolution.OtherBinding
                            LocalBindingReferenceConfirmation.EXACT_BINDING ->
                                admittedReadTarget(native.element, binding)
                        }
                    }
                },
                { ProgressManager.checkCanceled() },
                { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) },
                { work ->
                    scannedWork = work.value - captureAllowance.examined
                    onWork(work)
                },
                { captureAllowance.examined },
            )
            .read()
            .also(observation::recordValueRead)

    private fun admittedReadTarget(element: PsiElement, binding: KtNamedDeclaration): LocalReferenceResolution {
        val version = request.source.localVersion
        if (version is ValueLocalVersion.Defined) {
            if (!version.admitsRead(sourceValueRange(element))) return LocalReferenceResolution.OtherVersion
            if (!nativeLocalVersionRead(element, binding, observation))
                return LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
        val expression =
            element as? KtExpression
                ?: return LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        return when (
            val admission =
                nativeValueBranchAdmission(
                    source = request.source,
                    expression = expression,
                    binding = binding,
                    scope = scope,
                    projection = projection,
                    workAllowance = request.budget.resources.workAllowance,
                )
        ) {
            NativeValueBranchAdmission.ADMITTED,
            NativeValueBranchAdmission.NOT_APPLICABLE,
            NativeValueBranchAdmission.UNPROVEN -> branchReadTarget(expression, admission)
            NativeValueBranchAdmission.EXCLUDED -> LocalReferenceResolution.ExcludedBranch
            NativeValueBranchAdmission.WORK_LIMIT_REACHED ->
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        }
    }

    private fun branchReadTarget(
        expression: KtExpression,
        admission: NativeValueBranchAdmission,
    ): LocalReferenceResolution =
        if (request.source.localVersion is ValueLocalVersion.Defined) {
            when (
                val conditions =
                    nativeLocalReadConditions(
                        request.source,
                        expression,
                        admission,
                        observation,
                        request.budget.resources.workAllowance,
                    )
            ) {
                is Refinement.Refined ->
                    nativeLocalReadTarget(
                        element = expression,
                        source = request.source,
                        execution = request.source.executionOwner,
                        scope = scope,
                        conditions = conditions.value,
                    )
                is Refinement.Rejected -> LocalReferenceResolution.Unsupported(conditions.failure)
            }
        } else if (admission == NativeValueBranchAdmission.UNPROVEN)
            LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        else localReadTarget(expression)

    private fun localReadTarget(element: PsiElement): LocalReferenceResolution {
        val execution =
            when (valueNativeOwnership(element, owner)) {
                NativeValueOwnership.NESTED ->
                    when (
                        val captured =
                            nativeValueCaptureOwner(
                                element = element,
                                host = owner,
                                enclosing = request.source.enclosing,
                                scope = scope,
                                projection = projection,
                                admitWork = ::admitCaptureWork,
                                expiry = {
                                    if (captureAllowance.elapsedLimitReached(request.budget.resources))
                                        CallbackExpiryAdmission.EXPIRED
                                    else CallbackExpiryAdmission.CURRENT
                                },
                            )
                    ) {
                        is Refinement.Refined -> {
                            if (captured.value.owner.context !is ValueExecutionContext.Callback)
                                return LocalReferenceResolution.Unresolved(
                                    nativeValueObligation(
                                        request.source,
                                        ValueFlowUnsupportedCause.NESTED_EXECUTION,
                                        element,
                                    )
                                )
                            captured.value.owner
                        }
                        is Refinement.Rejected ->
                            return LocalReferenceResolution.Unresolved(
                                nativeValueObligation(request.source, captured.failure, element)
                            )
                    }
                NativeValueOwnership.UNAVAILABLE ->
                    return LocalReferenceResolution.Unresolved(
                        nativeValueObligation(request.source, ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
                    )
                NativeValueOwnership.ADMITTED -> request.source.executionOwner
            }
        return nativeLocalReadTarget(element = element, source = request.source, execution = execution, scope = scope)
    }

    private fun admitCaptureWork(): CallbackWorkAdmission =
        when {
            captureAllowance.elapsedLimitReached(request.budget.resources) -> CallbackWorkAdmission.TIME_LIMIT_REACHED
            !request.budget.resources.hasWorkCapacity(scannedWork + captureAllowance.examined) ->
                CallbackWorkAdmission.WORK_LIMIT_REACHED
            else -> {
                captureAllowance.examine()
                CallbackWorkAdmission.READY
            }
        }
}

private fun nativeLocalReadTarget(
    element: PsiElement,
    source: ValueSite,
    execution: ValueExecutionOwner,
    scope: CompiledRelationScope,
    conditions: ValueConditions = source.conditions,
): LocalReferenceResolution {
    if (!scope.nativeScope.contains(element.containingFile.virtualFile))
        return LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
    val range =
        when (val value = nativeRange(element)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return LocalReferenceResolution.Unsupported(value.failure)
        }
    return when (
        val target =
            ValueSite.fromCompiler(
                enclosing = source.enclosing,
                range = range,
                role = ValueRole.LocalRead,
                executionOwner = execution,
                valuePath = source.valuePath,
                callStack = source.callStack,
                conditions = conditions,
            )
    ) {
        is Refinement.Refined -> LocalReferenceResolution.Transfer(target.value)
        is Refinement.Rejected -> LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
}

private fun IntellijReadObservation.recordValueRead(read: ValueFlowRead) {
    val step =
        when (read) {
            is ValueFlowRead.Observed -> read.step
            is ValueFlowRead.Suspended -> read.step
            is ValueFlowRead.Rejected,
            is ValueFlowRead.ContractRejected -> null
        }
    step?.transfers?.forEach { count(IntellijReadCounter.VALUE_FLOW_TRANSFERS) }
    step?.obligations?.forEach { count(IntellijReadCounter.VALUE_FLOW_OBLIGATIONS) }
}

private fun nativeRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
    when (val range = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
        is Refinement.Refined -> range
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

private fun staleBindingRead(): ValueFlowRead =
    when (val work = RelationWorkCount.parse(1L)) {
        is Refinement.Refined -> ValueFlowRead.Rejected(ValueFlowRejection.STALE_SITE, work.value)
        is Refinement.Rejected -> error("Binding authority admission has one work unit")
    }
