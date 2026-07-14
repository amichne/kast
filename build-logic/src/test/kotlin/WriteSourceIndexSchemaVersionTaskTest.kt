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
    fun `release state version eight generates the Kotlin schema authority`() {
        val releaseState = temporaryDirectory.resolve("release-state.json")
        Files.writeString(releaseState, """{"source_index_schema_version": 8}""")
        val outputDirectory = temporaryDirectory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "writeSourceIndexSchemaVersionUnderTest",
            WriteSourceIndexSchemaVersionTask::class.java,
        ).get().apply {
            releaseStateFile.set(releaseState.toFile())
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
             * SQLite source-index schema version declared by the release state.
             */
            const val SOURCE_INDEX_SCHEMA_VERSION: Int = 8
            """.trimIndent() + "\n",
            generated,
        )
    }
}
