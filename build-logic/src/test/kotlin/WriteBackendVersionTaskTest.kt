import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WriteBackendVersionTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `invalid revision preserves the complete previous identity`() {
        val versionFile = temporaryDirectory.resolve("kast-backend-version.txt")
        val revisionFile = temporaryDirectory.resolve("kast-backend-revision.txt")
        Files.writeString(versionFile, "old-version")
        Files.writeString(revisionFile, "a".repeat(40))
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "writeBackendVersionUnderTest",
            WriteBackendVersionTask::class.java,
        ).get().apply {
            backendVersion.set("new-version")
            backendRevision.set("not-a-revision")
            this.versionFile.set(versionFile.toFile())
            this.revisionFile.set(revisionFile.toFile())
        }

        assertThrows<IllegalArgumentException> { task.write() }

        assertEquals("old-version", Files.readString(versionFile))
        assertEquals("a".repeat(40), Files.readString(revisionFile))
    }
}
