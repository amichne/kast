@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtProperty

/** Only native observations; scan decisions and detached progress belong to LocalBindingReferenceScan. */
internal class IntellijLocalBindingReads(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val observation: IntellijReadObservation,
    private val started: Long,
    private val onWork: (RelationWorkCount) -> Unit,
) {
    fun read(property: KtProperty): ValueFlowRead =
        LocalBindingReferenceScan<PsiReference>(
                request,
                scope.request,
                { visit ->
                    ReferencesSearch.search(property, LocalSearchScope(owner)).forEach(Processor { visit(it) })
                },
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
                        val resolution = analyze(native.element) { native.resolveToSymbol()?.psi }
                        when {
                            resolution == null ->
                                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
                            resolution !== property -> LocalReferenceResolution.OtherBinding
                            else -> localReadTarget(native.element)
                        }
                    }
                },
                { ProgressManager.checkCanceled() },
                { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) },
                onWork,
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
        val cause =
            when (valueNativeOwnership(element, owner)) {
                NativeValueOwnership.NESTED -> ValueFlowUnsupportedCause.NESTED_EXECUTION
                NativeValueOwnership.UNAVAILABLE -> ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION
                NativeValueOwnership.ADMITTED -> null
            }
        if (cause != null) return LocalReferenceResolution.Unsupported(cause)
        if (!scope.nativeScope.contains(element.containingFile.virtualFile))
            return LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
        val range =
            when (val value = nativeRange(element)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return LocalReferenceResolution.Unsupported(value.failure)
            }
        return when (val target = ValueSite.fromCompiler(request.source.enclosing, range, ValueRole.LocalRead)) {
            is Refinement.Refined -> LocalReferenceResolution.Transfer(target.value)
            is Refinement.Rejected ->
                LocalReferenceResolution.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun nativeRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
        when (val range = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
            is Refinement.Refined -> range
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
}
