@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import java.security.MessageDigest
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedWireDescriptorCollisionTest {
    @Test
    fun `overlapping mode tags keep all four qualified budget variant shapes`() {
        val roots =
            listOf(ExecutionBudgetReport.serializer().descriptor, ImpactFlowBudgetDocument.serializer().descriptor)
        val shape = hostedWireShape(roots)
        val expected =
            listOf(
                setOf("max_elapsed_ms", "max_work_units", "max_results", "max_returned_bytes"),
                setOf("max_elapsed_ms", "max_results", "max_returned_bytes"),
                setOf("maxElapsedMillis", "maxWorkUnits", "maxResults", "maxReturnedBytes"),
                setOf("maxResults", "maxReturnedBytes"),
            )
        for (fields in expected) {
            assertTrue(shape.types.any { type -> type.fields.map { it.name }.toSet() == fields }, fields.toString())
        }
        assertEquals(shape.types.size, shape.types.map { it.name }.distinct().size)
    }

    @Test
    fun `a changed field behind an overlapping mode tag changes the wire shape digest`() {
        val report = ExecutionBudgetReport.serializer().descriptor
        val impact = ImpactFlowBudgetDocument.serializer().descriptor
        val before = hostedWireShape(listOf(report, impact)).digest()
        val after = hostedWireShape(listOf(report, impact.renamedImpactField())).digest()
        assertNotEquals(before, after)
    }

    @Test
    fun `qualified mode identity stays stable when root enumeration order changes`() {
        val roots =
            listOf(ExecutionBudgetReport.serializer().descriptor, ImpactFlowBudgetDocument.serializer().descriptor)
        assertEquals(hostedWireShape(roots).digest(), hostedWireShape(roots.reversed()).digest())
    }
}

/** This controlled descriptor edit changes one real impact field without changing its shared mode tag. */
private fun SerialDescriptor.renamedImpactField(): SerialDescriptor {
    val original = this
    return object : SerialDescriptor by original {
        override fun getElementName(index: Int): String =
            original.getElementName(index).let { name ->
                if (serialName == "BOUNDED" && name == "maxElapsedMillis") "changedElapsedMillis" else name
            }

        override fun getElementDescriptor(index: Int): SerialDescriptor =
            original.getElementDescriptor(index).renamedImpactField()
    }
}

private fun HostedWireShapeDocument.digest(): List<Byte> =
    MessageDigest.getInstance("SHA-256")
        .digest(wireJson.encodeToString(HostedWireShapeDocument.serializer(), this).toByteArray())
        .toList()
