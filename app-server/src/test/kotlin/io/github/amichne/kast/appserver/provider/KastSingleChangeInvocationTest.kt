package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.runtime.ResolvedMutationRecovery
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryEvidence
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoverySettlement
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState
import io.github.amichne.kast.protocol.contract.ChangeRequest
import io.github.amichne.kast.protocol.wire.presentation.CanonicalChangeCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("LargeClass") // The shared enrolled-host fixture keeps the change cases at one execution boundary.
class KastSingleChangeInvocationTest {
    @TempDir lateinit var home: Path

    @Test
    fun `root rejection preserves exact predispatch failure and never invokes the host`() = runBlocking {
        val cases =
            listOf(
                io.github.amichne.kast.appserver.ide.CanonicalRootFailure.START_UNAVAILABLE to
                    io.github.amichne.kast.appserver.core.ProviderFailureCode.WORKSPACE_START_UNAVAILABLE,
                io.github.amichne.kast.appserver.ide.CanonicalRootFailure.START_NOT_DIRECTORY to
                    io.github.amichne.kast.appserver.core.ProviderFailureCode.WORKSPACE_START_NOT_DIRECTORY,
                io.github.amichne.kast.appserver.ide.CanonicalRootFailure.ROOT_MARKER_NOT_FOUND to
                    io.github.amichne.kast.appserver.core.ProviderFailureCode.WORKSPACE_ROOT_MARKER_NOT_FOUND,
                io.github.amichne.kast.appserver.ide.CanonicalRootFailure.INVALID_ROOT_MARKER to
                    io.github.amichne.kast.appserver.core.ProviderFailureCode.WORKSPACE_INVALID_ROOT_MARKER,
            )
        val context =
            (BrokerInvocationContext.admit(
                    threadId = "thread",
                    turnId = "turn",
                    callId = "call",
                    workingDirectory = home.toRealPath(),
                ) as Refinement.Refined)
                .value
        val target =
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("exact:test") as Refinement.Refined).value
        val source =
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("fun added() = Unit") as Refinement.Refined)
                .value
        for ((failure, expected) in cases) {
            val options =
                KastProviderOptions(
                    catalogSource = KastCatalogSource { error("Unexpected catalog effect") },
                    roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Rejected(failure) },
                    ideClient = ExistingIdeClient { _, _ -> error("Unexpected host effect") },
                    workspaceDemand = WorkspaceDemand { _, _ -> error("Unexpected workspace effect") },
                )
            val result =
                KastSingleChangeInvocation(options)
                    .invoke(ChangeRequest(ChangeIntentDocument.AddDeclaration(target, source)), context)
            assertEquals(io.github.amichne.kast.appserver.core.ProviderCall.Rejected(expected), result)
        }
    }

    @Test
    fun `one broker call plans and returns verified receipt without enrolled keys`() = runBlocking {
        val observed = mutableListOf<String>()
        val invocation = invocation { operation ->
            when (operation) {
                is ExistingIdeOperation.Plan -> {
                    observed += "plan"
                    semantic(TestPlan())
                }
                is ExistingIdeOperation.Mutation -> {
                    observed += "write-${operation.kind.name}"
                    semantic(TestApplication())
                }
                else -> error("Unexpected operation")
            }
        }

        assertInstanceOf(io.github.amichne.kast.appserver.core.ProviderCall.Completed::class.java, invocation)
        val output = (invocation as io.github.amichne.kast.appserver.core.ProviderCall.Completed).value
        assertTrue(output.success)
        val body = output.document.getValue("document").jsonObject
        assertEquals("complete", body.getValue("status").jsonPrimitive.content)
        assertEquals(
            "receipt:verified",
            body.getValue("application").jsonObject.getValue("receiptIdentity").jsonPrimitive.content,
        )
        assertEquals(listOf("plan", "write-CHANGE_APPLY"), observed)
    }

    @Test
    fun `unverified write preserves actual projected recovery rolled back`() =
        assertProjectedRecovery(ChangeRecoveryDocumentState.ROLLED_BACK)

    @Test
    fun `unverified write preserves actual projected recovery prior state`() =
        assertProjectedRecovery(ChangeRecoveryDocumentState.PRIOR_STATE)

    @Test
    fun `unverified write preserves actual projected recovery recovery required`() =
        assertProjectedRecovery(ChangeRecoveryDocumentState.RECOVERY_REQUIRED)

    private fun assertProjectedRecovery(recoveryState: ChangeRecoveryDocumentState) = runBlocking {
        val observed = mutableListOf<String>()
        val invocation = invocation { operation ->
            when (operation) {
                is ExistingIdeOperation.Plan -> {
                    observed += "plan"
                    semantic(TestPlan())
                }
                is ExistingIdeOperation.Mutation -> {
                    observed += "write-${operation.kind.name}"
                    if (operation.kind.name == "CHANGE_APPLY")
                        ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Qualified(document(TestUnverified())))
                    else projectedRecovery(recoveryState)
                }
                else -> error("Unexpected operation")
            }
        }
        assertInstanceOf(io.github.amichne.kast.appserver.core.ProviderCall.Completed::class.java, invocation)
        val output = (invocation as io.github.amichne.kast.appserver.core.ProviderCall.Completed).value
        assertTrue(!output.success)
        val body = output.document.getValue("document").jsonObject
        assertEquals("rejected", body.getValue("status").jsonPrimitive.content)
        val error = body.getValue("error").jsonObject
        assertProjectedRecoveryEvidence(error, recoveryState)
        assertEquals(
            listOf(
                "plan",
                "write-CHANGE_APPLY",
                "write-CHANGE_RECOVER",
            ),
            observed,
        )
    }

    @Test
    fun `host rejection remains apply rejected and never starts recovery`() = runBlocking {
        val observed = mutableListOf<String>()
        val invocation = invocation { operation ->
            when (operation) {
                is ExistingIdeOperation.Plan -> {
                    observed += "plan"
                    semantic(TestPlan())
                }
                is ExistingIdeOperation.Mutation -> {
                    observed += "write-${operation.kind.name}"
                    ExistingIdeExchange.HostRejected(document(TestHostRejection()))
                }
                else -> error("Unexpected operation")
            }
        }
        assertInstanceOf(io.github.amichne.kast.appserver.core.ProviderCall.Completed::class.java, invocation)
        val output = (invocation as io.github.amichne.kast.appserver.core.ProviderCall.Completed).value
        assertTrue(!output.success)
        val error = output.document.getValue("document").jsonObject.getValue("error").jsonObject
        assertEquals("APPLY_REJECTED", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            "SOURCE_CHANGED",
            error.getValue("application").jsonObject.getValue("failure").jsonPrimitive.content,
        )
        assertEquals(listOf("plan", "write-CHANGE_APPLY"), observed)
    }

    @Test
    fun `workspace preparation rejection before apply remains apply rejected`() = runBlocking {
        val observed = mutableListOf<String>()
        val output = invocationWithDemand { root, operation ->
            when (operation) {
                is ExistingIdeOperation.Plan -> {
                    observed += "plan"
                    WorkspaceDemandResult.Native(semantic(TestPlan()))
                }
                is ExistingIdeOperation.Mutation -> {
                    observed += "write-${operation.kind.name}"
                    WorkspaceDemandResult.Rejected(
                        WorkspaceDemandFailure.Admission(root, WorkspacePreparationFailure.RESPONSE_REJECTED)
                    )
                }
                else -> error("Unexpected operation")
            }
        }
        val result = (output as io.github.amichne.kast.appserver.core.ProviderCall.Completed).value
        val error = result.document.getValue("document").jsonObject.getValue("error").jsonObject
        assertEquals("APPLY_REJECTED", error.getValue("code").jsonPrimitive.content)
        assertEquals(listOf("plan", "write-CHANGE_APPLY"), observed)
    }

    @Test
    fun `cancelled post apply recovery retries non cancellably and settles proof`() = runBlocking {
        val enteredRecovery = CompletableDeferred<Unit>()
        val cancellation = CompletableDeferred<Throwable>()
        val settlement = WorkspaceRecoverySettlement()
        val recoveryCalls = mutableListOf<Unit>()
        val job =
            launch(settlement) {
                try {
                    invocation { operation -> cancelledRecoveryOperation(operation, enteredRecovery, recoveryCalls) }
                } catch (failure: Throwable) {
                    cancellation.complete(failure)
                }
            }

        enteredRecovery.await()
        job.cancelAndJoin()
        assertInstanceOf(CancellationException::class.java, cancellation.await())
        assertEquals(2, recoveryCalls.size)
        assertEquals(WorkspaceRecoveryEvidence.Proven(ResolvedMutationRecovery.ROLLED_BACK), settlement.evidence)
    }

    @Test
    fun `cancelled apply still attempts recovery outside the cancelled job`() = runBlocking {
        val enteredApply = CompletableDeferred<Unit>()
        val cancellation = CompletableDeferred<Throwable>()
        val settlement = WorkspaceRecoverySettlement()
        val observed = mutableListOf<String>()
        val job =
            launch(settlement) {
                try {
                    invocation { operation -> cancelledApplyOperation(operation, enteredApply, observed) }
                } catch (failure: Throwable) {
                    cancellation.complete(failure)
                }
            }

        enteredApply.await()
        job.cancelAndJoin()
        assertInstanceOf(CancellationException::class.java, cancellation.await())
        assertEquals(
            WorkspaceRecoveryEvidence.Proven(ResolvedMutationRecovery.ROLLED_BACK),
            settlement.evidence,
        )
        assertEquals(
            listOf(
                "plan",
                "write-CHANGE_APPLY",
                "write-CHANGE_RECOVER",
            ),
            observed,
        )
    }

    @Test
    fun `cancelled apply with incomplete recovery retains uncertainty`() = runBlocking {
        val enteredApply = CompletableDeferred<Unit>()
        val cancellation = CompletableDeferred<Throwable>()
        val settlement = WorkspaceRecoverySettlement()
        val observed = mutableListOf<String>()
        val incomplete = projectedRecovery(ChangeRecoveryDocumentState.RECOVERY_REQUIRED)
        val job =
            launch(settlement) {
                try {
                    invocation { operation ->
                        cancelledApplyOperation(
                            operation = operation,
                            enteredApply = enteredApply,
                            observed = observed,
                            recovery = incomplete,
                        )
                    }
                } catch (failure: Throwable) {
                    cancellation.complete(failure)
                }
            }

        enteredApply.await()
        job.cancelAndJoin()
        assertInstanceOf(CancellationException::class.java, cancellation.await())
        assertEquals(WorkspaceRecoveryEvidence.Unproven, settlement.evidence)
        assertEquals("write-CHANGE_RECOVER", observed.last())
    }

    private suspend fun cancelledApplyOperation(
        operation: ExistingIdeOperation,
        enteredApply: CompletableDeferred<Unit>,
        observed: MutableList<String>,
        recovery: ExistingIdeExchange = projectedRecovery(),
    ): ExistingIdeExchange =
        when (operation) {
            is ExistingIdeOperation.Plan -> {
                observed += "plan"
                semantic(TestPlan())
            }
            is ExistingIdeOperation.Mutation -> {
                observed += "write-${operation.kind.name}"
                if (operation.kind.name == "CHANGE_APPLY") {
                    enteredApply.complete(Unit)
                    awaitCancellation()
                } else recovery
            }
            else -> error("Unexpected operation")
        }

    private suspend fun cancelledRecoveryOperation(
        operation: ExistingIdeOperation,
        enteredRecovery: CompletableDeferred<Unit>,
        recoveryCalls: MutableList<Unit>,
    ): ExistingIdeExchange =
        when (operation) {
            is ExistingIdeOperation.Plan -> semantic(TestPlan())
            is ExistingIdeOperation.Mutation ->
                if (operation.kind.name == "CHANGE_APPLY")
                    ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Qualified(document(TestUnverified())))
                else {
                    recoveryCalls += Unit
                    if (recoveryCalls.size == 1) {
                        enteredRecovery.complete(Unit)
                        awaitCancellation()
                    }
                    projectedRecovery()
                }
            else -> error("Unexpected operation")
        }

    private suspend fun invocation(
        observe: suspend (ExistingIdeOperation) -> ExistingIdeExchange
    ): io.github.amichne.kast.appserver.core.ProviderCall<KastInvocationOutput> = invocationWithDemand { _, operation ->
        WorkspaceDemandResult.Native(observe(operation))
    }

    private suspend fun invocationWithDemand(
        observe: suspend (CanonicalRoot, ExistingIdeOperation) -> WorkspaceDemandResult
    ): io.github.amichne.kast.appserver.core.ProviderCall<KastInvocationOutput> {
        val root = CanonicalRoot(home.toRealPath())
        val options =
            KastProviderOptions(
                catalogSource = KastCatalogSource { error("Catalog not needed") },
                userHome = home,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ideClient = ExistingIdeClient { _, _ -> error("Direct fallback forbidden") },
                workspaceDemand =
                    WorkspaceDemand { selected, operation ->
                        assertEquals(root, selected)
                        observe(selected, operation)
                    },
            )
        assertTrue(!Files.exists(home.resolve(".kast/approval")))
        val context =
            (BrokerInvocationContext.admit(
                    threadId = "thread",
                    turnId = "turn",
                    callId = "call",
                    workingDirectory = home.toRealPath(),
                ) as Refinement.Refined)
                .value
        val target =
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("exact:test") as Refinement.Refined).value
        val source =
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("fun added() = Unit") as Refinement.Refined)
                .value
        return KastSingleChangeInvocation(options)
            .invoke(ChangeRequest(ChangeIntentDocument.AddDeclaration(target, source)), context)
    }

    private fun projectedRecovery(
        state: ChangeRecoveryDocumentState = ChangeRecoveryDocumentState.ROLLED_BACK
    ): ExistingIdeExchange {
        val evidence =
            EvidenceEnvelope(
                CanonicalOperation.CHANGE_RECOVER.id,
                (EvidenceGeneration.parse(1) as Refinement.Refined).value,
                ChangeRecoverResult(state),
            )
        val outcome =
            when (state) {
                ChangeRecoveryDocumentState.PRIOR_STATE,
                ChangeRecoveryDocumentState.ROLLED_BACK ->
                    CanonicalChangeCliDocuments.projectRecovery(OperationOutcome.Complete(evidence))
                ChangeRecoveryDocumentState.RECOVERY_REQUIRED ->
                    CanonicalChangeCliDocuments.projectRecovery(
                        OperationOutcome.Qualified(evidence, ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED)
                    )
            }
        return ExistingIdeExchange.Semantic(outcome)
    }

    private inline fun <reified T> document(value: T): CanonicalJsonDocument =
        CanonicalJsonDocument.generated(kotlinx.serialization.serializer<T>()).create(value)

    private inline fun <reified T> semantic(value: T): ExistingIdeExchange =
        ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Complete(document(value)))
}

