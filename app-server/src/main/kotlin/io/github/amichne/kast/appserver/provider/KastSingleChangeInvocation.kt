package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedApprovalAssertion
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.runtime.HostedChangeApprovalOperation
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRequest
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.contract.ChangeRunError
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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

/** One broker invocation around the host's durable plan, exact challenge, write, and recovery boundaries. */
internal class KastSingleChangeInvocation(private val options: KastProviderOptions) {
    private val preparers = canonicalCliRequestPreparers()
    private val gateway = KastHostedPlanApprovalGateway(options, options.userHome, options.ioDispatcher)
    private val signer = EnrolledPlanApprovalSigner(options.userHome)

    suspend fun invoke(
        input: KastInvocationInput,
        context: BrokerInvocationContext,
    ): ProviderCall<KastInvocationOutput> {
        val canonical =
            input as? KastInvocationInput.Canonical
                ?: return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
        val request =
            try {
                changeJson.decodeFromJsonElement<ChangeRequest>(canonical.arguments.element)
            } catch (_: SerializationException) {
                return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
            }
        return invoke(request, context)
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
                is CanonicalRootDiscovery.Rejected ->
                    return ProviderCall.Rejected(ProviderFailureCode.IDE_INVALID_REQUEST)
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
            (plan as? NativePhase.Complete)?.document?.let { document ->
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
        val assertion =
            authorize(HostedChangeApprovalOperation.APPLY, identity, context)
                ?: return authorizationFailure(identity, plan, context)
        val operation =
            mutation(HostedMutationOperation.CHANGE_APPLY, identity, assertion)
                ?: return authorizationFailure(identity, plan, context)
        val application =
            try {
                phase(root, operation)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(OperationExecutionBudget.SEMANTIC_READ.operation.value) {
                        recover(root, identity, context)
                    }
                }
                throw cancelled
            } catch (_: RuntimeException) {
                NativePhase.Incomplete(null)
            }
        val applicationDocument = application.document
        if (application is NativePhase.Rejected)
            return result(
                ChangeRunDocument.Rejected(
                    ChangeRunError(ChangeRejection.APPLY_REJECTED, identity, plan, applicationDocument)
                ),
                context,
            )
        val state = applicationState(applicationDocument)
        val verified = verifiedApplication(application, state)
        if (verified != null) return result(ChangeRunDocument.Complete(identity, plan, verified), context)
        if (application is NativePhase.Incomplete && state?.status == NativeStatus.REJECTED)
            return result(
                ChangeRunDocument.Rejected(
                    ChangeRunError(ChangeRejection.APPLY_REJECTED, identity, plan, applicationDocument)
                ),
                context,
            )
        val recovery = recover(root, identity, context)
        val failure = if (recovery == null) ChangeRejection.RECOVERY_UNAVAILABLE else ChangeRejection.APPLY_UNVERIFIED
        return result(
            ChangeRunDocument.Rejected(ChangeRunError(failure, identity, plan, applicationDocument, recovery)),
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

    private fun verifiedApplication(application: NativePhase, state: ApplicationState?): JsonObject? {
        if (application !is NativePhase.Complete) return null
        if (state?.status != NativeStatus.COMPLETE || state.state != NativeApplyState.VERIFIED) return null
        return application.document
    }

    private suspend fun recover(root: CanonicalRoot, identity: String, context: BrokerInvocationContext): JsonObject? {
        val assertion = authorize(HostedChangeApprovalOperation.RECOVER, identity, context) ?: return null
        val operation = mutation(HostedMutationOperation.CHANGE_RECOVER, identity, assertion) ?: return null
        return try {
            phase(root, operation).document
        } catch (_: RuntimeException) {
            null
        }
    }

    private suspend fun authorize(
        kind: HostedChangeApprovalOperation,
        identity: String,
        context: BrokerInvocationContext,
    ): String? {
        val request =
            HostedPlanApprovalRequest.admit(
                kind,
                context,
                changeJson.encodeToJsonElement(PlanIdentityInput(identity)),
            )
        if (request !is Refinement.Refined) return null
        val challenge = gateway.prepare(request.value)
        if (challenge !is Refinement.Refined) return null
        return when (
            val signed =
                runInterruptible(options.ioDispatcher) {
                    signer.signForInvocation(challenge.value.subject, context)
                }
        ) {
            is Refinement.Refined -> signed.value
            is Refinement.Rejected -> null
        }
    }

    private fun mutation(kind: HostedMutationOperation, identity: String, assertion: String): ExistingIdeOperation? {
        val planIdentity =
            when (val parsed = HostedPlanIdentity.parse(identity)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return null
            }
        val approval =
            when (val parsed = HostedApprovalAssertion.parse(assertion)) {
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
                ExistingIdeOperation.ApprovedMutation.admit(
                    prepared.request,
                    kind,
                    planIdentity,
                    approval,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> null
        }
    }

    private suspend fun phase(root: CanonicalRoot, operation: ExistingIdeOperation): NativePhase =
        when (val demanded = options.workspaceDemand.query(root, operation)) {
            is WorkspaceDemandResult.Rejected -> NativePhase.Incomplete(null)
            is WorkspaceDemandResult.Native ->
                when (val exchange = demanded.exchange) {
                    is ExistingIdeExchange.Rejected -> NativePhase.Incomplete(null)
                    is ExistingIdeExchange.HostRejected -> NativePhase.Rejected(document(exchange.document))
                    is ExistingIdeExchange.Received -> NativePhase.Incomplete(document(exchange.document))
                    is ExistingIdeExchange.Semantic ->
                        when (val outcome = exchange.outcome) {
                            is ProjectedOperationOutcome.Complete -> NativePhase.Complete(document(outcome.document))
                            is ProjectedOperationOutcome.Qualified -> NativePhase.Incomplete(document(outcome.document))
                            is ProjectedOperationOutcome.Rejected -> NativePhase.Rejected(document(outcome.document))
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
                        KastCompletedDocument(changeJson.encodeToJsonElement(ChangeRunDocument.serializer(), document))
                    )
                    .jsonObject,
                document is ChangeRunDocument.Complete,
                context.workingDirectory,
            )
        )
}

private sealed interface NativePhase {
    val document: JsonObject?

    data class Complete(override val document: JsonObject?) : NativePhase

    data class Incomplete(override val document: JsonObject?) : NativePhase

    data class Rejected(override val document: JsonObject?) : NativePhase
}

@Serializable private data class StoredPlan(val status: NativeStatus, val planIdentity: String)

@Serializable private data class ApplicationState(val status: NativeStatus, val state: NativeApplyState? = null)

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

@Serializable private data class PlanIdentityInput(val planIdentity: String)

private val changeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
