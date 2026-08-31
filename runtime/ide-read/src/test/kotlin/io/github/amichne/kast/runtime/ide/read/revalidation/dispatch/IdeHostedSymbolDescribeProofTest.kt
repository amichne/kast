package io.github.amichne.kast.runtime.ide.read.symbol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProjectTestRead
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimeCandidate
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparation
import io.github.amichne.kast.runtime.ide.read.workspace.HostedWorkspaceInspection
import io.github.amichne.kast.runtime.ide.read.workspace.HostedWorkspaceInspectionPreparation
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeHostedSymbolDescribeNegativeProof {
    @Test
    fun `weakened wrong moved and cancelled selectors fail closed without rediscovery`() {
        assertEquals(
            HostedSymbolDescriptionPreparation.Rejected(
                HostedSymbolDescriptionPreparationFailure.NON_IDE_PROJECT_HOST,
            ),
            HostedSymbolDescription.prepare(HostedSymbolDescriptionCandidate.IsolatedRuntime),
        )

        val weakened = preparedDescription { request, _ ->
            assertEquals("Widget", request.exactSelector.value)
            HostedExactSelectorAdmission.Rejected(SymbolDescribeRejection.NOT_FOUND)
        }
        assertEquals(
            OperationOutcome.Rejected(SymbolDescribeRejection.NOT_FOUND),
            runSuspend { weakened.execute(request("Widget")) },
        )

        val wrongSelector = preparedDescription { _, _ -> admitted {
            completeOutcome("exact:Other")
        } }
        assertEquals(
            OperationOutcome.Rejected(SymbolDescribeRejection.SELECTOR_STALE),
            runSuspend { wrongSelector.execute(request()) },
        )

        val wrongOperation = preparedDescription { _, _ -> admitted {
            completeOutcome("exact:Widget", CanonicalOperation.SYMBOL_RESOLVE)
        } }
        assertEquals(
            OperationOutcome.Rejected(SymbolDescribeRejection.SELECTOR_STALE),
            runSuspend { wrongOperation.execute(request()) },
        )

        val moved = preparedDescription(
            HostedIdeReadProjectTestRead.MOVED_AFTER_SEMANTIC_READ,
        ) { _, _ -> admitted { completeOutcome("exact:Widget") } }
        assertEquals(
            OperationOutcome.Rejected(SymbolDescribeRejection.SELECTOR_STALE),
            runSuspend { moved.execute(request()) },
        )

        var attempts = 0
        val cancellable = preparedDescription { _, _ -> admitted {
            attempts += 1
            if (attempts == 1) throw CancellationException("cancelled")
            completeOutcome("exact:Widget")
        } }
        assertThrows(CancellationException::class.java) {
            runSuspend { cancellable.execute(request()) }
        }
        assertTrue(runSuspend { cancellable.execute(request()) } is OperationOutcome.Complete)

        var defectAttempts = 0
        val defective = preparedDescription { _, _ -> admitted {
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

class IdeHostedSymbolDescribeAcceptance {
    @Test
    fun `exact selector describes same detached declaration through retained Project`() {
        var observedRoot: String? = null
        var descriptionCalls = 0
        val description = preparedDescription { request, currentRead ->
            assertEquals("exact:Widget", request.exactSelector.value)
            observedRoot = currentRead.canonicalRoot.value
            admitted {
                descriptionCalls += 1
                completeOutcome("exact:Widget")
            }
        }

        val outcome = runSuspend { description.execute(request()) }

        assertEquals("/workspace/kast", observedRoot)
        assertEquals(1, descriptionCalls)
        assertTrue(outcome is OperationOutcome.Complete)
        val evidence = (outcome as OperationOutcome.Complete).evidence
        assertEquals(CanonicalOperation.SYMBOL_DESCRIBE.id, evidence.operation)
        assertEquals("exact:Widget", evidence.payload.symbol.selector.value)
        assertEquals("Widget", evidence.payload.symbol.name.value)
        assertEquals("src/Widget.kt", evidence.payload.symbol.file.value)
    }

    @Test
    fun `one discovery refines through exact resolution and description in complete runtime`() {
        val project = hostedProject(HostedIdeReadProjectTestRead.CURRENT)
        var discoveryCalls = 0
        val discovery = when (val prepared = HostedSymbolDiscovery.prepare(project) { _, _ ->
            discoveryCalls += 1
            discoveryOutcome()
        }) {
            is HostedSymbolDiscoveryPreparation.Prepared -> prepared.discovery
            is HostedSymbolDiscoveryPreparation.Rejected -> error(
                "discovery rejected: ${prepared.failure}",
            )
        }
        val resolution = when (val prepared = HostedSymbolResolution.prepare(project) {
                request, _ ->
            assertEquals("candidate:Widget", request.candidateSelector.value)
            admittedResolution { resolutionOutcome() }
        }) {
            is HostedSymbolResolutionPreparation.Prepared -> prepared.resolution
            is HostedSymbolResolutionPreparation.Rejected -> error(
                "resolution rejected: ${prepared.failure}",
            )
        }
        val description = when (val prepared = HostedSymbolDescription.prepare(project) {
                request, _ ->
            assertEquals("exact:Widget", request.exactSelector.value)
            admitted { completeOutcome("exact:Widget") }
        }) {
            is HostedSymbolDescriptionPreparation.Prepared -> prepared.description
            is HostedSymbolDescriptionPreparation.Rejected -> error(
                "description rejected: ${prepared.failure}",
            )
        }
        val inspection = when (val prepared = HostedWorkspaceInspection.prepare(
            project,
            refined(EvidenceGeneration.parse(31)),
        )) {
            is HostedWorkspaceInspectionPreparation.Prepared -> prepared.inspection
            is HostedWorkspaceInspectionPreparation.Rejected -> error(
                "inspection rejected: ${prepared.failure}",
            )
        }
        assertTrue(
            HostedIdeReadRuntime.prepare(
                HostedIdeReadRuntimeCandidate.Complete(
                    project,
                    SemanticReadLease(
                        refined(
                            CanonicalWorkspaceRoot.fromCanonicalPath(
                                Path.of(project.canonicalRoot.value),
                            ),
                        ),
                        refined(EvidenceGeneration.parse(31)),
                    ),
                    inspection,
                    discovery,
                    resolution,
                    description,
                ),
            ) is HostedIdeReadRuntimePreparation.Prepared,
        )

        val discovered = runSuspend { discovery.execute(discoveryRequest()) }
        val candidate = ((discovered as OperationOutcome.Complete).evidence.payload.items.values
            .single() as SymbolDiscoveryDocument.Declaration).candidateSelector
        val resolved = runSuspend { resolution.execute(SymbolResolveRequest(candidate)) }
        val exact = (resolved as OperationOutcome.Complete).evidence.payload.exactSelector
        val described = runSuspend { description.execute(SymbolDescribeRequest(exact)) }

        assertEquals(1, discoveryCalls)
        assertEquals(
            exact,
            (described as OperationOutcome.Complete).evidence.payload.symbol.selector,
        )
    }
}

private fun preparedDescription(
    read: HostedIdeReadProjectTestRead = HostedIdeReadProjectTestRead.CURRENT,
    authority: HostedExactSelectorAuthority,
): HostedSymbolDescription = when (val prepared = HostedSymbolDescription.prepare(
    hostedProject(read),
    authority,
)) {
    is HostedSymbolDescriptionPreparation.Prepared -> prepared.description
    is HostedSymbolDescriptionPreparation.Rejected -> error(
        "description rejected: ${prepared.failure}",
    )
}

private fun admitted(
    description: HostedSymbolDescriptionCapability,
): HostedExactSelectorAdmission = HostedExactSelectorAdmission.Admitted(description)

private fun admittedResolution(
    resolution: HostedSymbolResolutionCapability,
): HostedCandidateSelectorAdmission = HostedCandidateSelectorAdmission.Admitted(resolution)

private fun request(selector: String = "exact:Widget") =
    SymbolDescribeRequest(protocolText(selector))

private fun completeOutcome(
    selector: String,
    operation: CanonicalOperation = CanonicalOperation.SYMBOL_DESCRIBE,
): OperationOutcome<SymbolDescribeResult, Nothing, SymbolDescribeRejection> =
    OperationOutcome.Complete(
        EvidenceEnvelope(
            operation.id,
            refined(EvidenceGeneration.parse(31)),
            SymbolDescribeResult(symbol(selector)),
        ),
    )

private fun discoveryRequest() = SymbolDiscoverRequest(
    SymbolDiscoverTargetDocument.Name(
        protocolText("Widget"),
        SymbolNameKindDocument.SYMBOL,
        SymbolDiscoveryMatchDocument.EXACT_NAME,
    ),
    refined(ProtocolCount.parse(1)),
)

private fun discoveryOutcome(): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = OperationOutcome.Complete(
    EvidenceEnvelope(
        CanonicalOperation.SYMBOL_DISCOVER.id,
        refined(EvidenceGeneration.parse(31)),
        SymbolDiscoverResult(
            refined(BoundedProtocolList.create(listOf(
                SymbolDiscoveryDocument.Declaration(
                    protocolText("candidate:Widget"),
                    SymbolDiscoveryKindDocument.CLASS,
                    protocolText("Widget"),
                    protocolText("src/Widget.kt"),
                    offset(7),
                ),
            ))),
        ),
    ),
)

private fun resolutionOutcome(): OperationOutcome<
    SymbolResolveResult,
    Nothing,
    SymbolResolveRejection,
    > = OperationOutcome.Complete(
    EvidenceEnvelope(
        CanonicalOperation.SYMBOL_RESOLVE.id,
        refined(EvidenceGeneration.parse(31)),
        SymbolResolveResult(protocolText("exact:Widget")),
    ),
)

private fun symbol(selector: String): SymbolDocument {
    val qualifiedIdentity = protocolText("sample.Widget")
    val signature = CompilerSignatureDocument.ClassLike(qualifiedIdentity)
    val compilerEvidence = refined(CompilerSymbolEvidenceDocument.fromSignature(signature))
    return refined(
        SymbolDocument.create(
            selector = protocolText(selector),
            kind = SymbolKindDocument.CLASSLIKE,
            name = protocolText("Widget"),
            qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(qualifiedIdentity),
            file = protocolText("src/Widget.kt"),
            range = refined(SourceRangeDocument.create(offset(7), offset(13))),
            compilerEvidence = compilerEvidence,
        ),
    )
}

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

private fun offset(raw: Int): ProtocolOffset = refined(ProtocolOffset.parse(raw))

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
