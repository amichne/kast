package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorAdmission
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeProcessId
import io.github.amichne.kast.protocol.wire.metadata.IdeRuntimeEpoch
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDescribeReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDiscoverReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolResolveReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.WorkspaceInspectReadPort
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimeCandidate
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparation
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntime as HostedEffectsRuntime
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointPublicationTest {
    @Test
    fun `complete runtime binds one reachable socket and atomically publishes an admitted descriptor`() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "kast-endpoint-")
        val compatibilityCandidate = positiveCompatibilityCandidate()
        val policy = positiveRefined(IdeHostCompatibilityPolicy.define(compatibilityCandidate))
        val compatibility = when (val admission = policy.admit(compatibilityCandidate)) {
            is IdeHostCompatibilityAdmission.Admitted -> admission.compatibility
            is IdeHostCompatibilityAdmission.Rejected -> fail(
                "compatibility rejected: ${admission.failure}",
            )
        }
        val root = positiveRefined(IdeEndpointCanonicalRoot.parse("/workspace/kast"))
        val workspaceOutcome: OperationOutcome<
            WorkspaceInspectResult,
            WorkspaceInspectQualification,
            WorkspaceInspectRejection,
            > = OperationOutcome.Rejected(WorkspaceInspectRejection.RUNTIME_BLOCKED)
        var workspaceCalls = 0
        val readRuntime = HostedIdeReadRuntime.prepare(
            HostedIdeReadRuntimeCandidate.Complete(
                HostedIdeReadProject.testing(root, compatibility),
                SemanticReadLease(
                    positiveRefined(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root.value))),
                    positiveRefined(EvidenceGeneration.parse(0)),
                ),
                WorkspaceInspectReadPort {
                    workspaceCalls += 1
                    workspaceOutcome
                },
                SymbolDiscoverReadPort { fail("discover port must not run during publication") },
                SymbolResolveReadPort { fail("resolve port must not run during publication") },
                SymbolDescribeReadPort { fail("describe port must not run during publication") },
            ),
        )
        val runtime = HostedEffectsRuntime.testing(
            (readRuntime as HostedIdeReadRuntimePreparation.Prepared).runtime,
        )
        val prepared = IdeEndpointPreparation.prepare(
            IdeEndpointPreparationCandidate(
                descriptorRoot = root,
                runtime = runtime,
                compatibilityPolicy = policy,
                socketDirectory = positiveRefined(
                    IdeEndpointSocketDirectory.parse(directory.toString()),
                ),
                processId = positiveRefined(IdeProcessId.parse(ProcessHandle.current().pid())),
                runtimeEpoch = positiveRefined(IdeRuntimeEpoch.parse(7)),
            ),
        ).positivePrepared()
        val coordinator = IdeEndpointCoordinator(JdkIdeEndpointPublisher)
        assertEquals(IdeEndpointServiceStart.Started, coordinator.begin())
        val launch = coordinator.listenersInstalled()
        assertTrue(launch is IdeEndpointSignalPlan.Launch)
        val activation = coordinator.planCompletion(
            (launch as IdeEndpointSignalPlan.Launch).attempt,
            IdeEndpointStartup.Prepared(prepared),
        )
        assertTrue(activation is IdeEndpointCompletionPlan.Activate)
        val endpoint = when (
            val plan = coordinator.activate(
                (activation as IdeEndpointCompletionPlan.Activate).request,
            )
        ) {
            is IdeEndpointActivationPlan.Serve -> plan.endpoint
            is IdeEndpointActivationPlan.Retired -> fail("coordinator retired before serving")
            IdeEndpointActivationPlan.Stop -> fail("coordinator stopped before serving")
        }
        val socketPath = Path.of(endpoint.location.socketPath.value)
        val descriptorPath = Path.of(endpoint.location.descriptorPath.value)
        try {
            assertTrue(Files.exists(socketPath))
            assertTrue(Files.isRegularFile(descriptorPath))
            val served = CompletableFuture.supplyAsync {
                runSuspend { endpoint.serveNext() }
            }
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                assertTrue(client.connect(UnixDomainSocketAddress.of(socketPath)))
                val request = when (
                    val encoded = CanonicalOperationWireBindings.workspaceInspect.encodeRequest(
                        WorkspaceInspectRequest,
                    )
                ) {
                    is WireEncoding.Encoded -> encoded.document
                    is WireEncoding.Rejected -> fail("workspace request rejected: ${encoded.failure}")
                }
                writeFrame(client, request)
                val response = readFrame(client)
                val decoded = when (
                    val outcome = CanonicalOperationWireBindings.workspaceInspect.decodeOutcome(
                        response,
                    )
                ) {
                    is WireDecoding.Decoded -> outcome.value
                    is WireDecoding.Rejected -> fail(
                        "workspace response rejected: ${outcome.failure}",
                    )
                }
                assertEquals(workspaceOutcome, decoded)
            }
            assertEquals(
                IdeEndpointConnectionHandling.Served,
                served.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, workspaceCalls)
            val admitted = when (
                val admission = IdeEndpointDescriptorV2.admit(
                    Files.readString(descriptorPath),
                    policy,
                )
            ) {
                is IdeEndpointDescriptorAdmission.Admitted -> admission.descriptor
                is IdeEndpointDescriptorAdmission.Rejected -> fail(
                    "published descriptor rejected: ${admission.failure}",
                )
            }
            assertEquals(root.value, admitted.canonicalRoot.value)
            assertEquals(ProcessHandle.current().pid(), admitted.processId.value)
            assertEquals(7, admitted.runtimeEpoch.value)
            assertEquals(socketPath.toString(), admitted.socketPath.value)
            assertEquals(
                compatibility.capabilities.capabilities.map { it.operation.id.value },
                admitted.compatibility.capabilities.capabilities.map { it.operation.id.value },
            )
        } finally {
            endpoint.retire(IdeEndpointRetirementCause.TEST_CLEANUP)
            assertFalse(Files.exists(descriptorPath))
            assertFalse(Files.exists(socketPath))
            Files.deleteIfExists(directory)
        }
    }
}

