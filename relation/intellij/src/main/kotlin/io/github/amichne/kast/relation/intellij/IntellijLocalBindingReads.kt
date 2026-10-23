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
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

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

    fun read(element: PsiElement): ValueFlowRead =
        when (element) {
            is KtNamedDeclaration ->
                readReferences(
                    enumerate = { visit ->
                        observation.forEachReference(element, LocalSearchScope(owner), process = visit)
                    },
                    confirm = { confirmLocalBindingReference(it, element, observation) },
                )
            else -> staleBindingRead()
        }

    private fun readReferences(
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
                            LocalBindingReferenceConfirmation.EXACT_BINDING -> localReadTarget(native.element)
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
            .also { read ->
                val step =
                    when (read) {
                        is ValueFlowRead.Observed -> read.step
                        is ValueFlowRead.Suspended -> read.step
                        is ValueFlowRead.Rejected,
                        is ValueFlowRead.ContractRejected -> null
                    }
                step?.transfers?.forEach { observation.count(IntellijReadCounter.VALUE_FLOW_TRANSFERS) }
                step?.obligations?.forEach { observation.count(IntellijReadCounter.VALUE_FLOW_OBLIGATIONS) }
            }

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
                        is Refinement.Refined -> captured.value.owner
                        is Refinement.Rejected -> return LocalReferenceResolution.Unsupported(captured.failure)
                    }
                NativeValueOwnership.UNAVAILABLE ->
                    return LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                NativeValueOwnership.ADMITTED -> request.source.executionOwner
            }
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
                    enclosing = request.source.enclosing,
                    range = range,
                    role = ValueRole.LocalRead,
                    executionOwner = execution,
                    valuePath = request.source.valuePath,
                    callStack = request.source.callStack,
                )
        ) {
            is Refinement.Refined -> LocalReferenceResolution.Transfer(target.value)
            is Refinement.Rejected ->
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
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

    private fun nativeRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
        when (val range = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
            is Refinement.Refined -> range
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
}

private fun staleBindingRead(): ValueFlowRead =
    when (val work = RelationWorkCount.parse(1L)) {
        is Refinement.Refined -> ValueFlowRead.Rejected(ValueFlowRejection.STALE_SITE, work.value)
        is Refinement.Rejected -> error("Binding authority admission has one work unit")
    }

internal fun PsiElement.isNativeReferenceBinding(role: ValueRole): Boolean =
    when (role) {
        ValueRole.LocalBinding ->
            when (val binding = nativeLocalBindingMutability()) {
                is Refinement.Refined -> binding.value == NativeLocalBindingMutability.IMMUTABLE
                is Refinement.Rejected -> false
            }
        is ValueRole.Formal -> role.position is ValueFormalPosition.Parameter && this is KtParameter
        is ValueRole.Argument,
        is ValueRole.Receiver,
        ValueRole.ExpressionResult,
        ValueRole.LocalRead,
        ValueRole.Return,
        ValueRole.PropertyAssignment -> false
    }

internal enum class NativeLocalBindingMutability {
    IMMUTABLE,
    MUTABLE,
}

/** The same cheap declaration law governs admission and reference enumeration. */
internal fun PsiElement.nativeLocalBindingMutability():
    Refinement<NativeLocalBindingMutability, ValueFlowUnsupportedCause> {
    val mutable =
        when (this) {
            is KtProperty -> {
                if (!isLocal)
                    return Refinement.Rejected(
                        if (isVar) ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW
                        else ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY
                    )
                isVar
            }
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    return Refinement.Refined(
        if (mutable) NativeLocalBindingMutability.MUTABLE else NativeLocalBindingMutability.IMMUTABLE
    )
}

internal fun PsiElement.nativeLocalBindingCause(): ValueFlowUnsupportedCause =
    when (val binding = nativeLocalBindingMutability()) {
        is Refinement.Refined ->
            when (binding.value) {
                NativeLocalBindingMutability.MUTABLE -> ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW
                NativeLocalBindingMutability.IMMUTABLE ->
                    error("Immutable local binding must use the production reference scan")
            }
        is Refinement.Rejected -> binding.failure
    }

/** Shared K2 identity proof for local value reads; spelling never establishes a transfer. */
internal enum class LocalBindingReferenceConfirmation {
    EXACT_BINDING,
    OTHER_BINDING,
    UNRESOLVED,
}

internal fun confirmLocalBindingReference(
    reference: KtReference,
    property: KtNamedDeclaration,
    observation: IntellijReadObservation,
): LocalBindingReferenceConfirmation {
    return when (val resolution = resolveLocalValueReference(reference, observation)) {
        NativeLocalValueReferenceResolution.Unresolved -> LocalBindingReferenceConfirmation.UNRESOLVED
        is NativeLocalValueReferenceResolution.Resolved ->
            if (resolution.declaration === property) LocalBindingReferenceConfirmation.EXACT_BINDING
            else LocalBindingReferenceConfirmation.OTHER_BINDING
    }
}

internal sealed interface NativeLocalValueReferenceResolution {
    data class Resolved(val declaration: PsiElement) : NativeLocalValueReferenceResolution

    data object Unresolved : NativeLocalValueReferenceResolution
}

/** Shared native observation, including formal references; consumers retain their own ownership and role checks. */
internal fun resolveLocalValueReference(
    reference: KtReference,
    observation: IntellijReadObservation,
): NativeLocalValueReferenceResolution {
    val declaration =
        observation.observedAnalyze(reference.element) { reference.resolveToSymbol()?.psi }
            ?: return NativeLocalValueReferenceResolution.Unresolved
    return NativeLocalValueReferenceResolution.Resolved(declaration)
}
