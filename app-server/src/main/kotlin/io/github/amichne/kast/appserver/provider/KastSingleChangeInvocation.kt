package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.runtime.ResolvedMutationRecovery
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoverySettlement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRequest
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.contract.ChangeRunError
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.ChangeRecoveryCliState
import io.github.amichne.kast.protocol.wire.presentation.ChangeRunCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** One broker invocation around the host's durable plan, write, and recovery boundaries. */
internal class KastSingleChangeInvocation(
    private val options: KastProviderOptions,
    private val detail: ToolOutputDetail = ToolOutputDetail.VERBOSE,
) {
    private val preparers = canonicalCliRequestPreparers()

    suspend fun invoke(
        input: KastInvocationInput,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        val facade =
            input as? KastInvocationInput.Facade
                ?: return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
        val request =
            (facade.request.canonical as? PublicToolCanonical.Change)?.request
                ?: return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
        return KastSingleChangeInvocation(options, facade.request.outputDetail).invoke(request, context)
    }

    internal suspend fun invoke(
        request: ChangeRequest,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        val root =
            when (
                val discovered =
                    runInterruptible(options.ioDispatcher) {
                        options.roots.discover(context.workingDirectory.path)
                    }
            ) {
                is CanonicalRootDiscovery.Discovered -> discovered.root
                is CanonicalRootDiscovery.Rejected -> return ProviderCall.Rejected(discovered.failure.providerFailure())
            }
        val prepared = preparers.changePlan.prepare(ChangePlanRequest(request.intent))
        if (prepared !is OperationPreparation.Prepared)
            return result(ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.PLANNING_REJECTED)), context)
        val planOperation =
            when (val admitted = ExistingIdeOperation.Plan.admit(prepared.request)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return result(
                        ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.PLANNING_REJECTED)),
                        context,
                    )
            }
        val plan = phase(root, planOperation)
        val planDocument = plan.document
        val identity =
            (plan as? SingleChangeNativePhase.Complete)?.document?.let { document ->
                try {
                    changeJson
                        .decodeFromJsonElement<StoredPlan>(document)
                        .takeIf { it.status == NativeStatus.COMPLETE }
                        ?.planIdentity
                } catch (_: SerializationException) {
                    null
                }
            }
        if (identity == null || planDocument == null || HostedPlanIdentity.parse(identity) !is Refinement.Refined)
            return result(
                ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.PLANNING_REJECTED, plan = planDocument)),
                context,
            )
        return apply(root, identity, planDocument, context)
    }

    private suspend fun apply(
        root: CanonicalRoot,
        identity: String,
        plan: JsonObject,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        val operation =
            mutation(HostedMutationOperation.CHANGE_APPLY, identity)
                ?: return authorizationFailure(identity, plan, context)
        val application =
            try {
                phase(root, operation)
            } catch (cancelled: CancellationException) {
                settleCancelledApply(root, identity)
                throw cancelled
            } catch (_: RuntimeException) {
                SingleChangeNativePhase.Incomplete(null)
            }
        val applicationDocument = application.document
        if (application is SingleChangeNativePhase.Rejected)
            return result(
                ChangeRunDocument.Rejected(
                    ChangeRunError(ChangeRejection.APPLY_REJECTED, identity, plan, applicationDocument)
                ),
                context,
            )
        val state = applicationState(applicationDocument)
        val verified = verifiedApplication(application, state)
        if (verified != null) return result(ChangeRunDocument.Complete(identity, plan, verified), context)
        if (application is SingleChangeNativePhase.Incomplete && state?.status == NativeStatus.REJECTED)
            return result(
                ChangeRunDocument.Rejected(
                    ChangeRunError(ChangeRejection.APPLY_REJECTED, identity, plan, applicationDocument)
                ),
                context,
            )
        val recovery =
            try {
                recover(root, identity)
            } catch (cancelled: CancellationException) {
                settleCancelledApply(root, identity)
                throw cancelled
            }
        val failure =
            if (recovery is SingleChangeRecoveryAttempt.Resolved) ChangeRejection.APPLY_UNVERIFIED
            else ChangeRejection.RECOVERY_UNAVAILABLE
        return result(
            ChangeRunDocument.Rejected(ChangeRunError(failure, identity, plan, applicationDocument, recovery.document)),
            context,
        )
    }

    private fun authorizationFailure(
        identity: String,
        plan: JsonObject,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> =
        result(
            ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.AUTHORIZATION_UNAVAILABLE, identity, plan)),
            context,
        )

    private fun applicationState(document: JsonObject?): ApplicationState? = document?.let {
        try {
            changeJson.decodeFromJsonElement<ApplicationState>(it)
        } catch (_: SerializationException) {
            null
        }
    }

    private fun verifiedApplication(application: SingleChangeNativePhase, state: ApplicationState?): JsonObject? {
        if (application !is SingleChangeNativePhase.Complete) return null
        if (state?.status != NativeStatus.COMPLETE || state.state != NativeApplyState.VERIFIED) return null
        return application.document
    }

    private suspend fun settleCancelledApply(
        root: CanonicalRoot,
        identity: String,
    ) {
        withContext(NonCancellable) {
            val recovery =
                withTimeoutOrNull(OperationExecutionBudget.SEMANTIC_READ.operation.value) {
                    recover(root, identity)
                }
            if (recovery is SingleChangeRecoveryAttempt.Resolved)
                currentCoroutineContext()[WorkspaceRecoverySettlement]?.confirm(recovery.state)
        }
    }

    private suspend fun recover(
        root: CanonicalRoot,
        identity: String,
    ): SingleChangeRecoveryAttempt {
        val operation =
            mutation(HostedMutationOperation.CHANGE_RECOVER, identity)
                ?: return SingleChangeRecoveryAttempt.Unresolved(null)
        val phase =
            try {
                phase(root, operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                return SingleChangeRecoveryAttempt.Unresolved(null)
            }
        val document = phase.document ?: return SingleChangeRecoveryAttempt.Unresolved(null)
        if (phase !is SingleChangeNativePhase.Complete) return SingleChangeRecoveryAttempt.Unresolved(document)
        val state =
            try {
                changeJson.decodeFromJsonElement<NativeRecoveryState>(document)
            } catch (_: SerializationException) {
                return SingleChangeRecoveryAttempt.Unresolved(document)
            }
        if (state.status != NativeStatus.COMPLETE) return SingleChangeRecoveryAttempt.Unresolved(document)
        return when (state.state) {
            ChangeRecoveryCliState.PRIOR_STATE ->
                SingleChangeRecoveryAttempt.Resolved(document, ResolvedMutationRecovery.PRIOR_STATE)
            ChangeRecoveryCliState.ROLLED_BACK ->
                SingleChangeRecoveryAttempt.Resolved(document, ResolvedMutationRecovery.ROLLED_BACK)
            ChangeRecoveryCliState.RECOVERY_REQUIRED -> SingleChangeRecoveryAttempt.Unresolved(document)
        }
    }

    private fun mutation(kind: HostedMutationOperation, identity: String): ExistingIdeOperation? {
        val planIdentity =
            when (val parsed = HostedPlanIdentity.parse(identity)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return null
            }
        val text =
            when (val parsed = ProtocolText.parse(identity)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return null
            }
        val prepared =
            when (kind) {
                HostedMutationOperation.CHANGE_APPLY -> preparers.changeApply.prepare(ChangeApplyRequest(text))
                HostedMutationOperation.CHANGE_RECOVER -> preparers.changeRecover.prepare(ChangeRecoverRequest(text))
            }
        if (prepared !is OperationPreparation.Prepared) return null
        return when (
            val admitted =
                ExistingIdeOperation.Mutation.admit(
                    prepared.request,
                    kind,
                    planIdentity,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> null
        }
    }

    private suspend fun phase(root: CanonicalRoot, operation: ExistingIdeOperation): SingleChangeNativePhase =
        when (val demanded = options.workspaceDemand.query(root, operation)) {
            is WorkspaceDemandResult.Rejected -> SingleChangeNativePhase.Rejected(null)
            is WorkspaceDemandResult.Native ->
                when (val exchange = demanded.exchange) {
                    is ExistingIdeExchange.Rejected -> SingleChangeNativePhase.Incomplete(null)
                    is ExistingIdeExchange.HostRejected -> SingleChangeNativePhase.Rejected(document(exchange.document))
                    is ExistingIdeExchange.Received -> SingleChangeNativePhase.Incomplete(document(exchange.document))
                    is ExistingIdeExchange.Semantic ->
                        when (val outcome = exchange.outcome) {
                            is ProjectedOperationOutcome.Complete ->
                                SingleChangeNativePhase.Complete(document(outcome.document))
                            is ProjectedOperationOutcome.Qualified ->
                                SingleChangeNativePhase.Incomplete(document(outcome.document))
                            is ProjectedOperationOutcome.Rejected ->
                                SingleChangeNativePhase.Rejected(document(outcome.document))
                        }
                }
        }

    private fun document(value: CanonicalJsonDocument): JsonObject? = runCatching {
        changeJson.parseToJsonElement(value.value) as? JsonObject
    }
        .getOrNull()

    private fun result(
        document: ChangeRunDocument,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> =
        ProviderCall.Completed(
            KastInvocationOutput(
                invocationJson
                    .encodeToJsonElement(
                        KastCompletedDocument(
                            Json.parseToJsonElement(ChangeRunCliDocuments.project(document).present(detail).value)
                        )
                    )
                    .jsonObject,
                document is ChangeRunDocument.Complete,
                context.workingDirectory,
            )
        )
}

@Serializable private data class StoredPlan(val status: NativeStatus, val planIdentity: String)

@Serializable private data class ApplicationState(val status: NativeStatus, val state: NativeApplyState? = null)

@Serializable private data class NativeRecoveryState(val status: NativeStatus, val state: ChangeRecoveryCliState)

@Serializable
private enum class NativeStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("rejected") REJECTED,
}

@Serializable
private enum class NativeApplyState {
    @SerialName("verified") VERIFIED,
    @SerialName("applied_unverified") APPLIED_UNVERIFIED,
}

private val changeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
