import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class WriteProtocolSchemaVersionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiSchemaVersion: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val installReceiptSchemaVersion: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val apiVersion = positiveVersion(apiSchemaVersion.get().asFile.readText())
        val installReceiptVersion = positiveVersion(installReceiptSchemaVersion.get().asFile.readText())
        outputDirectory
            .file("io/github/amichne/kast/api/protocol/SchemaVersion.kt")
            .get()
            .asFile
            .apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package io.github.amichne.kast.api.protocol

                    /**
                     * Public API schema version declared by the shared protocol.
                     */
                    const val SCHEMA_VERSION: Int = $apiVersion

                    /**
                     * Persisted install-receipt schema version declared by the shared protocol.
                     */
                    const val INSTALL_RECEIPT_SCHEMA_VERSION: Int = $installReceiptVersion
                    """.trimIndent() + "\n",
                )
            }
    }

    private fun positiveVersion(content: String): Int =
        content.trim().toInt().also { version ->
            require(version > 0) { "schema version must be positive" }
        }
}
