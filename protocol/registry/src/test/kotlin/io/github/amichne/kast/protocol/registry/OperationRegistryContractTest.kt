package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OperationRegistryContractTest {
    @Test
    fun `definition requires typed identity capability classification and budget`() {
        val definition = definition()

        assertEquals(operationId("symbol.discover"), definition.id)
        assertEquals(DiscoverRequest::class, definition.requestType)
        assertEquals(DiscoverPayload::class, definition.resultType)
        assertEquals(DiscoverQualification::class, definition.qualificationType)
        assertEquals(DiscoverRejection::class, definition.rejectionType)
        assertEquals(capabilityId("symbol.read"), definition.requiredCapability)
        assertEquals(OperationEffect.INTELLIJ_READ, definition.effect)
        assertEquals(OperationCost.BOUNDED_READ, definition.cost)
        assertEquals(OperationScope.SYMBOL, definition.scope)
        assertEquals(resourceBudget(), definition.budget)
        assertEquals(CompletenessPolicy.QUALIFIED_ALLOWED, definition.completeness)
    }

    @Test
    fun `definition binds only matching generation evidence to semantic outcomes`() {
        val definition = definition()
        val evidence = EvidenceEnvelope(
            operation = definition.id,
            generation = EvidenceGeneration.parse(9).refinedValue(),
            payload = DiscoverPayload(listOf("io.github.Example")),
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
                    DiscoverQualification.Truncated,
                ),
            ),
            definition.bindQualified(evidence, DiscoverQualification.Truncated),
        )

        val completeOnly = definition(completeness = CompletenessPolicy.COMPLETE_REQUIRED)
        assertEquals(
            OperationOutcomeBinding.Rejected(
                OperationOutcomeBindingFailure.QualificationNotAllowed(completeOnly.id),
            ),
            completeOnly.bindQualified(evidence, DiscoverQualification.Truncated),
        )

        val mismatched = evidence.copy(operation = operationId("file.describe"))
        assertEquals(
            OperationOutcomeBinding.Rejected(
                OperationOutcomeBindingFailure.EvidenceOperationMismatch(
                    expected = definition.id,
                    observed = mismatched.operation,
                ),
            ),
            definition.bindComplete(mismatched),
        )

        val rejection = DiscoverRejection.Blocked(
            OperationBlocker.CapabilityUnavailable(definition.requiredCapability),
        )
        assertEquals(
            io.github.amichne.kast.kernel.OperationOutcome.Rejected(rejection),
            definition.reject(rejection),
        )
    }

    @Test
    fun `registry construction and lookup are closed deterministic transitions`() {
        val discover = definition()
        val describe = definition("symbol.describe")
        val registry = OperationRegistry.create(listOf(describe, discover)).createdRegistry()

        assertEquals(listOf(describe, discover), registry.definitions)
        assertEquals(OperationLookup.Found(discover), registry.lookup(discover.id))
        assertEquals(
            OperationLookup.Missing(operationId("symbol.missing")),
            registry.lookup(operationId("symbol.missing")),
        )
        assertEquals(
            OperationRegistryConstruction.Rejected(
                setOf(OperationRegistryFailure.DuplicateOperationId(discover.id)),
            ),
            OperationRegistry.create(listOf(discover, discover)),
        )
    }

    @Test
    fun `stronger prerequisite remains blocker data rather than execution authority`() {
        val required = operationId("workspace.qualify")
        val blocker: OperationBlocker = OperationBlocker.StrongerOperationRequired(required)

        val recorded = when (blocker) {
            is OperationBlocker.BudgetUnavailable -> error("Unexpected blocker: $blocker")
            is OperationBlocker.CapabilityUnavailable -> error("Unexpected blocker: $blocker")
            is OperationBlocker.ScopeUnavailable -> error("Unexpected blocker: $blocker")
            is OperationBlocker.StrongerOperationRequired -> blocker.requiredOperation
        }

        assertEquals(required, recorded)
    }

    private fun definition(
        idRaw: String = "symbol.discover",
        completeness: CompletenessPolicy = CompletenessPolicy.QUALIFIED_ALLOWED,
    ): OperationDefinition<
        DiscoverRequest,
        DiscoverPayload,
        DiscoverQualification,
        DiscoverRejection,
        > = OperationDefinition(
        id = operationId(idRaw),
        requestType = DiscoverRequest::class,
        resultType = DiscoverPayload::class,
        qualificationType = DiscoverQualification::class,
        rejectionType = DiscoverRejection::class,
        requiredCapability = capabilityId("symbol.read"),
        effect = OperationEffect.INTELLIJ_READ,
        cost = OperationCost.BOUNDED_READ,
        scope = OperationScope.SYMBOL,
        budget = resourceBudget(),
        completeness = completeness,
    )

    private fun resourceBudget(): ResourceBudget = ResourceBudget(
        resultLimit = ResultLimit.parse(250).refinedValue(),
        workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue(),
        elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue(),
    )

    private fun operationId(raw: String): OperationId = OperationId.parse(raw).refinedValue()

    private fun capabilityId(raw: String): CapabilityId = CapabilityId.parse(raw).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private data class DiscoverRequest(
        val query: String,
    ) : OperationRequest

    private data class DiscoverPayload(
        val symbols: List<String>,
    ) : OperationPayload

    private sealed interface DiscoverQualification : OperationQualification {
        data object Truncated : DiscoverQualification
    }

    private sealed interface DiscoverRejection : OperationRejection {
        data class Blocked(
            val blocker: OperationBlocker,
        ) : DiscoverRejection
    }

    private fun OperationRegistryConstruction.createdRegistry(): OperationRegistry = when (this) {
        is OperationRegistryConstruction.Created -> registry
        is OperationRegistryConstruction.Rejected -> error("Expected registry, got $failures")
    }
}
