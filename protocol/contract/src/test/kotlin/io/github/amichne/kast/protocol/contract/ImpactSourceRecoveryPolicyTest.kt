package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactSourceRecoveryPolicyTest {
    @Test
    fun `every admitted source failure preserves its original recovery and scalar enum wire value`() {
        // Independent table was captured from the exhaustive recovery switch before its owner migration.
        val expected =
            requireNotNull(javaClass.getResource("/query/impact-source-recovery-policy.tsv"))
                .readText()
                .lineSequence()
                .filter(String::isNotEmpty)
                .map { line ->
                    val fields = line.split('\t')
                    assertEquals(3, fields.size)
                    Expected(
                        QueryImpactSourceFailureCode.valueOf(fields[0]),
                        fields[1],
                        ReadRecoveryAction.valueOf(fields[2]),
                    )
                }
                .toList()
        assertEquals(expected.map { it.code }, QueryImpactSourceFailureCode.entries)
        val position = (ProtocolOffset.parse(0) as Refinement.Refined).value
        for (case in expected) {
            val rejection =
                QueryRunRejection.ImpactSourceRejected(QueryImpactSourceFailureDocument.Admission(case.code, position))
            assertEquals(case.action, rejection.recoveryAction(), case.code.name)
            assertEquals(
                JsonPrimitive(case.wireName),
                Json.encodeToJsonElement(QueryImpactSourceFailureCode.serializer(), case.code),
            )
        }
    }

    private data class Expected(
        val code: QueryImpactSourceFailureCode,
        val wireName: String,
        val action: ReadRecoveryAction,
    )
}
