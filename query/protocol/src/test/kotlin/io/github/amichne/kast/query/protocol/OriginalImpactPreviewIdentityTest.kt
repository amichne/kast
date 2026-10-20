package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionRowIdentityCause
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OriginalImpactPreviewIdentityTest {
    @Test
    fun `another investigation cannot borrow stored path identities`() {
        val original = OriginalImpactRetentionTest.Fixture()
        val other = OriginalImpactRetentionTest.Fixture()
        val issued = original.issueOriginal(emptyList())
        val outcome =
            invocationPreviewRows(
                other.previewItems(listOf(2)),
                other.execution(listOf(2)).result.rows,
                issued,
                QueryResultRetentionObservation.None,
            )
        assertEquals(rejected(), outcome)
    }

    @Test
    fun `missing stored identity rejects with an exact row identity cause`() {
        val fixture = OriginalImpactRetentionTest.Fixture()
        val issued = fixture.issueOriginal(emptyList()).copy(rowIds = emptyList())
        val observed = mutableListOf<QueryResultRetentionEvidence>()
        val outcome =
            invocationPreviewRows(
                fixture.previewItems(listOf(2)),
                fixture.execution(listOf(2)).result.rows,
                issued,
                QueryResultRetentionObservation(observed::add),
            )
        assertEquals(rejected(), outcome)
        val signal = observed.single() as QueryResultRetentionEvidence.PreviewRowIdentityRejected
        assertEquals(1, signal.itemCount.value)
        assertEquals(0, signal.identityCount.value)
    }

    private fun rejected() =
        Refinement.Rejected(
            QueryRunRejection.ImpactExecutionRejected(
                ImpactExecutionFailureDocument.RowIdentity(ImpactExecutionRowIdentityCause.CHANGED_RETAINED_ROWS)
            )
        )
}
