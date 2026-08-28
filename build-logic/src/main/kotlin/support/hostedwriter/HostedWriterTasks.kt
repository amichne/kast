package support.hostedwriter

import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

abstract class GenerateHostedWriterProgramTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val encoded = HostedWriterProgram.encoded()
        validate(schemaFile.get().asFile.readText(), encoded)
        writeAtomically(outputFile.get().asFile.toPath(), encoded)
    }
}

abstract class WriteHostedWriterReceiptTask : DefaultTask() {
    @get:Input
    abstract val gateId: Property<String>

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val programFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyReceipts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val proofArtifacts: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeReceipt() {
        val programText = programFile.get().asFile.readText()
        val program = when (val replay = HostedWriterProgram.replay(programText)) {
            is HostedWriterProgramReplay.Admitted -> replay.document
            HostedWriterProgramReplay.Malformed,
            HostedWriterProgramReplay.NotCanonical,
            HostedWriterProgramReplay.WrongProgram,
            -> error("hosted-writer program replay rejected: $replay")
        }
        val id = ProofGateId(gateId.get())
        val task = program.tasks.singleOrNull { it.id == id }
                   ?: error("unknown hosted-writer gate ${id.value}")
        val head = repositoryHead()
        val programFingerprint = ProgramFingerprint(sha256(programText.toByteArray()))
        val dependencies = dependencyReceipts.files.sortedBy { it.name }.map { file ->
            val text = file.readText()
            val receipt = Json.decodeFromString<ProofReceipt>(text)
            require(receipt.repositoryHead == head) { "dependency receipt head mismatch" }
            require(receipt.programFingerprint == programFingerprint) {
                "dependency receipt program mismatch"
            }
            receipt to ReceiptDigest(sha256(text.toByteArray()))
        }
        require(dependencies.map { it.first.gateId }.toSet() == task.dependencies) {
            "dependency receipts do not match fixed gate dependencies"
        }
        val artifactDigests = proofArtifacts.files
            .flatMap { file ->
                if (file.isDirectory) {
                    Files.walk(file.toPath()).use { paths ->
                        paths.filter(Files::isRegularFile).map { it.toFile() }.toList()
                    }
                } else {
                    listOf(file)
                }
            }
            .sortedBy { it.absolutePath }
            .mapTo(linkedSetOf()) { ArtifactDigest(sha256(it.readBytes())) }
        val dependencyDigests = dependencies.mapTo(linkedSetOf()) { it.second }
        val inputMaterial = buildString {
            append(programFingerprint.value)
            append(head.value)
            task.allowedReads.forEach { append(it.value) }
            dependencyDigests.forEach { append(it.value) }
        }
        val receipt = ProofReceipt(
            gateId = id,
            programFingerprint = programFingerprint,
            repositoryHead = head,
            dependencyReceiptDigests = dependencyDigests,
            inputDigest = InputDigest(sha256(inputMaterial.toByteArray())),
            commandDigest = CommandDigest(sha256(task.command.value.toByteArray())),
            observedProofs = task.expectedProof,
            artifactDigests = artifactDigests,
        )
        val encoded = Json {
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }.encodeToString(receipt) + "\n"
        validate(schemaFile.get().asFile.readText(), encoded)
        writeAtomically(outputFile.get().asFile.toPath(), encoded)
    }

    private fun repositoryHead(): RepositoryHead {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repositoryDirectory.get().asFile)
            .redirectErrorStream(true)
            .start()
        val raw = process.inputStream.bufferedReader().use { it.readText() }.trim()
        require(process.waitFor() == 0 && Regex("[0-9a-f]{40}").matches(raw)) {
            "repository HEAD unavailable"
        }
        return RepositoryHead(raw)
    }
}

abstract class ValidateHostedWriterInstalledAcceptanceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val candidateFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun validateAndCopy() {
        val candidate = candidateFile.get().asFile.readText()
        Json.decodeFromString<InstalledAcceptanceDocument>(candidate)
        validate(schemaFile.get().asFile.readText(), candidate)
        writeAtomically(outputFile.get().asFile.toPath(), candidate)
    }
}

private fun validate(schema: String, document: String) {
    val result = HostedWriterSchemaValidator.validate(
        Json.parseToJsonElement(schema),
        Json.parseToJsonElement(document),
    )
    require(result is HostedWriterSchemaValidation.Valid) {
        "hosted-writer artifact schema rejected: $result"
    }
}

private fun writeAtomically(target: java.nio.file.Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)
