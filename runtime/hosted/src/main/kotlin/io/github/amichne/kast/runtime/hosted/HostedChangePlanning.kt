package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationCompilation
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanResult
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanningFailure
import io.github.amichne.kast.change.contract.LiveChangePlan
import io.github.amichne.kast.change.contract.LiveReplaceBodyCompilation
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanRequest
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanResult
import io.github.amichne.kast.change.intellij.HostedLiveAddDeclarationCompiler
import io.github.amichne.kast.change.intellij.HostedLiveReplaceBodyCompiler
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureReplaceBodyPlanningService
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import java.nio.file.Path

/** Acquires the same complete planning evidence through the original project's internal read ports. */
internal suspend fun prepareHostedAddDeclaration(
    project: Project,
    context: HostedSemanticReadContext,
    request: ChangePlanRequest,
): Refinement<LiveAddDeclarationChangePlan, HostedChangePlanningFailure> {
    val services =
        when (val admitted = admitHostedSemanticServices(project, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(HostedChangePlanningFailure.Read(admitted.failure))
        }
    val intent =
        when (val admitted = admitAddDeclarationIntent(request)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return canonicalRejected(admitted.failure)
        }
    val selector =
        when (val restored = restoreHostedChangeTarget(services, context, intent.exactTarget)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return canonicalRejected(restored.failure)
        }
    val file =
        when (val value = selector.file) {
            is SymbolDiscoveryFileIdentity.Workspace -> value
            is SymbolDiscoveryFileIdentity.External ->
                return canonicalRejected(ChangePlanRejection.EDITABLE_TARGET_REQUIRED)
        }
    val compiled =
        when (
            val result = HostedLiveAddDeclarationCompiler.compile(project, context, selector, intent.declaration.value)
        ) {
            is LiveAddDeclarationCompilation.Compiled -> result
            is LiveAddDeclarationCompilation.Rejected -> return canonicalRejected(ChangePlanRejection.INTENT_REJECTED)
        }
    val evidence =
        when (
            val observed =
                observeHostedPlanningEvidence(services = services, context = context, selector = selector, file = file)
        ) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return Refinement.Rejected(HostedChangePlanningFailure.Evidence(observed.failure))
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

internal suspend fun prepareHostedReplaceBody(
    project: Project,
    context: HostedSemanticReadContext,
    request: ChangePlanRequest,
): Refinement<LiveChangePlan, HostedChangePlanningFailure> {
    val intent =
        request.intent as? ChangeIntentDocument.ReplaceBody
            ?: return canonicalRejected(ChangePlanRejection.UNSUPPORTED_HOSTED_INTENT)
    val services =
        when (val admitted = admitHostedSemanticServices(project, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(HostedChangePlanningFailure.Read(admitted.failure))
        }
    val selector =
        when (val restored = restoreHostedChangeTarget(services, context, intent.exactTarget)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return canonicalRejected(restored.failure)
        }
    val compiled =
        when (val result = HostedLiveReplaceBodyCompiler.compile(project, context, selector, intent.body.value)) {
            is LiveReplaceBodyCompilation.Compiled -> result
            is LiveReplaceBodyCompilation.Rejected -> return canonicalRejected(ChangePlanRejection.INTENT_REJECTED)
        }
    return when (
        val planned =
            PureReplaceBodyPlanningService()
                .plan(
                    LiveReplaceBodyPlanRequest(
                        context.authority,
                        context.model,
                        selector,
                        compiled.content,
                        compiled.intent,
                    )
                )
    ) {
        is LiveReplaceBodyPlanResult.Planned -> Refinement.Refined(planned.plan)
        is LiveReplaceBodyPlanResult.Rejected -> canonicalRejected(ChangePlanRejection.INTENT_REJECTED)
    }
}

internal sealed interface HostedChangePlanningFailure {
    data class Read(val reason: io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure) :
        HostedChangePlanningFailure

    data class Canonical(val reason: ChangePlanRejection) : HostedChangePlanningFailure

    data class Evidence(val reason: HostedPlanningEvidenceFailure) : HostedChangePlanningFailure
}

private fun canonicalRejected(reason: ChangePlanRejection) =
    Refinement.Rejected(HostedChangePlanningFailure.Canonical(reason))

private fun admitAddDeclarationIntent(
    request: ChangePlanRequest
): Refinement<ChangeIntentDocument.AddDeclaration, ChangePlanRejection> =
    when (val intent = request.intent) {
        is ChangeIntentDocument.AddDeclaration -> Refinement.Refined(intent)
        is ChangeIntentDocument.AddFile,
        is ChangeIntentDocument.RenameSymbol,
        is ChangeIntentDocument.ReplaceBody,
        is ChangeIntentDocument.ReplaceDeclaration -> rejected(ChangePlanRejection.UNSUPPORTED_HOSTED_INTENT)
    }

private fun issueHostedPlan(
    request: LiveAddDeclarationPlanRequest
): Refinement<LiveAddDeclarationChangePlan, HostedChangePlanningFailure> =
    when (val result = PureAddDeclarationPlanningService().plan(request)) {
        is LiveAddDeclarationPlanResult.Planned -> Refinement.Refined(result.plan)
        is LiveAddDeclarationPlanResult.Rejected ->
            canonicalRejected(
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
        when (
            val result =
                observeHostedChange(HostedChangeStage.REFERENCE_RESTORATION) {
                    services.references.restoreExact(exactTarget, context.authority)
                }
        ) {
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
): Refinement<AddDeclarationPlanningEvidenceInput, HostedPlanningEvidenceFailure> {
    val budgets = services.budgets
    val relations = services.relations
    val relation =
        when (
            val result =
                relations
                    .read(RelationRequest.start(selector, RelationMeaning.References, budgets.hostedRelationBudget))
                    .planningEvidence()
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val traversalPlan =
        when (val result = TraversalPlan.start(selector, RelationMeaning.References, budgets.hostedTraversalBudget)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected ->
                return Refinement.Rejected(
                    HostedPlanningEvidenceFailure.TraversalRejected(
                        HostedPlanningTraversalRejection.TRAVERSAL_CONTRACT_VIOLATION
                    )
                )
        }
    val traversal =
        when (val result = traversalOperations(relations).run(traversalPlan).planningEvidence()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val scope =
        when (val result = DiagnosticScope.fromCanonicalPaths(context.authority, listOf(Path.of(file.path.value)))) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected ->
                return Refinement.Rejected(
                    HostedPlanningEvidenceFailure.DiagnosticRejected(
                        io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection.SCOPE_REJECTED
                    )
                )
        }
    val diagnostic =
        when (val result = services.diagnostics.check(DiagnosticCheckRequest(scope)).planningEvidence()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    return Refinement.Refined(
        AddDeclarationPlanningEvidenceInput(listOf(relation), listOf(traversal), listOf(diagnostic))
    )
}

private fun rejected(failure: ChangePlanRejection) = Refinement.Rejected(failure)