@Serializable
private data class TestPlan(val status: String = "complete", val planIdentity: String = "plan:${"a".repeat(64)}")

@Serializable
private data class TestApplication(
    val status: String = "complete",
    val state: String = "verified",
    val receiptIdentity: String = "receipt:verified",
)

@Serializable
private data class TestUnverified(val status: String = "qualified", val state: String = "recovery_required")

@Serializable
private data class TestHostRejection(val type: String = "rejected", val failure: String = "SOURCE_CHANGED")

private fun assertProjectedRecoveryEvidence(error: JsonObject, state: ChangeRecoveryDocumentState) {
    val expectedCode =
        if (state == ChangeRecoveryDocumentState.RECOVERY_REQUIRED) "RECOVERY_UNAVAILABLE" else "APPLY_UNVERIFIED"
    val expectedState =
        when (state) {
            ChangeRecoveryDocumentState.PRIOR_STATE -> "prior-state"
            ChangeRecoveryDocumentState.ROLLED_BACK -> "rolled-back"
            ChangeRecoveryDocumentState.RECOVERY_REQUIRED -> "recovery-required"
        }
    assertEquals(expectedCode, error.getValue("code").jsonPrimitive.content)
    val recovery = error.getValue("recovery").jsonObject
    assertEquals(expectedState, recovery.getValue("state").jsonPrimitive.content)
    if (state == ChangeRecoveryDocumentState.RECOVERY_REQUIRED) {
        assertEquals("qualified", recovery.getValue("status").jsonPrimitive.content)
        assertEquals("manual-recovery-required", recovery.getValue("qualification").jsonPrimitive.content)
    } else assertEquals("complete", recovery.getValue("status").jsonPrimitive.content)
}
