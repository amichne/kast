import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VerifyPluginXmlPresentTaskTest {
    @Test
    fun `multiple plugin zips are rejected`(@TempDir distributionsDirectory: Path) {
        Files.createFile(distributionsDirectory.resolve("current.zip"))
        Files.createFile(distributionsDirectory.resolve("stale.zip"))

        val failure = assertThrows(IllegalStateException::class.java) {
            selectPluginZip(distributionsDirectory.toFile())
        }

        assertEquals(
            "Expected exactly one plugin zip in ${distributionsDirectory.toFile()}, " +
                "found 2: current.zip, stale.zip",
            failure.message,
        )
    }
}
