import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class WriteSourceIndexSchemaVersionTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseStateFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val version = sourceIndexSchemaVersion(releaseStateFile.get().asFile.readText())
        outputDirectory
            .file("io/github/amichne/kast/indexstore/store/SourceIndexSchemaVersion.kt")
            .get()
            .asFile
            .apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package io.github.amichne.kast.indexstore.store

                    /**
                     * SQLite source-index schema version declared by the release state.
                     */
                    const val SOURCE_INDEX_SCHEMA_VERSION: Int = $version
                    """.trimIndent() + "\n",
                )
            }
    }

    private fun sourceIndexSchemaVersion(content: String): Int {
        val regex = Regex(""""source_index_schema_version"\s*:\s*(\d+)""")
        val matches = regex.findAll(content).toList()
        require(matches.size == 1) {
            "release-state.json must contain exactly one source_index_schema_version field"
        }
        return matches.single().groupValues[1].toInt().also { version ->
            require(version > 0) { "source_index_schema_version must be positive" }
        }
    }
}
