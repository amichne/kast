package io.github.amichne.kast.runtime.ide.read.symbol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProjectTestRead
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeHostedSymbolResolveNegativeProof {
    @Test
    fun `raw stale ambiguous echoed moved and cancelled candidates fail closed`() {
        assertEquals(
            HostedSymbolResolutionPreparation.Rejected(
                HostedSymbolResolutionPreparationFailure.NON_IDE_PROJECT_HOST,
            ),
            HostedSymbolResolution.prepare(HostedSymbolResolutionCandidate.IsolatedRuntime),
        )

        val raw = preparedResolution { request, _ ->
            assertEquals("Widget", request.candidateSelector.value)
            HostedCandidateSelectorAdmission.Rejected(SymbolResolveRejection.NOT_FOUND)
        }
        assertEquals(
            OperationOutcome.Rejected(SymbolResolveRejection.NOT_FOUND),
            runSuspend { raw.execute(request("Widget")) },
        )
        val stale = preparedResolution { _, _ ->
            HostedCandidateSelectorAdmission.Rejected(SymbolResolveRejection.CANDIDATE_STALE)
        }
        assertEquals(
            OperationOutcome.Rejected(SymbolResolveRejection.CANDIDATE_STALE),
            runSuspend { stale.execute(request()) },
        )

        val ambiguous = preparedResolution { _, _ ->
            HostedCandidateSelectorAdmission.Rejected(SymbolResolveRejection.AMBIGUOUS)
        }
        assertEquals(
            OperationOutcome.Rejected(SymbolResolveRejection.AMBIGUOUS),
            runSuspend { ambiguous.execute(request()) },
        )

        val echoed = preparedResolution { _, _ -> admitted { completeOutcome("candidate:Widget") } }
        assertEquals(
            OperationOutcome.Rejected(SymbolResolveRejection.CANDIDATE_STALE),
            runSuspend { echoed.execute(request()) },
        )

        val moved = preparedResolution(HostedIdeReadProjectTestRead.MOVED_AFTER_SEMANTIC_READ) {
                _, _ -> admitted { completeOutcome("exact:Widget") }
        }
        assertEquals(
            OperationOutcome.Rejected(SymbolResolveRejection.CANDIDATE_STALE),
            runSuspend { moved.execute(request()) },
        )

        var attempts = 0
        val cancellable = preparedResolution { _, _ -> admitted {
            attempts += 1
            if (attempts == 1) throw CancellationException("cancelled")
            completeOutcome("exact:Widget")
        } }
        assertThrows(CancellationException::class.java) {
            runSuspend { cancellable.execute(request()) }
        }
        assertTrue(runSuspend { cancellable.execute(request()) } is OperationOutcome.Complete)

        var defectAttempts = 0
        val defective = preparedResolution { _, _ -> admitted {
            defectAttempts += 1
            if (defectAttempts == 1) error("native defect")
            completeOutcome("exact:Widget")
        } }
        assertThrows(IllegalStateException::class.java) {
            runSuspend { defective.execute(request()) }
        }
        assertTrue(runSuspend { defective.execute(request()) } is OperationOutcome.Complete)
    }
}

class IdeHostedSymbolResolveAcceptance {
    @Test
    fun `admitted candidate resolves exact identity through current retained Project`() {
        var observedRoot: String? = null
        var resolutionCalls = 0
        val resolution = preparedResolution { request, currentRead ->
            assertEquals("candidate:Widget", request.candidateSelector.value)
            observedRoot = currentRead.canonicalRoot.value
            admitted {
                resolutionCalls += 1
                completeOutcome("exact:Widget")
            }
        }

        val outcome = runSuspend { resolution.execute(request()) }

        assertEquals("/workspace/kast", observedRoot)
        assertEquals(1, resolutionCalls)
        assertTrue(outcome is OperationOutcome.Complete)
        val evidence = (outcome as OperationOutcome.Complete).evidence
        assertEquals(CanonicalOperation.SYMBOL_RESOLVE.id, evidence.operation)
        assertEquals("exact:Widget", evidence.payload.exactSelector.value)
    }
}

private fun preparedResolution(
    read: HostedIdeReadProjectTestRead = HostedIdeReadProjectTestRead.CURRENT,
    authority: HostedCandidateSelectorAuthority,
): HostedSymbolResolution = when (val prepared = HostedSymbolResolution.prepare(
    hostedProject(read),
    authority,
)) {
    is HostedSymbolResolutionPreparation.Prepared -> prepared.resolution
    is HostedSymbolResolutionPreparation.Rejected -> error(
        "resolution rejected: ${prepared.failure}",
    )
}

private fun admitted(
    resolution: HostedSymbolResolutionCapability,
): HostedCandidateSelectorAdmission = HostedCandidateSelectorAdmission.Admitted(resolution)

private fun request(selector: String = "candidate:Widget") =
    SymbolResolveRequest(protocolText(selector))

private fun completeOutcome(
    selector: String,
): OperationOutcome<SymbolResolveResult, Nothing, SymbolResolveRejection> =
    OperationOutcome.Complete(
        EvidenceEnvelope(
            CanonicalOperation.SYMBOL_RESOLVE.id,
            refined(EvidenceGeneration.parse(30)),
            SymbolResolveResult(protocolText(selector)),
        ),
    )

private fun hostedProject(read: HostedIdeReadProjectTestRead): HostedIdeReadProject {
    val candidate = IdeHostCompatibilityCandidate(
        ideBuild = "262.9437.185",
        kotlinPluginBuild = "262.9437.185-IJ",
        kastPluginVersion = "1.2.3",
        runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
        operationRegistryDigest = "sha256:" + "1".repeat(64),
        wireSchemaDigest = "sha256:" + "2".repeat(64),
        capabilities = IdeHostCapability.entries.map { it.operation.id.value },
    )
    val policy = refined(IdeHostCompatibilityPolicy.define(candidate))
    val compatibility = when (val admitted = policy.admit(candidate)) {
        is IdeHostCompatibilityAdmission.Admitted -> admitted.compatibility
        is IdeHostCompatibilityAdmission.Rejected -> error("compatibility rejected")
    }
    return HostedIdeReadProject.testing(
        refined(IdeEndpointCanonicalRoot.parse("/workspace/kast")),
        compatibility,
        read,
    )
}

private fun protocolText(raw: String): ProtocolText = refined(ProtocolText.parse(raw))

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