private fun writeFrame(channel: SocketChannel, document: String) {
    val payload = document.toByteArray(StandardCharsets.UTF_8)
    val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
        .putInt(payload.size)
        .put(payload)
        .flip()
    while (frame.hasRemaining()) channel.write(frame)
}

private fun readFrame(channel: SocketChannel): String {
    val header = ByteBuffer.allocate(Int.SIZE_BYTES)
    readCompletely(channel, header)
    header.flip()
    val payload = ByteBuffer.allocate(header.int)
    readCompletely(channel, payload)
    payload.flip()
    return StandardCharsets.UTF_8.decode(payload).toString()
}

private fun readCompletely(channel: SocketChannel, buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) throw AssertionError("framed response ended early")
    }
}

private fun <Value> runSuspend(block: suspend () -> Value): Value {
    var completion: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completion = result
            }
        },
    )
    while (completion == null) Thread.onSpinWait()
    return checkNotNull(completion).getOrThrow()
}

private fun positiveCompatibilityCandidate() = IdeHostCompatibilityCandidate(
    ideBuild = "262.9437.185",
    kotlinPluginBuild = "262.9437.185-IJ",
    kastPluginVersion = "1.2.3",
    runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
    operationRegistryDigest = "sha256:" + "1".repeat(64),
    wireSchemaDigest = "sha256:" + "2".repeat(64),
    capabilities = io.github.amichne.kast.protocol.wire.metadata.CanonicalHostedCapabilities
        .candidates
        .map { it.operationId },
)

private fun <Value, Failure> positiveRefined(result: Refinement<Value, Failure>): Value =
    when (result) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> fail("fixture refinement rejected: ${result.failure}")
    }

private fun IdeEndpointPreparation.positivePrepared() = when (this) {
    is IdeEndpointPreparation.Prepared -> endpoint
    is IdeEndpointPreparation.Rejected -> fail("endpoint preparation rejected: $failure")
}
