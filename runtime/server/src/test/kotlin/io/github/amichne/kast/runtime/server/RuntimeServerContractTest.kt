package io.github.amichne.kast.runtime.server

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
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
import io.github.amichne.kast.protocol.wire.WireFailure
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeServerContractTest {
    @Test
    fun `binding table admits exactly one handler for every canonical operation`() {
        val bindings = canonicalBindings()

        RuntimeServer.create(bindings).createdServer()
        assertEquals(
            RuntimeServerConstruction.Rejected(
                setOf(
                    RuntimeServerConstructionFailure.MissingBinding(
                        CanonicalOperation.CHANGE_RECOVER,
                    ),
                ),
            ),
            RuntimeServer.create(bindings.dropLast(1)),
        )
        assertEquals(
            RuntimeServerConstruction.Rejected(
                setOf(
                    RuntimeServerConstructionFailure.DuplicateBinding(
                        CanonicalOperation.WORKSPACE_INSPECT,
                    ),
                ),
            ),
            RuntimeServer.create(bindings + bindings.first()),
        )
    }

    @Test
    fun `fake dispatch proves every outcome for all canonical operations`() = runTest {
        val bindings = canonicalBindings()
        val server = RuntimeServer.create(bindings).createdServer()

        bindings.forEach { binding ->
            TestOutcomeKind.entries.forEach { outcomeKind ->
                val request = TestRequest(outcomeKind)
                val requestDocument = binding.wireBinding.encodeRequest(request).encodedDocument()
                val responseDocument = server.dispatch(requestDocument).responseDocument()
                val observed = binding.wireBinding.decodeOutcome(responseDocument).decodedValue()

                assertEquals(expectedOutcome(binding.operation, outcomeKind), observed)
            }
        }
    }

    @Test
    fun `dispatch fails closed for unknown operations and mismatched schemas`() = runTest {
        val bindings = canonicalBindings()
        val binding = bindings.first()
        val server = RuntimeServer.create(bindings).createdServer()
        val encoded = binding.wireBinding
            .encodeRequest(TestRequest(TestOutcomeKind.COMPLETE))
            .encodedDocument()
        val unknownOperation = "symbol.missing"
        val unknownOperationDocument = encoded.replace(
            "\"operation\":\"${binding.operation.id.value}\"",
            "\"operation\":\"$unknownOperation\"",
        )
        assertEquals(
            ServerDispatch.Rejected(
                ServerDispatchFailure.RequestAdmissionFailed(
                    WireFailure.UnknownOperation(operationId(unknownOperation)),
                ),
            ),
            server.dispatch(unknownOperationDocument),
        )

        val unknownSchema = schemaIdentity("kast.unknown.v1")
        val unknownSchemaDocument = encoded.replace(binding.wireBinding.schema.value, unknownSchema.value)
        assertEquals(
            ServerDispatch.Rejected(
                ServerDispatchFailure.RequestDecodingFailed(
                    operation = binding.operation,
                    failure = WireFailure.UnknownSchema(unknownSchema),
                ),
            ),
            server.dispatch(unknownSchemaDocument),
        )
    }

    private fun canonicalBindings(): List<
        TypedOperationBinding<TestRequest, TestResult, TestQualification, TestRejection>,
        > = CanonicalOperation.entries.map { operation ->
        val wireBinding = OperationWireBinding(
            definition = definition(operation),
            serializers = GeneratedOperationSerializers(
                request = TestRequest.serializer(),
                result = TestResult.serializer(),
                qualification = TestQualification.serializer(),
                rejection = TestRejection.serializer(),
            ),
        )
        TypedOperationBinding(
            wireBinding = wireBinding,
            handler = OperationHandler { request ->
                expectedOutcome(operation, request.outcome)
            },
        )
    }

    private fun definition(
        operation: CanonicalOperation,
    ): OperationDefinition<TestRequest, TestResult, TestCapability, TestQualification, TestRejection> =
        OperationDefinition(
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
            scope = OperationScope.SYMBOL,
            budget = resourceBudget(),
            completeness = CompletenessPolicy.QUALIFIED_ALLOWED,
        )

    private fun expectedOutcome(
        operation: CanonicalOperation,
        outcomeKind: TestOutcomeKind,
    ): OperationOutcome<TestResult, TestQualification, TestRejection> {
        val evidence = EvidenceEnvelope(
            operation = operation.id,
            generation = EvidenceGeneration.parse(17).refinedValue(),
            payload = TestResult("result:${operation.id.value}"),
        )
        return when (outcomeKind) {
            TestOutcomeKind.COMPLETE -> OperationOutcome.Complete(evidence)
            TestOutcomeKind.QUALIFIED ->
                OperationOutcome.Qualified(evidence, TestQualification.TRUNCATED)
            TestOutcomeKind.REJECTED -> OperationOutcome.Rejected(TestRejection.BLOCKED)
        }
    }

    private fun resourceBudget(): ResourceBudget = ResourceBudget(
        resultLimit = ResultLimit.parse(250).refinedValue(),
        workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue(),
        elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue(),
    )

    private fun capabilityId(raw: String): CapabilityId = CapabilityId.parse(raw).refinedValue()

    private fun operationId(raw: String): OperationId = OperationId.parse(raw).refinedValue()

    private fun schemaIdentity(raw: String): SchemaIdentity = SchemaIdentity.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }

    private fun ServerDispatch.responseDocument(): String = when (this) {
        is ServerDispatch.Responded -> document
        is ServerDispatch.Rejected -> error("Expected response, got $failure")
    }

    private fun <Value> WireDecoding<Value>.decodedValue(): Value = when (this) {
        is WireDecoding.Decoded -> value
        is WireDecoding.Rejected -> error("Expected decoded value, got $failure")
    }

    private fun RuntimeServerConstruction.createdServer(): RuntimeServer = when (this) {
        is RuntimeServerConstruction.Created -> server
        is RuntimeServerConstruction.Rejected -> error("Expected server, got $failures")
    }

    @Serializable
    private data class TestRequest(
        val outcome: TestOutcomeKind,
    ) : OperationRequest

    @Serializable
    private data class TestResult(
        val value: String,
    ) : OperationResult

    private data class TestCapability(
        override val id: CapabilityId,
    ) : CapabilityMarker

    @Serializable
    private enum class TestOutcomeKind {
        COMPLETE,
        QUALIFIED,
        REJECTED,
    }

    @Serializable
    private enum class TestQualification : OperationQualification {
        TRUNCATED,
    }

    @Serializable
    private enum class TestRejection : OperationRejection {
        BLOCKED,
    }
}
