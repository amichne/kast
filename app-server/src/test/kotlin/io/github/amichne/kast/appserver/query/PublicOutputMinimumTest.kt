package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicOutputMinimumTest {
    @Test
    fun `impossible output allowance rejects before canonical invocation`() {
        val names = listOf("scopedInterfaceDeclaration", "resumeQuery", "readRetained", "readSourceExactSymbol")
        for (name in names) {
            val example = PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples.getValue(name)
            val input = Json.decodeFromJsonElement(PublicToolQuerySymbols.serializer(), example.value)
            for (catalog in PublicToolCatalog.entries) {
                for (bytes in listOf(1L, 4095L, 4096L)) {
                    val budget = PublicToolExecutionBudget(maxReturnedBytes = bytes)
                    val action = when (val original = input.request) {
                        is PublicToolRunAction -> original.copy(executionBudget = budget)
                        is PublicToolResumeAction -> original.copy(executionBudget = budget)
                        is PublicToolReadResultAction -> original.copy(executionBudget = budget)
                        is PublicToolReadSourceAction -> original.copy(executionBudget = budget)
                    }
                    val raw = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input.copy(request = action))
                    val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw, catalog)
                    if (bytes < 4096L)
                        assertEquals(Refinement.Rejected(PublicToolInputFailure.Parameter(
                            PublicToolParameter.EXECUTION_BUDGET_MAX_RETURNED_BYTES,
                            PublicToolRule.OUTPUT_CONTROL_ALLOWANCE)), admitted, "$name $catalog $bytes")
                    else assertTrue(admitted is Refinement.Refined, "$name $catalog $bytes")
                }
            }
        }
    }
}
