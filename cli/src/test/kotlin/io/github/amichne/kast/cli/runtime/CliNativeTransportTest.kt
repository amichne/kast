package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("native")
class CliNativeTransportTest {
    @Test
    fun `terminal startup rejection returns generated runtime boundary without wire exchange`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val canonicalRoot = FilesystemCanonicalRootDiscovery.discover(root).discoveredRoot()
        val runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refinedValue()
        val endpoint = RuntimeEndpoint.at(
            canonicalRoot,
            runtimeId,
            temporary.resolve("runtime.sock"),
        ).resolvedEndpoint()
        var wireInvoked = false
        val cli = KastCli(
            commandGraphFactory = commandGraphFactory(),
            rootDiscovery = FilesystemCanonicalRootDiscovery,
            endpointLocator = RuntimeEndpointLocator {
                RuntimeEndpointResolution.Resolved(endpoint)
            },
            runtimeDemander = RuntimeDemander { _, _ ->
                RuntimeAdmission.Rejected(RuntimeAdmissionFailure.SessionEndedBeforeReady)
            },
            wireClient = WireClient { _, _ ->
                wireInvoked = true
                error("wire exchange must not run")
            },
            localMetadata = testLocalMetadata(),
            lifecycle = ExactRootRuntimeLifecycle(),
            productInspector = ProductInspector { error("product inspection must not run") },
        )

        val exit = cli.execute(listOf("workspace", "inspect"), root)

        val rejected = assertInstanceOf(CliExit.BoundaryRejected::class.java, exit)
        assertEquals(CliBoundaryExitStatus.RUNTIME, rejected.status)
        assertEquals(
            "{\"status\":\"rejected\",\"boundary\":\"runtime\"," +
                "\"reason\":\"session-ended-before-ready\"}",
            rejected.document.value,
        )
        assertEquals(false, wireInvoked)
    }

    @Test
    fun `workspace inspect traverses exact root UDS and typed wire`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val nested = Files.createDirectories(root.resolve("module"))
        val canonicalRoot = FilesystemCanonicalRootDiscovery.discover(root).discoveredRoot()
        val socket = Path.of("/tmp/kast-cli-${System.nanoTime()}.sock")
        val runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refinedValue()
        val endpoint = RuntimeEndpoint.at(canonicalRoot, runtimeId, socket).resolvedEndpoint()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val executor = Executors.newSingleThreadExecutor()
        try {
            Files.deleteIfExists(socket)
            server.bind(UnixDomainSocketAddress.of(socket))
            val served = executor.submit {
                server.accept().use { channel ->
                    val requestDocument = WireFrameCodec.read(channel).receivedDocument()
                    val binding = CanonicalOperationWireBindings.workspaceInspect
                    val request = WireRequestEnvelope.admit(requestDocument).admittedRequest()
                    assertEquals(
                        WireDecoding.Decoded(WorkspaceInspectRequest),
                        binding.decodeRequest(request),
                    )
                    val outcome = OperationOutcome.Complete(
                        EvidenceEnvelope(
                            operation = binding.operation.id,
                            generation = EvidenceGeneration.parse(17).refinedValue(),
                            payload = WorkspaceInspectResult(
                                ProtocolText.parse(canonicalRoot.path.toString()).refinedValue(),
                                WorkspaceStateDocument.READY,
                            ),
                        ),
                    )
                    assertEquals(
                        WireFrameWrite.Written,
                        WireFrameCodec.write(
                            channel,
                            binding.encodeOutcome(outcome).encodedDocument(),
                        ),
                    )
                }
            }
            val cli = KastCli(
                commandGraphFactory = commandGraphFactory(),
                rootDiscovery = FilesystemCanonicalRootDiscovery,
                endpointLocator = RuntimeEndpointLocator { discovered ->
                    if (discovered == canonicalRoot) {
                        RuntimeEndpointResolution.Resolved(endpoint)
                    } else {
                        RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.ROOT_MISMATCH)
                    }
                },
                runtimeDemander = RuntimeDemander { discovered, requestedEndpoint ->
                    if (discovered == canonicalRoot && requestedEndpoint == endpoint) {
                        RuntimeAdmission.Ready(endpoint)
                    } else {
                        RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
                    }
                },
                wireClient = UnixDomainWireClient(),
                localMetadata = testLocalMetadata(),
                lifecycle = ExactRootRuntimeLifecycle(),
                productInspector = ProductInspector { error("product inspection must not run") },
            )

            val exit = cli.execute(listOf("workspace", "inspect"), nested)

            val complete = exit as CliExit.Complete
            assertEquals(0, complete.code)
            assertEquals(
                "{\"operation\":\"workspace.inspect\",\"status\":\"complete\"," +
                    "\"canonicalRoot\":\"${canonicalRoot.path}\",\"state\":\"ready\"}",
                complete.document.value,
            )
            served.get(10, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            Files.deleteIfExists(socket)
        }
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
    }

    private fun testLocalMetadata(): CliLocalMetadata = when (
        val admitted = CliLocalMetadata.admit(
            "test",
            "{\"schemaVersion\":1}",
        )
    ) {
        is CliLocalMetadataAdmission.Admitted -> admitted.metadata
        is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }

    private fun WireRequestAdmission.admittedRequest() = when (this) {
        is WireRequestAdmission.Admitted -> request
        is WireRequestAdmission.Rejected -> error("Expected admitted request, got $failure")
    }

    private fun CanonicalRootDiscovery.discoveredRoot(): CanonicalRoot = when (this) {
        is CanonicalRootDiscovery.Discovered -> root
        is CanonicalRootDiscovery.Rejected -> error("Expected root, got $failure")
    }

    private fun RuntimeEndpointResolution.resolvedEndpoint(): RuntimeEndpoint = when (this) {
        is RuntimeEndpointResolution.Resolved -> endpoint
        is RuntimeEndpointResolution.Rejected -> error("Expected endpoint, got $failure")
    }

    private fun WireFrameRead.receivedDocument(): String = when (this) {
        is WireFrameRead.Received -> document
        is WireFrameRead.Rejected -> error("Expected frame, got $failure")
    }
}
