package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.OperationTypeBinding
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.registry.CompletenessPolicy
import io.github.amichne.kast.protocol.registry.OperationCost
import io.github.amichne.kast.protocol.registry.OperationDefinition
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.OperationLane
import io.github.amichne.kast.protocol.registry.OperationScope
import io.github.amichne.kast.protocol.wire.GeneratedOperationSerializers
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `workspace inspect traverses exact root UDS and typed wire`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val nested = Files.createDirectories(root.resolve("module"))
        val canonicalRoot = FilesystemCanonicalRootDiscovery.discover(root).discoveredRoot()
        val socket = Path.of("/tmp/kast-cli-${System.nanoTime()}.sock")
        val runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refinedValue()
        val endpoint = RuntimeEndpoint.at(canonicalRoot, runtimeId, socket).resolvedEndpoint()
        val projections = CliProjectionTable.create(canonicalProjections()).createdTable()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val executor = Executors.newSingleThreadExecutor()
        try {
            Files.deleteIfExists(socket)
            server.bind(UnixDomainSocketAddress.of(socket))
            val served = executor.submit {
                server.accept().use { channel ->
                    val requestDocument = WireFrameCodec.read(channel).receivedDocument()
                    val binding = wireBinding(CanonicalOperation.WORKSPACE_INSPECT)
                    val request = WireRequestEnvelope.admit(requestDocument).admittedRequest()
                    assertEquals(
                        WireDecoding.Decoded(TestRequest),
                        binding.decodeRequest(request),
                    )
                    val outcome = OperationOutcome.Complete(
                        EvidenceEnvelope(
                            operation = CanonicalOperation.WORKSPACE_INSPECT.id,
                            generation = EvidenceGeneration.parse(17).refinedValue(),
                            payload = TestResult("ready"),
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
                projections = projections,
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
                        RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE)
                    }
                },
                wireClient = UnixDomainWireClient(),
                localMetadata = testLocalMetadata(),
            )

            val exit = cli.execute(listOf("workspace", "inspect"), nested)

            val complete = exit as CliExit.Complete
            assertEquals(0, complete.code)
            assertEquals(
                "{\"operation\":\"workspace.inspect\",\"status\":\"complete\",\"value\":\"ready\"}",
                complete.document.value,
            )
            served.get(10, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            Files.deleteIfExists(socket)
        }
    }

    private fun canonicalProjections(): List<
        TypedCliProjection<TestRequest, TestResult, TestQualification, TestRejection>,
        > = CanonicalOperation.entries.map { operation ->
        TypedCliProjection(
            wireBinding = wireBinding(operation),
            requestParser = CliRequestParser { arguments ->
                if (arguments.values.isEmpty()) {
                    CliRequestParsing.Parsed(TestRequest)
                } else {
                    CliRequestParsing.Rejected
                }
            },
            outcomeProjector = CliOutcomeProjector { outcome ->
                val status = when (outcome) {
                    is OperationOutcome.Complete -> "complete"
                    is OperationOutcome.Qualified -> "qualified"
                    is OperationOutcome.Rejected -> "rejected"
                }
                val value = when (outcome) {
                    is OperationOutcome.Complete -> outcome.evidence.payload.value
                    is OperationOutcome.Qualified -> outcome.evidence.payload.value
                    is OperationOutcome.Rejected -> outcome.reason.name.lowercase()
                }
                val document = CliJsonDocument.from(
                    buildJsonObject {
                        put("operation", operation.id.value)
                        put("status", status)
                        put("value", value)
                    },
                )
                when (outcome) {
                    is OperationOutcome.Complete -> ProjectedCliOutcome.Complete(document)
                    is OperationOutcome.Qualified -> ProjectedCliOutcome.Qualified(document)
                    is OperationOutcome.Rejected -> ProjectedCliOutcome.Rejected(document)
                }
            },
        )
    }

    private fun wireBinding(
        operation: CanonicalOperation,
    ): OperationWireBinding<TestRequest, TestResult, TestQualification, TestRejection> =
        OperationWireBinding(
            definition = OperationDefinition(
                operation = operation,
                types = OperationTypeBinding(
                    requestType = TestRequest::class,
                    resultType = TestResult::class,
                    qualificationType = TestQualification::class,
                    rejectionType = TestRejection::class,
                    schema = schemaIdentity("kast.${operation.id.value}.v1"),
                ),
                requiredCapability = capabilityId("semantic.read"),
                capabilityType = TestCapability::class,
                lane = OperationLane.INDEX_LOOKUP,
                effect = OperationEffect.INTELLIJ_READ,
                cost = OperationCost.BOUNDED_READ,
                scope = OperationScope.WORKSPACE,
                budget = ResourceBudget(
                    resultLimit = ResultLimit.parse(250).refinedValue(),
                    workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue(),
                    elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue(),
                ),
                completeness = CompletenessPolicy.QUALIFIED_ALLOWED,
            ),
            serializers = GeneratedOperationSerializers(
                request = TestRequest.serializer(),
                result = TestResult.serializer(),
                qualification = TestQualification.serializer(),
                rejection = TestRejection.serializer(),
            ),
        )

    private fun capabilityId(raw: String): CapabilityId = CapabilityId.parse(raw).refinedValue()

    private fun schemaIdentity(raw: String): SchemaIdentity = SchemaIdentity.parse(raw).refinedValue()

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

    private fun testLocalMetadata(): CliLocalMetadata = when (
        val admitted = CliLocalMetadata.admit(
            "test",
            "sha256:${"a".repeat(64)}",
            "{\"schemaVersion\":1}",
        )
    ) {
        is CliLocalMetadataAdmission.Admitted -> admitted.metadata
        is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
    }

    private fun CliProjectionTableConstruction.createdTable(): CliProjectionTable = when (this) {
        is CliProjectionTableConstruction.Created -> table
        is CliProjectionTableConstruction.Rejected -> error("Expected table, got $failures")
    }

    private fun WireFrameRead.receivedDocument(): String = when (this) {
        is WireFrameRead.Received -> document
        is WireFrameRead.Rejected -> error("Expected frame, got $failure")
    }

    @Serializable
    private data object TestRequest : OperationRequest

    @Serializable
    private data class TestResult(
        val value: String,
    ) : OperationResult

    private data class TestCapability(
        override val id: CapabilityId,
    ) : CapabilityMarker

    @Serializable
    private enum class TestQualification : OperationQualification {
        PARTIAL,
    }

    @Serializable
    private enum class TestRejection : OperationRejection {
        BLOCKED,
    }
}
