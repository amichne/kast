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
                CanonicalOperation.WORKSPACE_INSPECT,
                CanonicalOperation.INDEX_SYNC,
                CanonicalOperation.TOPOLOGY_BUILD,
                CanonicalOperation.SYMBOL_DISCOVER,
                CanonicalOperation.SYMBOL_RESOLVE,
                CanonicalOperation.SYMBOL_DESCRIBE,
                CanonicalOperation.RELATION_READ,
                CanonicalOperation.TRAVERSAL_RUN,
                CanonicalOperation.DIAGNOSTIC_CHECK,
                CanonicalOperation.CHANGE_PLAN,
                CanonicalOperation.CHANGE_APPLY,
                CanonicalOperation.CHANGE_VERIFY,
                CanonicalOperation.CHANGE_RECOVER,
            ),
            HostedOperationProjection.publicDefinitions.map { it.operation },
        )
        assertTrue(HostedOperationProjection.internalDefinitions.isEmpty())
        assertTrue(HostedOperationProjection.unavailableDefinitions.isEmpty())
    }

    @Test
    fun `only add declaration is advertised for hosted change planning`() {
        assertEquals(
            HostedVariants.Intents(setOf(HostedChangeIntent.ADD_DECLARATION)),
            CanonicalOperationDefinitions.changePlan.hostedVariants,
        )
        assertEquals(
            CanonicalOperationDefinitions.all
                .filterNot { it.operation == CanonicalOperation.CHANGE_PLAN }
                .associate { it.operation to HostedVariants.None },
            CanonicalOperationDefinitions.all
                .filterNot { it.operation == CanonicalOperation.CHANGE_PLAN }
                .associate { it.operation to it.hostedVariants },
        )
    }

    @Test
    fun `hosted binding completeness requires every public operation and forbids unavailable ones`() {
        assertEquals(
            HostedBindingCompleteness.Complete,
            HostedOperationProjection.verifyBindings(
                HostedOperationProjection.publicDefinitions.map { it.operation },
            ),
        )
        assertEquals(
            HostedBindingCompleteness.Rejected(
                setOf(
                    HostedBindingCompletenessFailure.MissingPublicBinding(
                        CanonicalOperation.CHANGE_RECOVER,
                    ),
                ),
            ),
            HostedOperationProjection.verifyBindings(
                HostedOperationProjection.publicDefinitions
                    .map { it.operation }
                    .dropLast(1),
            ),
        )
    }
}
