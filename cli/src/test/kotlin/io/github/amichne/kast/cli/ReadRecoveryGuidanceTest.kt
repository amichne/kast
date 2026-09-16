package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ReadBudgetField
import io.github.amichne.kast.protocol.contract.ReadRecoveryGuidance
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.recoveryGuidance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadRecoveryGuidanceTest {
    @Test
    fun `increase advice identifies actual field and admitted ceiling`() {
        val report =
            ExecutionBudgetReport.from(
                hostedSchemaBudgetGrant(ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(1).proven()))
            )
        val advice = qualification(RelationLimitationDocument.WORK_LIMIT_REACHED).recoveryGuidance(report).single()
        assertTrue(advice is ReadRecoveryGuidance.IncreaseBudget)
        val encoded = Json.encodeToJsonElement(ReadRecoveryGuidance.serializer(), advice).jsonObject
        assertEquals("increase_budget", encoded.getValue("action").jsonPrimitive.content)
        assertEquals("max_work_units", encoded.getValue("field").jsonPrimitive.content)
        assertTrue(encoded.getValue("instruction").jsonPrimitive.content.contains("operatorCeiling"))
        assertEquals(
            report.work.operatorCeiling.value,
            encoded.getValue("allowance").jsonObject.getValue("operatorCeiling").jsonPrimitive.content.toLong(),
        )
    }

    @Test
    fun `operator clamp and absent grant never recommend more budget`() {
        val report =
            ExecutionBudgetReport.from(
                hostedSchemaBudgetGrant(
                    ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(Long.MAX_VALUE).proven())
                )
            )
        for (grant in listOf(report, null)) {
            assertEquals(
                ReadRecoveryGuidance.NarrowScope(ReadBudgetField.WORK),
                qualification(RelationLimitationDocument.WORK_LIMIT_REACHED).recoveryGuidance(grant).single(),
            )
        }
    }

    @Test
    fun `provider failure and indexing are not budget advice`() {
        assertEquals(
            listOf(ReadRecoveryGuidance.ReportFailure.Required),
            qualification(RelationLimitationDocument.PROVIDER_FAILURE).recoveryGuidance(null),
        )
        assertEquals(
            listOf(ReadRecoveryGuidance.WaitForWorkspace.Required),
            qualification(RelationLimitationDocument.DUMB_MODE_TRANSITION).recoveryGuidance(null),
        )
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString(
                ReadRecoveryGuidance.serializer(),
                Json.encodeToString(UnknownRecovery("retry_forever")),
            )
        }
    }

    @kotlinx.serialization.Serializable private data class UnknownRecovery(val action: String)

    private fun qualification(cause: RelationLimitationDocument) =
        RelationReadQualification.terminalIncomplete(RelationKnownMinimumDocument.parse(0).proven(), listOf(cause))
            .proven()

    private fun <V> Refinement<V, *>.proven(): V = (this as Refinement.Refined).value
}
