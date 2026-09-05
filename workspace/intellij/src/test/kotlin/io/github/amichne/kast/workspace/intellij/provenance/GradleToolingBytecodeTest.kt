package io.github.amichne.kast.workspace.intellij.provenance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream

class GradleToolingBytecodeTest {
    @Test
    fun `every Gradle-side model class can load in a Java 8 or later project daemon`() {
        val payload = listOf(
            GradleSourceRootProducerModel::class.java,
            GradleSourceRootProducerModelEntry::class.java,
            DefaultGradleSourceRootProducerModel::class.java,
            DefaultGradleSourceRootProducerModelEntry::class.java,
            GradleSourceRootProducerRole::class.java,
            GradleSourceRootProducerProvenance::class.java,
            GradleSourceRootProducerModelBuilder::class.java,
        )
        for (type in payload) {
            DataInputStream(checkNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))).use { bytes ->
                assertEquals(0xCAFEBABE.toInt(), bytes.readInt())
                bytes.readUnsignedShort()
                assertTrue(bytes.readUnsignedShort() <= 52, "Gradle payload exceeds Java 8: ${type.name}")
            }
        }
    }
}
