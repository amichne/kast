package io.github.amichne.kast.protocol.wire

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
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OperationWireContractTest {
    @Test
    fun `generated serializer table is exact over all canonical operations`() {
        val bindings = canonicalBindings()
        val table = OperationWireTable.create(bindings).createdTable()

        assertEquals(CanonicalOperation.entries, table.bindings.map { it.operation })
        assertEquals(
            OperationWireTableConstruction.Rejected(
                setOf(
                    OperationWireTableFailure.MissingSerializerBinding(
                        CanonicalOperation.CHANGE_RECOVER,
                    ),
                ),
            ),
            OperationWireTable.create(bindings.dropLast(1)),
        )
        assertEquals(
            OperationWireTableConstruction.Rejected(
                setOf(
                    OperationWireTableFailure.DuplicateSerializerBinding(
                        CanonicalOperation.WORKSPACE_INSPECT,
                    ),
                ),
            ),
            OperationWireTable.create(bindings + bindings.first()),
        )
    }

    @Test
    fun `all operations round trip typed request and every outcome through one envelope`() {
        canonicalBindings().forEach { binding ->
            val request = TestRequest("request:${binding.operation.id.value}")
            val encodedRequest = binding.encodeRequest(request).encodedDocument()
            assertEquals(WireDecoding.Decoded(request), binding.decodeRequest(encodedRequest))

            val evidence = EvidenceEnvelope(
                operation = binding.operation.id,
                generation = EvidenceGeneration.parse(17).refinedValue(),
                payload = TestResult("result:${binding.operation.id.value}"),
            )
            val outcomes = listOf(
                OperationOutcome.Complete(evidence),
                OperationOutcome.Qualified(evidence, TestQualification.TRUNCATED),
                OperationOutcome.Rejected(TestRejection.BLOCKED),
            )
            outcomes.forEach { outcome ->
                val encodedOutcome = binding.encodeOutcome(outcome).encodedDocument()
                assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(encodedOutcome))
            }
        }
    }

    @Test
    fun `unknown schema and unknown operation reject as closed wire failures`() {
        val binding = canonicalBindings().first()
        val encoded = binding.encodeRequest(TestRequest("request")).encodedDocument()
        val unknownSchema = schemaIdentity("kast.unknown.v1")
        val withUnknownSchema = encoded.replace(binding.schema.value, unknownSchema.value)
        assertEquals(
            WireDecoding.Rejected(WireFailure.UnknownSchema(unknownSchema)),
            binding.decodeRequest(withUnknownSchema),
        )

        val unknownOperation = "symbol.missing"
        val withUnknownOperation = encoded.replace(
            "\"operation\":\"${binding.operation.id.value}\"",
            "\"operation\":\"$unknownOperation\"",
        )
        assertEquals(
            WireDecoding.Rejected(
                WireFailure.UnknownOperation(operationId = operationId(unknownOperation)),
            ),
            binding.decodeRequest(withUnknownOperation),
        )
    }

    private fun canonicalBindings(): List<
        OperationWireBinding<TestRequest, TestResult, TestQualification, TestRejection>,
        > = CanonicalOperation.entries.map { operation ->
        val definition = OperationDefinition(
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
        OperationWireBinding(
            definition = definition,
            serializers = GeneratedOperationSerializers(
                request = TestRequest.serializer(),
                result = TestResult.serializer(),
                qualification = TestQualification.serializer(),
                rejection = TestRejection.serializer(),
            ),
        )
    }

    private fun resourceBudget(): ResourceBudget = ResourceBudget(
        resultLimit = ResultLimit.parse(250).refinedValue(),
        workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue(),
        elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue(),
    )

    private fun capabilityId(raw: String): CapabilityId = CapabilityId.parse(raw).refinedValue()

    private fun operationId(raw: String) =
        io.github.amichne.kast.kernel.OperationId.parse(raw).refinedValue()

    private fun schemaIdentity(raw: String): SchemaIdentity = SchemaIdentity.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }

    @Serializable
    private data class TestRequest(
        val query: String,
    ) : OperationRequest

    @Serializable
    private data class TestResult(
        val value: String,
    ) : OperationResult

    private data class TestCapability(
        override val id: CapabilityId,
    ) : CapabilityMarker

    @Serializable
    private enum class TestQualification : OperationQualification {
        TRUNCATED,
    }

    @Serializable
    private enum class TestRejection : OperationRejection {
        BLOCKED,
    }

    private fun OperationWireTableConstruction.createdTable(): OperationWireTable = when (this) {
        is OperationWireTableConstruction.Created -> table
        is OperationWireTableConstruction.Rejected -> error("Expected table, got $failures")
    }
}
