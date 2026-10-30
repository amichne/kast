import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.VerifyControlDistributionTask

class VerifyControlDistributionTaskTest {
    @Test
    fun `query artifact rejects a substituted payload and a script producer`(@TempDir directory: Path) {
        val root = Files.createDirectories(directory.resolve("product"))
        val payload = Files.createDirectories(root.resolve("bin")).resolve("kast-query")
        val producer = directory.resolve("producer")
        Files.writeString(producer, "#!/bin/sh\nexec java Main\n")
        Files.writeString(payload, "#!/bin/sh\nexec java Other\n")
        val archive = Files.write(directory.resolve("archive.tar.gz"), byteArrayOf())
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("verifyControl", VerifyControlDistributionTask::class.java).get().apply {
            controlDirectory.set(root.toFile())
            controlArchive.set(archive.toFile())
            qualifiedQueryExecutable.set(producer.toFile())
            targetPlatform.set("macos-aarch64")
            maximumEntries.set(16384)
            maximumArchiveBytes.set(64L * 1024 * 1024)
            maximumInstalledBytes.set(128L * 1024 * 1024)
        }
        assertEquals(
            "control query payload differs from its native producer",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
        Files.copy(producer, payload, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(
            "control query payload is not a native executable for macos-aarch64",
            assertThrows(IllegalStateException::class.java, task::verify).message,
        )
    }
}
