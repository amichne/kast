package support.architecture.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import support.architecture.DefaultRuntimeFallbackAuthority
import support.architecture.NoDefaultRuntimeFallbackFailure
import support.architecture.NoDefaultRuntimeFallbackInspection
import support.architecture.NoDefaultRuntimeFallbackVerification
import support.architecture.RuntimeClassBytes
import java.nio.file.Files

@Serializable
private data class NoDefaultRuntimeFallbackReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val status: String,
    val entrypoint: String,
    val reachableClassCount: Int,
    val verifiedAuthorities: List<String>,
)

private val fallbackReportJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "    "
}

@UntrackedTask(because = "Re-derives the fixed KVP-027 fallback-link misuse")
abstract class VerifyNoDefaultRuntimeFallbackNegativeTask : DefaultTask() {
    @TaskAction
    fun verify() {
        val result = NoDefaultRuntimeFallbackInspection.inspect(negativeFixture())
        val rejected = result as? NoDefaultRuntimeFallbackVerification.Rejected
            ?: throw GradleException("KVP-027 misuse fixture was unexpectedly admitted")
        val observed = (listOf(rejected.first) + rejected.additional)
            .filterIsInstance<NoDefaultRuntimeFallbackFailure.ForbiddenReachable>()
            .map(NoDefaultRuntimeFallbackFailure.ForbiddenReachable::authority)
            .toSet()
        val expected = DefaultRuntimeFallbackAuthority.entries.toSet()
        if (observed != expected) {
            throw GradleException("KVP-027 misuse mismatch: expected=$expected observed=$observed")
        }
        logger.lifecycle("KVP-027 rejected all {} fallback authorities", observed.size)
    }
}

@CacheableTask
abstract class VerifyNoDefaultRuntimeFallbackTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledClassDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val classes = compiledClassDirectories.files.asSequence()
            .flatMap { directory ->
                if (directory.isDirectory) directory.walkTopDown().asSequence() else emptySequence()
            }
            .filter { file -> file.isFile && file.extension == "class" }
            .sortedBy { file -> file.path }
            .map { file -> RuntimeClassBytes.capture(file.path, Files.readAllBytes(file.toPath())) }
            .toList()
        val proof = when (val result = NoDefaultRuntimeFallbackInspection.inspect(classes)) {
            is NoDefaultRuntimeFallbackVerification.Complete -> result.proof
            is NoDefaultRuntimeFallbackVerification.Rejected -> throw GradleException(
                "KVP-027 default runtime fallback REJECTED: ${result.first}; " +
                    result.additional.joinToString(),
            )
        }
        val report = NoDefaultRuntimeFallbackReportDocument(
            schemaVersion = 1,
            taskId = "KVP-027",
            status = "COMPLETE",
            entrypoint = proof.entrypoint,
            reachableClassCount = proof.reachableClasses.size,
            verifiedAuthorities = proof.verifiedAuthorities.map { it.name },
        )
        val output = reportFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, fallbackReportJson.encodeToString(report) + "\n")
        logger.lifecycle(
            "KVP-027 admitted IDE-only composition closure ({} classes, {} forbidden mappings)",
            proof.reachableClasses.size,
            proof.verifiedAuthorities.size,
        )
    }
}

private fun negativeFixture(): List<RuntimeClassBytes> {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V21,
        Opcodes.ACC_PUBLIC,
        "io/github/amichne/kast/cli/InstalledKastCliComposition",
        null,
        "java/lang/Object",
        null,
    )
    val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "misuse", "()V", null, null)
    method.visitCode()
    DefaultRuntimeFallbackAuthority.entries.flatMap { it.forbiddenOwners }.forEach { owner ->
        method.visitTypeInsn(Opcodes.NEW, owner)
        method.visitInsn(Opcodes.POP)
    }
    method.visitLdcInsn("/usr/bin/launchctl")
    method.visitInsn(Opcodes.POP)
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(1, 1)
    method.visitEnd()
    writer.visitEnd()
    return listOf(RuntimeClassBytes.capture("negative/Installed.class", writer.toByteArray()))
}
