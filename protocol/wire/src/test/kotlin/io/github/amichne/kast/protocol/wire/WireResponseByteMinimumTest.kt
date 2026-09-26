package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireResponseByteMinimumTest {
    @Test
    fun `each operation minimum is inclusive and remains below its actual required response`() {
        val cases =
            listOf(
                CanonicalOperationWireBindings.queryRun.minimumResponseBytes to
                    CanonicalOperationWireBindings.queryRun.encodeOutcome(
                        OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady)
                    ),
                CanonicalOperationWireBindings.sourceRead.minimumResponseBytes to
                    CanonicalOperationWireBindings.sourceRead.encodeOutcome(
                        OperationOutcome.Rejected(SourceReadRejection.WORKSPACE_NOT_READY)
                    ),
                CanonicalOperationWireBindings.traversalRun.minimumResponseBytes to
                    CanonicalOperationWireBindings.traversalRun.encodeOutcome(
                        OperationOutcome.Rejected(TraversalRunRejection.WORKSPACE_NOT_READY)
                    ),
            )
        for ((minimum, encoded) in cases) {
            val document = (encoded as WireEncoding.Encoded).document
            assertFalse(minimum.admits(limit(minimum.bytes - 1)))
            assertTrue(minimum.admits(limit(minimum.bytes)))
            assertTrue(minimum.admits(limit(minimum.bytes + 1)))
            assertTrue(
                document.toByteArray(Charsets.UTF_8).size > minimum.bytes,
                "The necessary identity bound must not claim to fit an outcome body",
            )
        }
    }

    private fun limit(bytes: Long) = (ReturnedByteLimit.parse(bytes) as Refinement.Refined).value
}
