import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.idea.selectedIdeaProductInfo

class SelectedIdeaProductInfoTest {
    @Test
    fun `vendor layout is explicit and ambiguity fails closed`(@TempDir home: Path) {
        assertThrows(GradleException::class.java) { selectedIdeaProductInfo(home.toFile()) }
        val linux = Files.writeString(home.resolve("product-info.json"), "linux")
        assertEquals(linux.toFile(), selectedIdeaProductInfo(home.toFile()))
        Files.createDirectory(home.resolve("Resources"))
        val mac = Files.writeString(home.resolve("Resources/product-info.json"), "mac")
        assertThrows(GradleException::class.java) { selectedIdeaProductInfo(home.toFile()) }
        Files.delete(linux)
        assertEquals(mac.toFile(), selectedIdeaProductInfo(home.toFile()))
    }
}
