package support.delivery

import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

@Serializable
private data class Kvp034MetricSpecDocument(
    val schemaVersion: Int,
    val taskId: String,
    val metrics: List<Kvp034MetricSpecEntry>,
)

@Serializable
private data class Kvp034MetricSpecEntry(
    val id: String,
    val predicate: String,
    val expected: String,
)

@UntrackedTask(because = "Projects the canonical graph-owned installed metric specification")
abstract class GenerateKvp034MetricSpecTask : DefaultTask() {
    @get:OutputFile abstract val specFile: RegularFileProperty

    @TaskAction fun generate() {
        val metrics = KastVfsPassiveReusedIndexProgram.validated.program.installedMetrics
            .sortedBy { it.id }
            .map { Kvp034MetricSpecEntry(it.id, it.predicate, it.value.toString()) }
        val raw = kvp034AcceptanceJson.encodeToString(
            Kvp034MetricSpecDocument.serializer(),
            Kvp034MetricSpecDocument(1, "KVP-034", metrics),
        ) + "\n"
        writeTextAtomically(specFile.get().asFile.toPath(), raw)
    }
}

@UntrackedTask(because = "Runs the installed CLI against one external live IDE Project")
abstract class Kvp034InstalledAcceptanceTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val harnessFile: RegularFileProperty
    @get:InputFile abstract val metricSpecFile: RegularFileProperty
    @get:InputFile abstract val staticProofFile: RegularFileProperty
    @get:InputFile abstract val dynamicProofFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun accept() {
        val root = repositoryRootPath.get()
        val head = observeExactHead(java.nio.file.Path.of(root))
        val result = execOperations.exec {
            workingDir(root)
            executable("python3")
            args(
                harnessFile.get().asFile.absolutePath,
                "--root", root,
                "--head", head.value,
                "--metrics", metricSpecFile.get().asFile.absolutePath,
                "--static-proof", staticProofFile.get().asFile.absolutePath,
                "--dynamic-proof", dynamicProofFile.get().asFile.absolutePath,
                "--report", reportFile.get().asFile.absolutePath,
            )
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException(
            "KVP-034 installed acceptance rejected with status ${result.exitValue}",
        )
        val raw = when (val read = readBoundaryFile(
            reportFile.get().asFile.toPath(), MAX_RECEIPT_EVIDENCE_BYTES,
        )) {
            is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
            is BoundaryFileRead.Rejected -> throw GradleException(
                "KVP-034 installed report unreadable: ${read.failure}",
            )
        }
        if (admitKvp034Report(
            raw,
            KastVfsPassiveReusedIndexProgram.validated.program.installedMetrics,
            DeliveryGeneration(head.value),
        ) !is Kvp034ReportAdmission.Complete) {
            throw GradleException("KVP-034 installed report rejected after harness completion")
        }
    }
}

private val kvp034AcceptanceJson = kotlinx.serialization.json.Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
