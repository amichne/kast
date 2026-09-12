package io.github.amichne.kast.runtime.hosted

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.change.verify.LiveChangeReceiptStoreFailure
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocationFailure
import io.github.amichne.kast.evidence.contract.KastUserStateRootFailure
import io.github.amichne.kast.evidence.sqlite.SqliteHostedChangeStoresFailure
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedChangeFailureTest {
    @Test
    fun `every encoded change failure satisfies the independently owned schema and unknown causes reject`() {
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(checkNotNull(javaClass.getResource("/ide-hosted/hosted-endpoint.schema.json")).readText())
        for (failure in HostedEndpointFailure.entries) {
            val document = HostedResponse.Rejected(failure).document
            assertTrue(schema.validate(document, InputFormat.JSON).isEmpty(), document)
            assertEquals(setOf("type", "failure"), Json.parseToJsonElement(document).jsonObject.keys)
        }
        val failures: List<HostedChangeFailure> =
            KastUserStateRootFailure.entries.map(HostedChangeFailure::StateRoot) +
                HostedWorkspaceStateLocationFailure.entries.map(HostedChangeFailure::Location) +
                SqliteMutationRecoveryJournalOpenFailure.entries.map(HostedChangeFailure::Database) +
                LiveChangePlanStoreFailure.entries.map(HostedChangeFailure::Plans) +
                LiveChangePlanStoreFailure.entries.map(HostedChangeFailure::PlanLookup) +
                LiveChangeReceiptStoreFailure.entries.map(HostedChangeFailure::Receipts) +
                HostedEndpointFailure.entries.map(HostedChangeFailure::Endpoint) +
                HostedChangeFailure.PlanMissing
        for (failure in failures) {
            val document = HostedResponse.ChangeRejected(failure).document
            assertTrue(schema.validate(document, InputFormat.JSON).isEmpty(), document)
        }
        for (fixture in
            listOf(
                InvalidRejection("HOST_REJECTED", "CHANGE_STORAGE_REJECTED", null),
                InvalidRejection("HOST_REJECTED", "CHANGE_STORAGE_REJECTED", InvalidDetail("PLAN_LOOKUP", "UNKNOWN")),
                InvalidRejection("HOST_REJECTED", "IO_UNAVAILABLE", InvalidDetail("PLAN_LOOKUP", "CORRUPT_RECORD")),
            )) {
            val malformed = Json { explicitNulls = false }.encodeToString(fixture)
            assertTrue(schema.validate(malformed, InputFormat.JSON).isNotEmpty(), malformed)
        }
    }

    @Test
    fun `every stored plan failure survives admission diagnostics and encoded response`() {
        val root =
            assertInstanceOf<Refinement.Refined<CanonicalWorkspaceRoot>>(
                    CanonicalWorkspaceRoot.fromCanonicalPath(java.nio.file.Path.of("/fixture"))
                )
                .value
        for (cause in LiveChangePlanStoreFailure.entries) {
            val observations = mutableListOf<HostedChangeStorageObservation>()
            val result =
                admitLoadedHostedPlan(root, LiveChangePlanLookup.Rejected(cause))
                    .observed(HostedChangeStorageStage.PLAN_LOOKUP, observations::add)
            val failure = assertInstanceOf<Refinement.Rejected<HostedChangeFailure>>(result).failure
            assertEquals(HostedChangeFailure.PlanLookup(cause), failure)
            assertEquals(
                listOf(HostedChangeStorageObservation.Rejected(HostedChangeStorageStage.PLAN_LOOKUP, failure)),
                observations,
            )
            val document = Json.parseToJsonElement(HostedResponse.ChangeRejected(failure).document).jsonObject
            assertEquals("HOST_REJECTED", document.getValue("type").jsonPrimitive.content)
            assertEquals("CHANGE_STORAGE_REJECTED", document.getValue("failure").jsonPrimitive.content)
            assertEquals(setOf("type", "cause"), document.getValue("detail").jsonObject.keys)
            assertEquals("PLAN_LOOKUP", document.getValue("detail").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(cause.name, document.getValue("detail").jsonObject.getValue("cause").jsonPrimitive.content)
            val signal = Json.encodeToString(observations.single())
            assertFalse(signal.contains("/fixture"))
            assertTrue(signal.contains(cause.name))
        }
    }

    @Test
    fun `successful stages retain their value and emit bounded completion evidence`() {
        val evidence = Any()
        val observations = mutableListOf<HostedChangeStorageObservation>()
        val result: Refinement<Any, HostedChangeFailure> = Refinement.Refined(evidence)
        assertSame(result, result.observed(HostedChangeStorageStage.OPEN, observations::add))
        assertEquals(listOf(HostedChangeStorageObservation.Completed(HostedChangeStorageStage.OPEN)), observations)
        val document = Json.parseToJsonElement(Json.encodeToString(observations.single())).jsonObject
        assertEquals(setOf("type", "stage"), document.keys)
        assertEquals("COMPLETED", document.getValue("type").jsonPrimitive.content)
        assertEquals("OPEN", document.getValue("stage").jsonPrimitive.content)
    }

    @Test
    fun `database failure and missing plan remain distinct from corrupt storage`() {
        assertEquals(
            HostedChangeFailure.Database(SqliteMutationRecoveryJournalOpenFailure.SYMLINK_NOT_ALLOWED),
            SqliteHostedChangeStoresFailure.Database(SqliteMutationRecoveryJournalOpenFailure.SYMLINK_NOT_ALLOWED)
                .hosted(),
        )
        val root =
            assertInstanceOf<Refinement.Refined<CanonicalWorkspaceRoot>>(
                    CanonicalWorkspaceRoot.fromCanonicalPath(java.nio.file.Path.of("/fixture"))
                )
                .value
        assertEquals(
            Refinement.Rejected(HostedChangeFailure.PlanMissing),
            admitLoadedHostedPlan(root, LiveChangePlanLookup.Missing),
        )
        val missing =
            Json.parseToJsonElement(HostedResponse.ChangeRejected(HostedChangeFailure.PlanMissing).document).jsonObject
        assertEquals("INVALID_REQUEST", missing.getValue("failure").jsonPrimitive.content)
    }
}

@Serializable private data class InvalidRejection(val type: String, val failure: String, val detail: InvalidDetail?)

@Serializable private data class InvalidDetail(val type: String, val cause: String)
