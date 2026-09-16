package io.github.amichne.kast.runtime.hosted

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResponse
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshResult
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshRule
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedWorkspaceRefreshSchemaTest {
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(javaClass.getResource("/ide-hosted/hosted-workspace-refresh.schema.json")).readText()
            )

    @Test
    fun `refresh responses retain every finite status and failure in the independently owned schema`() {
        val results =
            WorkspaceRefreshStage.entries.map { WorkspaceRefreshResult.Pending("fixture-request", it) } +
                WorkspaceRefreshFailure.entries.flatMap {
                    listOf(WorkspaceRefreshResult.Failed("fixture-request", it), WorkspaceRefreshResult.Rejected(it))
                } +
                listOf(
                    WorkspaceRefreshResult.Complete("fixture-request"),
                    WorkspaceRefreshResult.Configured(WorkspaceRefreshRule.Off),
                )
        for (result in results) {
            val document =
                Json.encodeToString(
                    WorkspaceRefreshResponse("/fixture", "00000000-0000-0000-0000-000000000001", result)
                )
            assertTrue(schema.validate(document, InputFormat.JSON).isEmpty(), document)
        }
    }

    @Test
    fun `unknown discriminants fields causes and incompatible refresh variants are rejected`() {
        // Independent negative wire corpus; none of these documents may acquire lifecycle completion evidence.
        val corpus =
            checkNotNull(javaClass.getResource("/ide-hosted/workspace-refresh-invalid-responses.json")).readText()
        for (malformed in Json.parseToJsonElement(corpus).jsonArray) {
            assertTrue(schema.validate(malformed.toString(), InputFormat.JSON).isNotEmpty(), malformed.toString())
        }
    }
}
