package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationCompilation
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanResult
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanningFailure
import io.github.amichne.kast.change.intellij.HostedLiveAddDeclarationCompiler
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import java.nio.file.Path

/** Acquires the same complete planning evidence through the original project's internal read ports. */
internal suspend fun prepareHostedAddDeclaration(
    project: Project,
    context: HostedSemanticReadContext,
    request: ChangePlanRequest,
): Refinement<LiveAddDeclarationChangePlan, ChangePlanRejection> {
    val services = HostedSemanticServices(project, context)
    val intent =
        when (val value = request.intent) {
            is ChangeIntentDocument.AddDeclaration -> value
            is ChangeIntentDocument.AddFile,
            is ChangeIntentDocument.RenameSymbol,
            is ChangeIntentDocument.ReplaceDeclaration -> return rejected(ChangePlanRejection.UNSUPPORTED_HOSTED_INTENT)
        }
    val selector =
        when (val restored = restoreHostedChangeTarget(services, context, intent.exactTarget)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return restored
        }
    val file =
        when (val value = selector.file) {
            is SymbolDiscoveryFileIdentity.Workspace -> value
            is SymbolDiscoveryFileIdentity.External -> return rejected(ChangePlanRejection.EDITABLE_TARGET_REQUIRED)
        }
    val compiled =
        when (
            val result =
                HostedLiveAddDeclarationCompiler.compile(
                    project = project,
                    context = context,
                    selector = selector,
                    rawDeclaration = intent.declaration.value,
                )
        ) {
            is LiveAddDeclarationCompilation.Compiled -> result
            is LiveAddDeclarationCompilation.Rejected -> return rejected(ChangePlanRejection.INTENT_REJECTED)
        }
    val evidence =
        when (
            val observed =
                observeHostedPlanningEvidence(services = services, context = context, selector = selector, file = file)
        ) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return observed
        }
    return issueHostedPlan(
        LiveAddDeclarationPlanRequest(
            authority = context.authority,
            model = context.model,
            selector = selector,
            content = compiled.content,
            intent = compiled.intent,
            evidence = evidence,
        )
    )
}

private fun issueHostedPlan(
    request: LiveAddDeclarationPlanRequest
): Refinement<LiveAddDeclarationChangePlan, ChangePlanRejection> =
    when (val result = PureAddDeclarationPlanningService().plan(request)) {
        is LiveAddDeclarationPlanResult.Planned -> Refinement.Refined(result.plan)
        is LiveAddDeclarationPlanResult.Rejected ->
            rejected(
                when (result.failure) {
                    is LiveAddDeclarationPlanningFailure.Target -> ChangePlanRejection.EDITABLE_TARGET_REQUIRED
                    is LiveAddDeclarationPlanningFailure.Basis -> ChangePlanRejection.EXACT_SYMBOL_REQUIRED
                    is LiveAddDeclarationPlanningFailure.Evidence -> ChangePlanRejection.INTENT_REJECTED
                }
            )
    }

private suspend fun restoreHostedChangeTarget(
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    exactTarget: io.github.amichne.kast.protocol.contract.ProtocolText,
): Refinement<SymbolSelector, ChangePlanRejection> {
    val restored =
        when (val result = CanonicalQueryReferences(context.model).restoreExact(exactTarget, context.authority)) {
            is CanonicalSelectorDecoding.Decoded -> result.value
            is CanonicalSelectorDecoding.Rejected -> return rejected(ChangePlanRejection.EXACT_SYMBOL_REQUIRED)
        }
    val selector =
        when (val result = services.exact.describe(ExactSymbolRequest(restored))) {
            is SymbolDescriptionResult.Described -> result.description.selector
            is SymbolDescriptionResult.Rejected -> return rejected(ChangePlanRejection.EXACT_SYMBOL_REQUIRED)
        }
    return Refinement.Refined(selector)
}

private suspend fun observeHostedPlanningEvidence(
    services: HostedSemanticServices,
    context: HostedSemanticReadContext,
    selector: SymbolSelector,
    file: SymbolDiscoveryFileIdentity.Workspace,
): Refinement<AddDeclarationPlanningEvidenceInput, ChangePlanRejection> {
    val budgets = services.budgets
    val relations = services.relations
    val relation =
        relations.read(RelationRequest.start(selector, RelationMeaning.References, budgets.hostedRelationBudget))
    if (relation !is RelationReadResult.Complete) return rejected(ChangePlanRejection.RELATION_READ_REQUIRED)
    val traversalPlan =
        when (val result = TraversalPlan.start(selector, RelationMeaning.References, budgets.hostedTraversalBudget)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejected(ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE)
        }
    val traversal = traversalOperations(relations).run(traversalPlan)
    if (traversal !is TraversalResult.Complete) return rejected(ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE)
    val scope =
        when (val result = DiagnosticScope.fromCanonicalPaths(context.authority, listOf(Path.of(file.path.value)))) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejected(ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED)
        }
    val diagnostic = services.diagnostics.check(DiagnosticCheckRequest(scope))
    if (diagnostic !is DiagnosticCheckResult.Complete) return rejected(ChangePlanRejection.DIAGNOSTIC_CHECK_REQUIRED)
    return Refinement.Refined(
        AddDeclarationPlanningEvidenceInput(listOf(relation), listOf(traversal), listOf(diagnostic))
    )
}

private fun rejected(failure: ChangePlanRejection) = Refinement.Rejected(failure)
