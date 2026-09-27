package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.runtime.BrokerInvocationApproval
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ApprovedProjectCloseInvocation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.OperationRequestPreparer
import io.github.amichne.kast.protocol.wire.presentation.PreparedOperationRequest
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** One operation admission and IDEA exchange; no CLI arguments or process completion protocol. */
internal class KastDirectInvocation(private val options: KastProviderOptions) {
    private val preparers = canonicalCliRequestPreparers()

    suspend fun invoke(
        tool: QualifiedKastTool,
        input: KastInvocationInput,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        // Retain the provider's schema and approval binding before any filesystem or transport effect.
        val arguments =
            when (val encoded = input.encodeFor(tool)) {
                is Refinement.Refined -> encoded.value
                is Refinement.Rejected -> return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
            }
        val admission =
            when (val approved = KastInvocationAdmission.prepare(tool, arguments, context)) {
                is Refinement.Refined -> approved.value
                is Refinement.Rejected -> return ProviderCall.Rejected(approved.failure)
            }
        if (tool.hostedDefinition.operation == CanonicalOperation.WORKSPACE_LIFECYCLE)
            return lifecycle(admission.arguments, context)
        if (tool.hostedDefinition.operation == CanonicalOperation.CHANGE)
            return KastSingleChangeInvocation(options).invoke(input, context)
        val request =
            when (val prepared = prepare(input)) {
                is Refinement.Refined -> prepared.value
                is Refinement.Rejected -> return ProviderCall.Rejected(prepared.failure.providerFailure())
            }
        val operation =
            when (val admitted = operation(request)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return ProviderCall.Rejected(admitted.failure.providerFailure())
            }
        val root =
            when (
                val admitted =
                    runInterruptible(options.ioDispatcher) { options.roots.discover(context.workingDirectory.path) }
            ) {
                is CanonicalRootDiscovery.Discovered -> admitted.root
                is CanonicalRootDiscovery.Rejected -> return ProviderCall.Rejected(admitted.failure.providerFailure())
            }
        return when (val result = options.workspaceDemand.query(root, operation)) {
            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult.Native ->
                project(result.exchange, context)
            is io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult.Rejected ->
                ProviderCall.WorkspaceRejected(result.failure)
        }
    }

