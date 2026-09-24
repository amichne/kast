package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.wire.presentation.CanonicalReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Relation output schema checks share the emitted documents, not a mocked provider. */
class LiveRelationOutputSchemaTest {
    @Test
    fun `every finite relation rejection satisfies its installed schema`() {
        for (reason in RelationReadRejection.entries) {
            val document = CanonicalReadCliDocuments.projectRelation(OperationOutcome.Rejected(reason)).document()
            assertAdmits(document)
            assertEquals(JsonPrimitive(reason.name.lowercase().replace('_', '-')), document["reason"])
        }
    }

    @Test
    fun `owner issued relation continuations satisfy advertised output schemas`() = runTest {
        for (fixture in listOf(RelationPagingFixture.published(), RelationPagingFixture.live())) {
            val outcome = fixture.page() as OperationOutcome.Qualified
            assertAdmits(CanonicalReadCliDocuments.projectRelation(outcome).document())
        }
    }

    private fun assertAdmits(document: JsonObject) {
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(installedServerOutputSchema(CanonicalOperation.RELATION_READ).toString())
        val errors = schema.validate(completedSchemaEnvelope(document), InputFormat.JSON)
        assertTrue(errors.isEmpty(), "Relation schema rejected its emitted document: $errors")
    }

    private fun ProjectedOperationOutcome.document(): JsonObject =
        when (this) {
                is ProjectedOperationOutcome.Complete -> document
                is ProjectedOperationOutcome.Qualified -> document
                is ProjectedOperationOutcome.Rejected -> document
            }
            .value
            .let(Json::parseToJsonElement)
            .jsonObject
}
