package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReceiptEvidenceLocationTest {
    @Test
    fun `canonical receipts are exact-head build evidence`() {
        KastVfsPassiveReusedIndexProgram.definition.tasks.forEach { task ->
            assertEquals(
                "build/reports/delivery/receipts/${task.id.value}-COMPLETE.receipt.json",
                task.completionReceipt.outputPath,
                task.id.value,
            )
            assertFalse(task.allowedReads.any { it.startsWith(TRACKED_RECEIPT_DIRECTORY) })
            assertFalse(task.allowedWrites.any { it.startsWith(TRACKED_RECEIPT_DIRECTORY) })
        }
    }

    private companion object {
        const val TRACKED_RECEIPT_DIRECTORY = "gradle/delivery/receipts"
    }
}
