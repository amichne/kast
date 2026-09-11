package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContractFailure
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeContractFailureTest {
    @Test
    fun `contract success preserves the admitted object`() {
        assertEquals("admitted", Validation.Validated("admitted").nativeContracts())
    }

    @Test
    fun `definition failures retain every schema and closed failure kind`() {
        val rejection =
            Validation.Rejected(
                NonEmptyFailures.from<CodexProtocolContractFailure>(
                    CodexProtocolContractFailure.PlanApprovalIncompatible(CodexOwnedSchema.ITEM_STARTED_NOTIFICATION),
                    listOf(
                        CodexProtocolContractFailure.PlanApprovalIncompatible(
                            CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION
                        )
                    ),
                )
            )
        val failure = assertThrows(NativeContractRejected::class.java) { rejection.nativeContracts() }
        assertEquals(
            Json.parseToJsonElement(
                """{
                    "stage":"CONTRACT_DEFINITION",
                    "observations":[
                        {"contract":"PLAN_APPROVAL_INCOMPATIBLE","schema":"ITEM_STARTED_NOTIFICATION"},
                        {"contract":"PLAN_APPROVAL_INCOMPATIBLE","schema":"ITEM_COMPLETED_NOTIFICATION"}
                    ]
                }"""
            ),
            Json.encodeToJsonElement(NativeContractFailureDocument.serializer(), failure.document),
        )
    }

    @Test
    fun `missing schema file preserves inventory stage without physical path`() {
        val failure =
            assertThrows(NativeContractRejected::class.java) {
                nativeSchemaFileRejected(CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_PARAMS)
            }
        assertEquals(NativeContractStage.SCHEMA_INVENTORY, failure.document.stage)
        assertEquals(
            listOf(
                NativeContractObservation(
                    NativeContractKind.SCHEMA_FILE_REJECTED,
                    CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_PARAMS,
                )
            ),
            failure.document.observations,
        )
    }
}
