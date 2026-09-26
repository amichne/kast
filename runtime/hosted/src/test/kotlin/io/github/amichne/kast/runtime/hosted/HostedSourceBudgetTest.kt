package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSourceBudgetTest {
    private val budget =
        ExecutionBudgetDocument(
            ElapsedTimeLimitMillis.parse(200).proven(),
            WorkUnitLimit.parse(7).proven(),
            ResultLimit.parse(2).proven(),
            ReturnedByteLimit.parse(2048).proven(),
        )

    @Test
    fun `source budget survives canonical wire input and hosted admission`() {
        val fixture = RelationPagingFixture.live()
        val request =
            SourceReadRequest(
                SourceReadAnchorDocument.Symbol(fixture.exact),
                SourceRegionSelectionDocument.Anchor,
                SourceEntitySelectionDocument.None,
                SourceTextRequestDocument.None,
                SourceEntityLimitDocument.parse(100).proven(),
                SourceTextByteLimitDocument.parse(65536).proven(),
                SourceReadPageDocument.First,
                executionBudget = budget,
            )
        val encoded = Json.encodeToString(SourceReadRequest.serializer(), request)
        val decoded = Json.decodeFromString(SourceReadRequest.serializer(), encoded)
        assertEquals(
            budget.requested(),
            HostedRequest.Source(fixture.authority.workspaceRoot, decoded).executionBudget().requested,
        )
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
