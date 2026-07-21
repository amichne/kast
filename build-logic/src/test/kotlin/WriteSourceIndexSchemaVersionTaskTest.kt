import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WriteSourceIndexSchemaVersionTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `protocol version eight generates the Kotlin schema authority`() {
        val schemaVersion = temporaryDirectory.resolve("source-index-schema-version.txt")
        Files.writeString(schemaVersion, "8\n")
        val outputDirectory = temporaryDirectory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "writeSourceIndexSchemaVersionUnderTest",
            WriteSourceIndexSchemaVersionTask::class.java,
        ).get().apply {
            schemaVersionFile.set(schemaVersion.toFile())
            this.outputDirectory.set(outputDirectory.toFile())
        }

        task.write()

        val generated = Files.readString(
            outputDirectory.resolve("io/github/amichne/kast/indexstore/store/SourceIndexSchemaVersion.kt"),
        )
        assertEquals(
            """
            package io.github.amichne.kast.indexstore.store

            /**
             * SQLite source-index schema version declared by the shared protocol.
             */
            const val SOURCE_INDEX_SCHEMA_VERSION: Int = 8
            """.trimIndent() + "\n",
            generated,
        )
    }
}
