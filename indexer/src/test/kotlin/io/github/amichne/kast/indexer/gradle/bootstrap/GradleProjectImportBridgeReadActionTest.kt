package io.github.amichne.kast.indexer.gradle.bootstrap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Supplier

@TestApplication
class GradleProjectImportBridgeReadActionTest {
    @Test
    fun `project model inventory observation owns an IntelliJ read action`() {
        val expected = GradleModelInventory.empty()

        val method = GradleProjectImportBridge::class.java.getDeclaredMethod(
            "readProjectModelInventory",
            Supplier::class.java,
        )
        method.isAccessible = true
        val observed = method.invoke(
            null,
            Supplier {
                assertTrue(ApplicationManager.getApplication().isReadAccessAllowed)
                expected
            },
        )

        assertEquals(expected, observed)
    }
}
