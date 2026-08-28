package io.github.amichne.kast.runtime.ide.read.workspace

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProjectTestRead
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeHostedWorkspaceInspectNegativeProof {
    @Test
    fun `isolated host and unavailable current epoch reject without repair`() {
        assertEquals(
            HostedWorkspaceInspectionPreparation.Rejected(
                HostedWorkspaceInspectionPreparationFailure.NON_IDE_PROJECT_HOST,
            ),
            HostedWorkspaceInspection.prepare(
                HostedWorkspaceInspectionCandidate.IsolatedRuntime,
            ),
        )
        val inspection = preparedInspection(
            HostedIdeReadProjectTestRead.READ_PREEMPTED,
        )

        assertEquals(
            OperationOutcome.Rejected(WorkspaceInspectRejection.RUNTIME_BLOCKED),
            runSuspend { inspection.execute(WorkspaceInspectRequest) },
        )
    }
}

class IdeHostedWorkspaceInspectAcceptance {
    @Test
    fun `existing IDE Project yields exact root capabilities and current epoch outcome`() {
        val generation = refined(EvidenceGeneration.parse(29))
        val inspection = when (val prepared = HostedWorkspaceInspection.prepare(
            hostedProject(HostedIdeReadProjectTestRead.CURRENT),
            generation,
        )) {
            is HostedWorkspaceInspectionPreparation.Prepared -> prepared.inspection
            is HostedWorkspaceInspectionPreparation.Rejected ->
                error("inspection rejected: ${prepared.failure}")
        }

        assertEquals(HostedWorkspaceKind.IDE_PROJECT, inspection.hostKind)
        assertEquals(IdeHostCapability.entries, inspection.capabilities.capabilities)
        assertEquals("/workspace/kast", inspection.canonicalRoot.value)
        val outcome = runSuspend { inspection.execute(WorkspaceInspectRequest) }
        assertTrue(outcome is OperationOutcome.Complete)
        val evidence = (outcome as OperationOutcome.Complete).evidence
        assertEquals(generation, evidence.generation)
        assertEquals("workspace.inspect", evidence.operation.value)
        assertEquals("/workspace/kast", evidence.payload.canonicalRoot.value)
        assertEquals(WorkspaceStateDocument.READY, evidence.payload.state)
    }
}

private fun preparedInspection(read: HostedIdeReadProjectTestRead): HostedWorkspaceInspection =
    when (val prepared = HostedWorkspaceInspection.prepare(
        hostedProject(read),
        refined(EvidenceGeneration.parse(29)),
    )) {
        is HostedWorkspaceInspectionPreparation.Prepared -> prepared.inspection
        is HostedWorkspaceInspectionPreparation.Rejected ->
            error("inspection rejected: ${prepared.failure}")
    }

private fun hostedProject(read: HostedIdeReadProjectTestRead): HostedIdeReadProject {
    val candidate = compatibilityCandidate()
    val policy = refined(IdeHostCompatibilityPolicy.define(candidate))
    val compatibility = when (val admission = policy.admit(candidate)) {
        is IdeHostCompatibilityAdmission.Admitted -> admission.compatibility
        is IdeHostCompatibilityAdmission.Rejected ->
            error("compatibility rejected: ${admission.failure}")
    }
    return HostedIdeReadProject.testing(
        refined(IdeEndpointCanonicalRoot.parse("/workspace/kast")),
        compatibility,
        read,
    )
}

private fun compatibilityCandidate() = IdeHostCompatibilityCandidate(
    ideBuild = "262.9437.185",
    kotlinPluginBuild = "262.9437.185-IJ",
    kastPluginVersion = "1.2.3",
    runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
    operationRegistryDigest = "sha256:" + "1".repeat(64),
    wireSchemaDigest = "sha256:" + "2".repeat(64),
    capabilities = listOf(
        "workspace.inspect",
        "symbol.discover",
        "symbol.resolve",
        "symbol.describe",
    ),
)

private fun <Value, Failure> refined(result: Refinement<Value, Failure>): Value = when (result) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> error("fixture rejected: ${result.failure}")
}

private fun <Value> runSuspend(block: suspend () -> Value): Value {
    var completion: Result<Value>? = null
    block.startCoroutine(object : Continuation<Value> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Value>) {
            completion = result
        }
    })
    return checkNotNull(completion).getOrThrow()
}
