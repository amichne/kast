@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackAccessorKind
import io.github.amichne.kast.relation.contract.CallbackDeferredSupplier
import io.github.amichne.kast.relation.contract.CallbackDirectExecutionRoute
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor

/** The lexical declaration and its actual execution site remain separate compiler facts. */
internal class IntellijDeferredSupplierExecution(private val context: IntellijCallbackFlowContext) {
    fun read(
        element: PsiElement,
        occurrence: RelationOccurrence,
    ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
        when (val admitted = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        val site =
            generateSequence(element as PsiElement?) { it.parent }
                .firstOrNull {
                    it is KtPropertyAccessor ||
                        it is KtClassInitializer ||
                        it is KtFunction ||
                        (it is org.jetbrains.kotlin.psi.KtProperty && !it.isLocal)
                }
        return when (site) {
            is KtPropertyAccessor -> accessor(site, occurrence)
            is KtClassInitializer -> initializer(site, occurrence)
            is KtConstructor<*> -> declaration(element, occurrence)
            is KtFunction ->
                if (site is KtNamedFunction && site.name != null) declaration(element, occurrence)
                else invoked(site, occurrence)
            else -> declaration(element, occurrence)
        }
    }

    private fun declaration(
        element: PsiElement,
        occurrence: RelationOccurrence,
    ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
        val found = element.nearestDeclaration() as? ContainingDeclaration.Found ?: return rejected()
        val evidence = context.receiverDeclaration(found.declaration) ?: return rejected()
        return CallbackDeferredSupplier.fromCompiler(context.scope.request.subject.lease.identity, occurrence, evidence)
    }

    private fun accessor(
        site: KtPropertyAccessor,
        occurrence: RelationOccurrence,
    ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
        val property = site.property
        if (!context.observation.observedAnalyze(site) { site.symbol.containingDeclaration?.psi === property })
            return rejected()
        val evidence = context.receiverDeclaration(property) ?: return rejected()
        val region = context.occurrence(site) ?: return rejected()
        return CallbackDeferredSupplier.accessor(
            context.scope.request.subject.lease.identity,
            occurrence,
            evidence,
            if (site.isGetter) CallbackAccessorKind.GETTER else CallbackAccessorKind.SETTER,
            region,
        )
    }

    private fun initializer(
        site: KtClassInitializer,
        occurrence: RelationOccurrence,
    ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> {
        val klass =
            com.intellij.psi.util.PsiTreeUtil.getParentOfType(site, KtClassOrObject::class.java) ?: return rejected()
        if (!context.observation.observedAnalyze(site) { site.symbol.containingDeclaration?.psi === klass })
            return rejected()
        val evidence = context.receiverDeclaration(klass) ?: return rejected()
        val region = context.occurrence(site) ?: return rejected()
        return CallbackDeferredSupplier.initializer(
            context.scope.request.subject.lease.identity,
            occurrence,
            evidence,
            region,
        )
    }

    private fun invoked(
        site: KtFunction,
        occurrence: RelationOccurrence,
    ): Refinement<CallbackDeferredSupplier, CallbackInvocationFlowCause> =
        when (val route = route(site)) {
            is Refinement.Refined -> CallbackDeferredSupplier.invoked(occurrence, route.value)
            is Refinement.Rejected -> route
        }

    private fun route(site: KtFunction): Refinement<CallbackDirectExecutionRoute, CallbackInvocationFlowCause> {
        val supply =
            classifyCallbackFunctionSupply(site) as? IntellijCallbackLambdaSupply.Invocation
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val prepared =
            when (val read = IntellijCallbackDeclaredSupplyReader(context).prepareDirect(supply)) {
                is CallbackBindingPreparation.Direct -> read
                is CallbackBindingPreparation.Unavailable -> return Refinement.Rejected(read.cause)
                is CallbackBindingPreparation.ContractRejected -> return rejected()
                is CallbackBindingPreparation.Prepared,
                is CallbackBindingPreparation.DeferredDependency,
                is CallbackBindingPreparation.DependencyContract -> return rejected()
            }
        val body = context.anonymous(site) ?: return rejected()
        return when (prepared.binding.owner) {
            is RelationCallableBody.Named -> CallbackDirectExecutionRoute.start(prepared.binding, body)
            is RelationCallableBody.Anonymous -> {
                val owner =
                    (supply.call.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunction
                        ?: return rejected()
                when (val parent = route(owner)) {
                    is Refinement.Refined -> parent.value.append(prepared.binding, body)
                    is Refinement.Rejected -> parent
                }
            }
        }
    }

    private fun rejected(): Refinement.Rejected<CallbackInvocationFlowCause> =
        Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
}
