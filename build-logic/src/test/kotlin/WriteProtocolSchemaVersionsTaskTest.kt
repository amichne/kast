import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WriteProtocolSchemaVersionsTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `authored protocol versions generate Kotlin schema constants`() {
        val apiSchemaVersion = temporaryDirectory.resolve("api-schema-version.txt")
        Files.writeString(apiSchemaVersion, "4\n")
        val installReceiptSchemaVersion = temporaryDirectory.resolve("install-receipt-schema-version.txt")
        Files.writeString(installReceiptSchemaVersion, "3\n")
        val outputDirectory = temporaryDirectory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "writeProtocolSchemaVersionsUnderTest",
            WriteProtocolSchemaVersionsTask::class.java,
        ).get().apply {
            this.apiSchemaVersion.set(apiSchemaVersion.toFile())
            this.installReceiptSchemaVersion.set(installReceiptSchemaVersion.toFile())
            this.outputDirectory.set(outputDirectory.toFile())
        }

        task.write()

        val generated = Files.readString(
            outputDirectory.resolve("io/github/amichne/kast/api/protocol/SchemaVersion.kt"),
        )
        assertEquals(
            """
            package io.github.amichne.kast.api.protocol

            /**
             * Public API schema version declared by the shared protocol.
             */
            const val SCHEMA_VERSION: Int = 4

            /**
             * Persisted install-receipt schema version declared by the shared protocol.
             */
            const val INSTALL_RECEIPT_SCHEMA_VERSION: Int = 3
            """.trimIndent() + "\n",
            generated,
        )
    }
}
