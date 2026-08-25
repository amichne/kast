package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeliveryTaskOwnershipTest {
    @Test
    fun `M0 delivery model tasks use canonical owners`() {
        val tasks = KastVfsPassiveReusedIndexProgram.definition.tasks.associateBy { it.id.value }

        assertEquals(
            listOf(
                "build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt",
                "build-logic/src/test/kotlin/support/delivery/DeliveryProgramModelTest.kt",
            ),
            tasks.getValue("KVP-002").allowedWrites,
        )
        assertTrue(
            "build-logic/src/main/kotlin/support/delivery/model/DeliveryProgramModel.kt" in
                tasks.getValue("KVP-002").allowedReads,
        )
        assertEquals(
            listOf(
                "build-logic/src/main/kotlin/support/delivery/model/DeliveryGraph.kt",
                "build-logic/src/test/kotlin/support/delivery/DeliveryGraphTest.kt",
            ),
            tasks.getValue("KVP-003").allowedWrites,
        )
        assertEquals(
            listOf(
                "build-logic/src/main/kotlin/support/delivery/model/DeliveryReceipt.kt",
                "build-logic/src/test/kotlin/support/delivery/DeliveryReceiptTest.kt",
                "gradle/delivery/schema/proof-receipt.schema.json",
            ),
            tasks.getValue("KVP-007").allowedWrites,
        )
        assertEquals(
            listOf(
                "build-logic/src/main/kotlin/support/delivery/model/DeliveryState.kt",
                "build-logic/src/test/kotlin/support/delivery/DeliveryStateTest.kt",
            ),
            tasks.getValue("KVP-008").allowedWrites,
        )
    }
}
