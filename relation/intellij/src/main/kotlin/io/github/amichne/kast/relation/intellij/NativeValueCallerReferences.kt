package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import org.jetbrains.kotlin.psi.KtImportDirective

/** The existing bounded locator inventory separates native reference callbacks from K2 caller refinement. */
internal class NativeValueCallerReferences(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
) {
    fun read(
        declaration: PsiNamedElement,
        callable: RelationEndpoint,
        process: (PsiReference) -> Boolean,
        consume: (NativeValueReturnEffect) -> Boolean,
    ): Boolean {
        val question =
            RelationRequest.start(
                subject = callable,
                meaning = RelationMeaning.References,
                budget = request.budget,
                boundary = request.boundary,
            )
        val selected =
            when (val value = scope.forRequest(question)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return consume(NativeValueReturnEffect.Rejected(value.failure))
            }
        val collector =
            IntellijRelationCollector(request = question, observation = projection.observation, allowance = allowance)
        val termination =
            references(
                question = question,
                selected = selected,
                declaration = declaration,
                collector = collector,
                process = process,
            )
        return when (val compiled = collector.finish(termination)) {
            is RelationCompilation.Complete -> true
            is RelationCompilation.Qualified -> {
                consume(NativeValueReturnEffect.InventoryIncomplete(compiled.coverage.limitations))
                false
            }
            is RelationCompilation.Rejected -> consume(NativeValueReturnEffect.Rejected(compiled.reason))
        }
    }

    private fun references(
        question: RelationRequest,
        selected: CompiledRelationScope,
        declaration: PsiNamedElement,
        collector: IntellijRelationCollector,
        process: (PsiReference) -> Boolean,
    ): IntellijRelationTermination =
        IntellijRetainedRelationRead(
                project = declaration.project,
                scope = selected,
                projection = projection,
                limits = ReadLimits.Default,
                observation = projection.observation,
                cancellationCheck = ProgressManager::checkCanceled,
                admitsReference = ::valueCallerReference,
            )
            .references(
                request = question,
                subject = declaration,
                collector = collector,
                process = { reference ->
                    val continued = process(reference)
                    val dismissed = collector.dismissProviderItem()
                    continued && dismissed
                },
                termination = ::callerReferenceTermination,
            )
}

private fun valueCallerReference(reference: PsiReference): Boolean =
    PsiTreeUtil.getParentOfType(reference.element, KtImportDirective::class.java, false) == null

private fun callerReferenceTermination(termination: ProviderTermination): IntellijRelationTermination =
    when (termination) {
        ProviderTermination.TERMINAL -> IntellijRelationTermination.Terminal
        ProviderTermination.HALTED ->
            IntellijRelationTermination.TerminalIncomplete(setOf(RelationLimitation.PROVIDER_INCOMPLETE))
    }
