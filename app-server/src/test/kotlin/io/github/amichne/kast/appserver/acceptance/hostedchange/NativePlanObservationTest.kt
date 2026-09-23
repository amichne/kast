package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class NativePlanObservationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `plan response stages retain bounded success and failure signals`() {
        for (stage in NativePlanStage.entries) {
            for (outcome in NativePlanStageOutcome.entries) {
                val document = Json.parseToJsonElement(NativePlanStageObservation(stage, outcome).encode()).jsonObject
                assertEquals(setOf("event", "stage", "outcome"), document.keys)
                assertEquals("kast_native_plan_stage", document.getValue("event").jsonPrimitive.content)
                assertEquals(stage.name, document.getValue("stage").jsonPrimitive.content)
                assertEquals(outcome.name, document.getValue("outcome").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `native plan and legacy probe evidence encode only bounded facts`() {
        val plan = nativePlanSourceEvidence("abc")
        assertEquals(setOf("sourceSha256", "postPlanAdmission"), plan.keys)
        assertEquals("abc", plan.getValue("sourceSha256").jsonPrimitive.content)
        assertEquals("SAVED_PSI_COMMITTED", plan.getValue("postPlanAdmission").jsonPrimitive.content)
        val processes =
            Json.encodeToJsonElement(NativeProcessTraceDocument.serializer(), NativeProcessTraceDocument(1, 0))
                .jsonObject
        assertEquals(setOf("processCount", "isolatedStartupCommandCount"), processes.keys)
        assertEquals(1, processes.getValue("processCount").jsonPrimitive.int)
        assertEquals(0, processes.getValue("isolatedStartupCommandCount").jsonPrimitive.int)
    }

    @Test
    fun `source and authority changes remain distinct in durable evidence`() {
        val source = byteArrayOf(1)
        val first = Json.encodeToJsonElement(AuthoritySample.serializer(), AuthoritySample(6)).jsonObject
        val next = Json.encodeToJsonElement(AuthoritySample.serializer(), AuthoritySample(7)).jsonObject
        val observations =
            listOf(
                observeNativePlan(source, source, first, first),
                observeNativePlan(source, byteArrayOf(2), first, first),
                observeNativePlan(source, source, first, next),
                observeNativePlan(source, byteArrayOf(2), first, next),
            )
        assertEquals(NativePlanInvariant.entries, observations)
        for (observation in observations) verifyEvidence(observation)
    }

    private fun verifyEvidence(observation: NativePlanInvariant) {
        val report = directory.resolve("${observation.name}.json")
        val evidence = NativeChangeEvidence(report)
        if (observation == NativePlanInvariant.UNCHANGED) evidence.verifyPlan(observation)
        else {
            val failure = assertThrows<NativeRejected> { evidence.verifyPlan(observation) }
            assertEquals(observation.name, failure.failure.name)
        }
        val document = Json.parseToJsonElement(Files.readString(report)).jsonObject
        val recorded = document.getValue("cases").jsonObject.getValue("plan-has-no-source-effect").jsonObject
        val expected = Json.encodeToJsonElement(PlanExpected.serializer(), PlanExpected(observation.name)).jsonObject
        assertEquals(expected, recorded.getValue("evidence"))
    }
}

@Serializable private data class AuthoritySample(val epoch: Long)

@Serializable private data class PlanExpected(val invariant: String)
