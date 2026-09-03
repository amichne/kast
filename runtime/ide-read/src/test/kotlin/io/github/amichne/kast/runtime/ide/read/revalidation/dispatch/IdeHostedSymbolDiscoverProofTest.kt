package io.github.amichne.kast.runtime.ide.read.symbol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
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

class IdeHostedSymbolDiscoverNegativeProof {
    @Test
    fun `isolated runtime movement bounds qualification and cancellation fail closed`() {
        assertEquals(
            HostedSymbolDiscoveryPreparation.Rejected(
                HostedSymbolDiscoveryPreparationFailure.NON_IDE_PROJECT_HOST,
            ),
            HostedSymbolDiscovery.prepare(HostedSymbolDiscoveryCandidate.IsolatedRuntime),
        )

        var movedCalls = 0
        val moved = preparedDiscovery(HostedIdeReadProjectTestRead.MOVED_AFTER_SEMANTIC_READ) {
            _, _ ->
            movedCalls += 1
            completeOutcome(items = 1)
        }
        assertEquals(
            OperationOutcome.Rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY),
            runSuspend { moved.execute(request(limit = 1)) },
        )
        assertEquals(1, movedCalls)

        val excessive = preparedDiscovery { _, _ -> completeOutcome(items = 2) }
        assertEquals(
            OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED),
            runSuspend { excessive.execute(request(limit = 1)) },
        )

        val dumb = preparedDiscovery { _, _ ->
            qualifiedOutcome(SymbolDiscoverLimitation.DUMB_MODE_TRANSITION)
        }
        assertTrue(runSuspend { dumb.execute(request(limit = 1)) } is OperationOutcome.Qualified)

        var attempts = 0
        val cancellable = preparedDiscovery { _, _ ->
            attempts += 1
            if (attempts == 1) throw CancellationException("cancelled")
            completeOutcome(items = 1)
        }
        assertThrows(CancellationException::class.java) {
            runSuspend { cancellable.execute(request(limit = 1)) }
        }
        assertTrue(
            runSuspend { cancellable.execute(request(limit = 1)) } is OperationOutcome.Complete,
        )
    }
}

class IdeHostedSymbolDiscoverAcceptance {
    @Test
    fun `current exact Project admits bounded detached native outcome`() {
        var observedRoot: String? = null
        val discovery = preparedDiscovery { _, currentRead ->
            observedRoot = currentRead.canonicalRoot.value
            completeOutcome(items = 1)
        }

        val outcome = runSuspend { discovery.execute(request(limit = 1)) }

        assertEquals("/workspace/kast", observedRoot)
        assertTrue(outcome is OperationOutcome.Complete)
        val evidence = (outcome as OperationOutcome.Complete).evidence
        assertEquals(CanonicalOperation.SYMBOL_DISCOVER.id, evidence.operation)
        assertEquals(1, evidence.payload.items.values.size)
    }
}

private fun preparedDiscovery(
    read: HostedIdeReadProjectTestRead = HostedIdeReadProjectTestRead.CURRENT,
    native: HostedNativeSymbolDiscovery,
): HostedSymbolDiscovery = when (val prepared = HostedSymbolDiscovery.prepare(
    hostedProject(read),
    native,
)) {
    is HostedSymbolDiscoveryPreparation.Prepared -> prepared.discovery
    is HostedSymbolDiscoveryPreparation.Rejected -> error("discovery rejected: ${prepared.failure}")
}

private fun request(limit: Int) = SymbolDiscoverRequest(
    SymbolDiscoverTargetDocument.Name(
        protocolText("Widget"),
        SymbolNameKindDocument.SYMBOL,
        SymbolDiscoveryMatchDocument.EXACT_NAME,
    ),
    refined(ProtocolCount.parse(limit)),
)

private fun completeOutcome(items: Int): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = OperationOutcome.Complete(discoveryEvidence(items))

private fun qualifiedOutcome(limitation: SymbolDiscoverLimitation): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = OperationOutcome.Qualified(
    discoveryEvidence(0),
    refined(SymbolDiscoverQualification.from(setOf(limitation))),
)

private fun discoveryEvidence(items: Int) = EvidenceEnvelope(
    CanonicalOperation.SYMBOL_DISCOVER.id,
    refined(EvidenceGeneration.parse(29)),
    SymbolDiscoverResult(
        refined(BoundedProtocolList.create(
            List(items) { ordinal ->
                SymbolDiscoveryDocument.File(
                    protocolText("candidate:v2:file:29:$ordinal"),
                    protocolText("Widget$ordinal.kt"),
                    protocolText("/workspace/kast/src/Widget$ordinal.kt"),
                )
            },
        )),
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
