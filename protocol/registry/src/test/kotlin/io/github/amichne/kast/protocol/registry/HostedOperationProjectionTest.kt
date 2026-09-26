package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedOperationProjectionTest {
    @Test
    fun `canonical definitions are the sole ordered hosted exposure authority`() {
        assertEquals(
            listOf(
                CanonicalOperation.WORKSPACE_LIFECYCLE,
                CanonicalOperation.QUERY_RUN,
                CanonicalOperation.SYMBOL_DISCOVER,
                CanonicalOperation.SYMBOL_INSPECT,
                CanonicalOperation.SOURCE_READ,
                CanonicalOperation.TRAVERSAL_RUN,
                CanonicalOperation.DIAGNOSTIC_CHECK,
                CanonicalOperation.CHANGE,
                CanonicalOperation.CHANGE_PLAN,
                CanonicalOperation.CHANGE_APPLY,
                CanonicalOperation.CHANGE_RECOVER,
            ),
            HostedOperationProjection.publicDefinitions.map { it.operation },
        )
        assertEquals(
            listOf(CanonicalOperation.INDEX_SYNC, CanonicalOperation.TOPOLOGY_BUILD),
            HostedOperationProjection.internalDefinitions.map { it.operation },
        )
        assertTrue(HostedOperationProjection.unavailableDefinitions.isEmpty())
    }

    @Test
    fun `only add declaration is advertised for hosted change`() {
        assertEquals(
            HostedVariants.Intents(setOf(HostedChangeIntent.ADD_DECLARATION)),
            CanonicalOperationDefinitions.change.hostedVariants,
        )
        assertEquals(
            CanonicalOperationDefinitions.all
                .filterNot { it.operation in setOf(CanonicalOperation.CHANGE, CanonicalOperation.CHANGE_PLAN) }
                .associate { it.operation to HostedVariants.None },
            CanonicalOperationDefinitions.all
                .filterNot { it.operation in setOf(CanonicalOperation.CHANGE, CanonicalOperation.CHANGE_PLAN) }
                .associate { it.operation to it.hostedVariants },
        )
    }

    @Test
    fun `hosted binding completeness requires every public operation and forbids unavailable ones`() {
        assertEquals(
            HostedBindingCompleteness.Complete,
            HostedOperationProjection.verifyBindings(HostedOperationProjection.publicDefinitions.map { it.operation }),
        )
        assertEquals(
            HostedBindingCompleteness.Rejected(
                setOf(HostedBindingCompletenessFailure.MissingPublicBinding(CanonicalOperation.CHANGE))
            ),
            HostedOperationProjection.verifyBindings(
                HostedOperationProjection.publicDefinitions
                    .map { it.operation }
                    .filterNot { it == CanonicalOperation.CHANGE }
            ),
        )
    }
}
