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
                "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramRuntimeGraph.kt",
                "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveProgramTasksM0M1.kt",
                "build-logic/src/main/kotlin/support/delivery/model/proof/DeliveryReceipt.kt",
                "build-logic/src/main/kotlin/support/delivery/tasks/AGENTS.md",
                "build-logic/src/main/kotlin/support/delivery/tasks/receiptboundary/DeliveryReceiptJsonBoundary.kt",
                "build-logic/src/main/kotlin/support/delivery/tasks/Kvp001ReceiptTaskSupport.kt",
                "build-logic/src/main/kotlin/support/delivery/tasks/receipt/AGENTS.md",
                "build-logic/src/main/kotlin/support/delivery/tasks/receipt/ReceiptIssuanceBoundary.kt",
                "build-logic/src/test/kotlin/support/delivery/DeliveryTaskOwnershipTest.kt",
                "build-logic/src/test/kotlin/support/delivery/proof/AGENTS.md",
                "build-logic/src/test/kotlin/support/delivery/proof/DeliveryReceiptTest.kt",
                "docs/kast-vfs-passive-reused-index-delivery-program.md",
                "gradle/delivery/kast-vfs-passive-requirements.json",
                "gradle/delivery/kast-vfs-passive-reused-index-program.json",
                "gradle/delivery/schema/proof-receipt.schema.json",
                "scripts/verify_bundle.py",
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

    @Test
    fun `KVP-038 owns its required architecture verifier`() {
        val task = KastVfsPassiveReusedIndexProgram.definition.tasks.single {
            it.id == TaskId("KVP-038")
        }

        assertTrue(
            "build-logic/src/main/kotlin/support/architecture/gradle" in task.allowedWrites,
        )
        assertTrue(
            "build-logic/src/test/kotlin/support/architecture/gradle" in task.allowedWrites,
        )
    }
}
