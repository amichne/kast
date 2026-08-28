package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OperationRegistryContractTest {
    @Test
    fun `operation lanes retain the seven admitted authority classes`() {
        assertEquals(
            listOf(
                OperationLane.METADATA,
                OperationLane.INDEX_LOOKUP,
                OperationLane.SCOPED_SEMANTIC_READ,
                OperationLane.BOUNDED_RELATION_READ,
                OperationLane.REGISTERED_LONG_WORK,
                OperationLane.DERIVED_WRITE,
                OperationLane.SOURCE_WRITE,
            ),
            OperationLane.entries,
        )
    }

    @Test
    fun `canonical public operation set is exact and ordered`() {
        assertEquals(
            listOf(
                "workspace.inspect",
                "topology.build",
                "symbol.discover",
                "symbol.resolve",
                "symbol.describe",
                "relation.read",
                "traversal.run",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.verify",
                "change.recover",
            ),
            CanonicalOperation.entries.map { it.id.value },
        )
    }

    @Test
    fun `every canonical operation carries one complete typed metadata binding`() {
        val definitions = canonicalDefinitions()
        val registry = OperationRegistry.create(definitions).createdRegistry()

        assertEquals(CanonicalOperation.entries, registry.definitions.map { it.operation })
        definitions.forEach { definition ->
            assertEquals(TestRequest::class, definition.requestType)
            assertEquals(TestResult::class, definition.resultType)
            assertEquals(TestQualification::class, definition.qualificationType)
            assertEquals(TestRejection::class, definition.rejectionType)
            assertEquals(TestCapability::class, definition.capabilityType)
            assertEquals(capabilityId("semantic.read"), definition.requiredCapability)
            assertEquals(schemaIdentity("kast.${definition.id.value}.v1"), definition.schema)
        }
    }

    @Test
    fun `registry rejects missing duplicate unknown and untyped metadata as closed failures`() {
        val definitions = canonicalDefinitions()
        val missing = CanonicalOperation.CHANGE_RECOVER

        assertEquals(
            OperationRegistryConstruction.Rejected(
                setOf(OperationRegistryFailure.MissingOperationId(missing.id)),
            ),
            OperationRegistry.create(definitions.filterNot { it.operation == missing }),
        )

        val duplicate = definitions.first()
        assertEquals(
            OperationRegistryConstruction.Rejected(
                setOf(OperationRegistryFailure.DuplicateOperationId(duplicate.id)),
            ),
            OperationRegistry.create(definitions + duplicate),
        )

        val unknown = RawMetadata(operationId("symbol.missing"))
        assertEquals(
            OperationRegistryConstruction.Rejected(
                setOf(OperationRegistryFailure.UnknownOperationId(unknown.id)),
            ),
            OperationRegistry.create(definitions + unknown),
        )

        val untypedOperation = CanonicalOperation.SYMBOL_DISCOVER
        val withoutTypedDefinition = definitions.filterNot { it.operation == untypedOperation }
        assertEquals(
            OperationRegistryConstruction.Rejected(
                setOf(
                    OperationRegistryFailure.UntypedOperationMetadata(untypedOperation.id),
                    OperationRegistryFailure.MissingOperationId(untypedOperation.id),
                ),
            ),
            OperationRegistry.create(withoutTypedDefinition + RawMetadata(untypedOperation.id)),
        )
    }

    @Test
    fun `lookup and outcome binding preserve canonical identity`() {
        val definition = definition(CanonicalOperation.SYMBOL_DISCOVER)
        val registry = OperationRegistry.create(canonicalDefinitions()).createdRegistry()
        assertEquals(OperationLookup.Found(definition), registry.lookup(definition.id))
        assertEquals(
            OperationLookup.Unknown(operationId("symbol.missing")),
            registry.lookup(operationId("symbol.missing")),
        )

        val evidence = EvidenceEnvelope(
            operation = definition.id,
            generation = EvidenceGeneration.parse(9).refinedValue(),
            payload = TestResult("found"),
        )
        assertEquals(
            OperationOutcomeBinding.Bound(
                io.github.amichne.kast.kernel.OperationOutcome.Complete(evidence),
            ),
            definition.bindComplete(evidence),
        )
        assertEquals(
            OperationOutcomeBinding.Bound(
                io.github.amichne.kast.kernel.OperationOutcome.Qualified(
                    evidence,
                    TestQualification.TRUNCATED,
                ),
            ),
            definition.bindQualified(evidence, TestQualification.TRUNCATED),
        )

        val completeOnly = definition(
            CanonicalOperation.SYMBOL_DISCOVER,
            CompletenessPolicy.COMPLETE_REQUIRED,
        )
        assertEquals(
            OperationOutcomeBinding.Rejected(
                OperationOutcomeBindingFailure.QualificationNotAllowed(completeOnly.id),
            ),
            completeOnly.bindQualified(evidence, TestQualification.TRUNCATED),
        )

        val mismatched = evidence.copy(operation = CanonicalOperation.SYMBOL_DESCRIBE.id)
        assertEquals(
            OperationOutcomeBinding.Rejected(
                OperationOutcomeBindingFailure.EvidenceOperationMismatch(
                    expected = definition.id,
                    observed = mismatched.operation,
                ),
            ),
            definition.bindComplete(mismatched),
        )
        assertEquals(
            io.github.amichne.kast.kernel.OperationOutcome.Rejected(TestRejection.BLOCKED),
            definition.reject(TestRejection.BLOCKED),
        )
    }

    @Test
    fun `stronger prerequisite remains blocker data rather than execution authority`() {
        val required = CanonicalOperation.WORKSPACE_INSPECT.id
        val blocker: OperationBlocker = OperationBlocker.StrongerOperationRequired(required)

        val recorded = when (blocker) {
            is OperationBlocker.BudgetUnavailable -> error("Unexpected blocker: $blocker")
            is OperationBlocker.CapabilityUnavailable -> error("Unexpected blocker: $blocker")
            is OperationBlocker.ScopeUnavailable -> error("Unexpected blocker: $blocker")
            is OperationBlocker.StrongerOperationRequired -> blocker.requiredOperation
        }

        assertEquals(required, recorded)
    }

    private fun canonicalDefinitions(): List<OperationDefinition<*, *, *, *, *>> =
        CanonicalOperation.entries.map(::definition)

    private fun definition(
        operation: CanonicalOperation,
        completeness: CompletenessPolicy = CompletenessPolicy.QUALIFIED_ALLOWED,
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
            completeness = completeness,
            hostedExposure = HostedExposure.UNAVAILABLE,
        )

    private fun resourceBudget(): ResourceBudget = ResourceBudget(
        resultLimit = ResultLimit.parse(250).refinedValue(),
        workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue(),
        elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue(),
    )

    private fun operationId(raw: String): OperationId = OperationId.parse(raw).refinedValue()

    private fun capabilityId(raw: String): CapabilityId = CapabilityId.parse(raw).refinedValue()

    private fun schemaIdentity(raw: String): SchemaIdentity = SchemaIdentity.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private data class RawMetadata(
        override val id: OperationId,
    ) : OperationMetadata

    private data class TestRequest(
        val query: String,
    ) : OperationRequest

    private data class TestResult(
        val value: String,
    ) : OperationResult

    private data class TestCapability(
        override val id: CapabilityId,
    ) : CapabilityMarker

    private enum class TestQualification : OperationQualification {
        TRUNCATED,
    }

    private enum class TestRejection : OperationRejection {
        BLOCKED,
    }

    private fun OperationRegistryConstruction.createdRegistry(): OperationRegistry = when (this) {
        is OperationRegistryConstruction.Created -> registry
        is OperationRegistryConstruction.Rejected -> error("Expected registry, got $failures")
    }
}