    private fun project(
        exchange: ExistingIdeExchange,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> =
        when (exchange) {
            is ExistingIdeExchange.Rejected -> ProviderCall.Rejected(exchange.failure.providerFailure())
            is ExistingIdeExchange.Received -> completed(exchange.document, true, context)
            is ExistingIdeExchange.HostRejected -> completed(exchange.document, false, context)
            is ExistingIdeExchange.Semantic ->
                when (val outcome = exchange.outcome) {
                    is ProjectedOperationOutcome.Complete -> completed(outcome.document, true, context)
                    is ProjectedOperationOutcome.Qualified -> completed(outcome.document, true, context)
                    is ProjectedOperationOutcome.Rejected -> completed(outcome.document, false, context)
                }
        }

    private suspend fun lifecycle(
        arguments: JsonElement,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        val request =
            try {
                Json.decodeFromJsonElement(WorkspaceLifecycleRequest.serializer(), arguments)
            } catch (_: SerializationException) {
                return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
            }
        val result =
            runInterruptible(options.ioDispatcher) {
                when (val approval = context.approval) {
                    is BrokerInvocationApproval.ProjectClose -> {
                        if (request !is WorkspaceLifecycleRequest.RequestUserClose)
                            return@runInterruptible IdeLifecycleResult.Blocked(
                                IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED
                            )
                        options.lifecycleClient.approvedClose(
                            ApprovedProjectCloseInvocation(request, approval.grant.assertion),
                            context.threadId.value,
                        )
                    }
                    BrokerInvocationApproval.Absent -> options.lifecycleClient.execute(request, context.threadId.value)
                    is BrokerInvocationApproval.Granted ->
                        IdeLifecycleResult.Blocked(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)
                }
            }
        val payload = invocationJson.encodeToJsonElement(IdeLifecycleResult.serializer(), result)
        val document =
            if (result is IdeLifecycleResult.Blocked)
                invocationJson.encodeToJsonElement(KastRejectedDocument(payload)).jsonObject
            else invocationJson.encodeToJsonElement(KastCompletedDocument(payload)).jsonObject
        return ProviderCall.Completed(
            KastInvocationOutput(document, result !is IdeLifecycleResult.Blocked, context.workingDirectory)
        )
    }

    private fun completed(document: CanonicalJsonDocument, success: Boolean, context: BrokerInvocationContext) =
        ProviderCall.Completed(
            KastInvocationOutput(
                invocationJson
                    .encodeToJsonElement(KastCompletedDocument(Json.parseToJsonElement(document.value)))
                    .jsonObject,
                success,
                context.workingDirectory,
            )
        )

    private fun operation(request: PreparedOperationRequest): Refinement<ExistingIdeOperation, ExistingIdeFailure> =
        when (request.operation) {
            CanonicalOperation.CHANGE_PLAN -> ExistingIdeOperation.Plan.admit(request)
            CanonicalOperation.CHANGE_APPLY,
            CanonicalOperation.CHANGE_RECOVER -> Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
            CanonicalOperation.WORKSPACE_LIFECYCLE,
            CanonicalOperation.CHANGE,
            CanonicalOperation.INDEX_SYNC,
            CanonicalOperation.TOPOLOGY_BUILD -> Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
            CanonicalOperation.QUERY_RUN,
            CanonicalOperation.SOURCE_READ,
            CanonicalOperation.DIAGNOSTIC_CHECK -> ExistingIdeOperation.Read.admit(request)
        }

    private fun prepare(input: KastInvocationInput): Refinement<PreparedOperationRequest, ExistingIdeFailure> =
        when (input) {
            is KastInvocationInput.Source -> admit(preparers.sourceRead.prepare(input.request))
            is KastInvocationInput.Facade ->
                when (val canonical = input.request.canonical) {
                    is PublicToolCanonical.Query -> admit(preparers.queryRun.prepare(canonical.request))
                    is PublicToolCanonical.Diagnostics -> admit(preparers.diagnosticCheck.prepare(canonical.request))
                }
            is KastInvocationInput.Canonical ->
                when (input.operation) {
                    CanonicalOperation.CHANGE_PLAN ->
                        decode(input, ChangePlanRequest.serializer(), preparers.changePlan)
                    CanonicalOperation.CHANGE -> Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
                    CanonicalOperation.CHANGE_APPLY ->
                        decode(input, ChangeApplyRequest.serializer(), preparers.changeApply)
                    CanonicalOperation.CHANGE_RECOVER ->
                        decode(input, ChangeRecoverRequest.serializer(), preparers.changeRecover)
                    CanonicalOperation.WORKSPACE_LIFECYCLE,
                    CanonicalOperation.INDEX_SYNC,
                    CanonicalOperation.TOPOLOGY_BUILD,
                    CanonicalOperation.QUERY_RUN,
                    CanonicalOperation.DIAGNOSTIC_CHECK,
                    CanonicalOperation.SOURCE_READ -> Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
                }
        }

    private fun <T : OperationRequest> decode(
        input: KastInvocationInput.Canonical,
        serializer: KSerializer<T>,
        preparer: OperationRequestPreparer<T>,
    ): Refinement<PreparedOperationRequest, ExistingIdeFailure> =
        try {
            admit(preparer.prepare(Json.decodeFromJsonElement(serializer, input.arguments.element)))
        } catch (_: SerializationException) {
            Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST)
        }

    private fun admit(prepared: OperationPreparation): Refinement<PreparedOperationRequest, ExistingIdeFailure> =
        when (prepared) {
            is OperationPreparation.Prepared -> Refinement.Refined(prepared.request)
            is OperationPreparation.Rejected -> Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST)
        }
}
