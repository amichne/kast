package support.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

private val REPORT_JSON = Json { prettyPrint = true; prettyPrintIndent = "    " }

@Serializable
private data class IdePluginLayoutReport(
    val schemaVersion: Int,
    val taskId: String,
    val policyId: String,
    val pluginSizeCeilingBytes: Long,
    val artifact: LayoutArtifactDocument,
    val scannedClassCount: Int,
    val violationCount: Int,
    val nestedJars: List<LayoutJarDocument>,
)

@Serializable
private data class LayoutArtifactDocument(val path: String, val sha256: String, val sizeBytes: Long)

@Serializable
private data class LayoutJarDocument(
    val entry: String,
    val sha256: String,
    val sizeBytes: Long,
    val classCount: Int,
    val classOwnerDigest: String,
)

@UntrackedTask(
    because = "Rechecks canonical path, symlink state, and exact archive bytes at execution time",
)
abstract class VerifyIdeHostedPluginLayoutTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginArchive: RegularFileProperty
    @get:Internal abstract val repositoryRoot: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    /**
     * Proof transition: Gradle archive/root properties `->` emitted KVP-011 layout report.
     *
     * Establishes repository-bound archive admission and the complete hosted-plugin layout policy.
     * Finite [IdePluginLayoutFailure] values become [GradleException] only at this task adapter.
     */
    @TaskAction
    fun verify() {
        val archive = when (val result = RepositoryBoundPluginArchive.read(
            repositoryRoot.get().asFile.toPath(),
            pluginArchive.get().asFile.toPath(),
        )) {
            is RepositoryPluginArchiveReadResult.Complete -> result.archive
            is RepositoryPluginArchiveReadResult.Rejected -> throw renderFailure(result.failure)
        }
        val layout = when (val result = admitIdePluginLayout(archive)) {
            is IdePluginLayoutResult.Complete -> result.layout
            is IdePluginLayoutResult.Rejected -> throw renderFailure(result.failure)
        }
        val report = reportFor(archive.relativePathForReport(), layout)
        try {
            writeAtomically(
                reportFile.get().asFile.toPath(),
                REPORT_JSON.encodeToString(IdePluginLayoutReport.serializer(), report) + "\n",
            )
        } catch (_: Exception) {
            throw renderFailure(IdePluginLayoutFailure.REPORT_WRITE_FAILURE)
        }
    }
}

@UntrackedTask(because = "Re-derives fixed KVP-011 forbidden nested-class fixtures")
abstract class VerifyIdeHostedPluginLayoutNegativeTask : DefaultTask() {
    /**
     * Proof transition: fixed negative fixtures `->` complete KVP-011 rejection proof.
     *
     * Establishes exact rejection for all ten forbidden layout cases. Any mismatched finite failure
     * becomes [GradleException] only at this task adapter.
     */
    @TaskAction
    fun verify() {
        val cases = ideHostedNegativeCases()
        cases.forEach { fixture ->
            val result = fixture.inspect()
            if (result != IdePluginLayoutResult.Rejected(fixture.expected)) {
                throw renderFailure(fixture.expected)
            }
        }
        logger.lifecycle("KVP-011 rejected all {} plugin-layout negative cases", cases.size)
    }
}

private fun renderFailure(failure: IdePluginLayoutFailure): GradleException = GradleException(
    "KVP-011 plugin layout rejected: ${failure.name}",
)

private fun reportFor(path: String, layout: VerifiedIdePluginLayout) = IdePluginLayoutReport(
    schemaVersion = 1,
    taskId = "KVP-011",
    policyId = IDE_HOSTED_PLUGIN_POLICY_ID.value,
    pluginSizeCeilingBytes = IDE_HOSTED_PLUGIN_SIZE_CEILING_BYTES.value,
    artifact = LayoutArtifactDocument(
        path,
        layout.archiveDigest.value,
        layout.archiveSizeBytes.value,
    ),
    scannedClassCount = layout.jars.sumOf { it.classOwners.size },
    violationCount = 0,
    nestedJars = layout.jars.map { jar ->
        LayoutJarDocument(
            jar.entry.value,
            jar.digest.value,
            jar.sizeBytes.value,
            jar.classOwners.size,
            support.plugin.IdeHostedDigest.observe(
                jar.classOwners.joinToString("\n") { it.value }.toByteArray(),
            ).value,
        )
    },
)

private fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content)
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}
