package kast.baseline.build

import kast.baseline.program.*
import groovy.json.JsonSlurper
import groovy.json.JsonException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

@DisableCachingByDefault(because = "Every proof must execute against the current clean exact head")
abstract class BaselineGate : DefaultTask() {
    @get:Input abstract val gateId: Property<String>
    @get:Internal abstract val checkout: DirectoryProperty
    @get:Classpath abstract val executionClasspath: ConfigurableFileCollection
    @get:Input abstract val javaExecutable: Property<String>
    @get:OutputDirectory abstract val proofDirectory: DirectoryProperty
    @get:Inject abstract val processes: ExecOperations

    private fun spec(id: GateId) = BaselineProgram.tasks.single { it.id == id }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun execute(arguments: List<String>, directory: File): Pair<Int, ByteArray> {
        val output = ByteArrayOutputStream()
        val result = processes.exec {
            commandLine(arguments)
            workingDir(directory)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
            environment = System.getenv().filterKeys { it !in setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS") }
        }
        return result.exitValue to output.toByteArray()
    }
    private fun git(vararg arguments: String): String {
        val (exit, output) = execute(listOf("git") + arguments, checkout.get().asFile)
        if (exit != 0) throw GradleException("baseline-git-unavailable")
        return output.decodeToString().trim()
    }
    private fun command(id: GateId): List<String> = when (val action = spec(id).action) {
        is GateAction.Check -> listOf(javaExecutable.get(), "-cp", executionClasspath.asPath,
            "kast.baseline.verification.MainKt", action.suite.argument)
        GateAction.BoundaryCheck -> listOf("gradle-module-policy", id.name)
        is GateAction.Unimplemented -> listOf("unimplemented-adapter", action.adapter.name)
    }
    private fun bytecodeDigest(): String = sha(executionClasspath.files.sortedBy { it.path }.flatMap { entry ->
        if (entry.isDirectory) entry.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(entry).path }.map {
            it.relativeTo(entry).path + ":" + sha(it.readBytes())
        }.toList() else listOf(entry.name + ":" + sha(entry.readBytes()))
    }.joinToString("\n").toByteArray())
    private fun directory(id: GateId): File = proofDirectory.get().asFile.parentFile.resolve(id.name)
    private fun expected(id: GateId, dependencies: Map<GateId, String>): ReceiptCoordinates = ReceiptCoordinates(
        sha(programProjection().toByteArray()), git("rev-parse", "HEAD"), BaselineProgram.BASE_REVISION,
        sha((git("rev-parse", "HEAD") + ":" + bytecodeDigest()).toByteArray()),
        sha(json(command(id)).toByteArray()), dependencies)

    /** JSON decoding is confined here. Unknown keys, wrong types, and altered artifacts reject. */
    private fun readReceipt(id: GateId): String {
        val dependencies = spec(id).dependencies.associateWith(::readReceipt)
        val directory = directory(id)
        val file = directory.resolve("receipt.json")
        if (!file.isFile) throw GradleException("baseline-predecessor-missing:$id")
        val decoded = try { JsonSlurper().parse(file) } catch (_: JsonException) {
            throw GradleException("baseline-receipt-json:$id")
        }
        val parsed = decoded as? Map<*, *> ?: throw GradleException("baseline-receipt-shape:$id")
        if (parsed.keys != setOf("coordinates", "status", "observations", "artifacts"))
            throw GradleException("baseline-receipt-fields:$id")
        val coordinates = parsed["coordinates"] as? Map<*, *> ?: throw GradleException("baseline-receipt-coordinates:$id")
        if (coordinates.keys != setOf("program", "head", "base", "inputs", "command", "dependencies"))
            throw GradleException("baseline-coordinate-fields:$id")
        fun field(name: String) = coordinates[name] as? String ?: throw GradleException("baseline-coordinate-type:$id")
        val rawDependencies = coordinates["dependencies"] as? Map<*, *> ?: throw GradleException("baseline-dependency-shape:$id")
        val dependencyValues = rawDependencies.entries.associate { entry ->
            val name = entry.key as? String ?: throw GradleException("baseline-dependency-id:$id")
            val gate = GateId.entries.singleOrNull { it.name == name } ?: throw GradleException("baseline-dependency-id:$id")
            gate to (entry.value as? String ?: throw GradleException("baseline-dependency-digest:$id"))
        }
        val rawArtifacts = parsed["artifacts"] as? Map<*, *> ?: throw GradleException("baseline-artifact-shape:$id")
        if (rawArtifacts.keys != setOf("output.log", "bytecode")) throw GradleException("baseline-artifact-set:$id")
        val artifacts = rawArtifacts.entries.associate { (key, value) ->
            key.toString() to (value as? String ?: throw GradleException("baseline-artifact-digest:$id"))
        }
        val log = directory.resolve("output.log")
        if (!log.isFile) throw GradleException("baseline-output-missing:$id")
        val observed = mapOf("output.log" to sha(log.readBytes()), "bytecode" to bytecodeDigest())
        val observations = (parsed["observations"] as? List<*>)?.map {
            it as? String ?: throw GradleException("baseline-observation-type:$id")
        } ?: throw GradleException("baseline-observation-shape:$id")
        val raw = ReceiptDocument(ReceiptCoordinates(field("program"), field("head"), field("base"),
            field("inputs"), field("command"), dependencyValues), parsed["status"] as? String ?: "", observations, artifacts)
        when (val result = VerifiedReceipt.parse(raw, expected(id, dependencies), observed,
            log.readLines().filter { it.startsWith("PASS ") }.map { it.removePrefix("PASS ") })) {
            is ReceiptAdmission.Verified -> return sha(file.readBytes())
            is ReceiptAdmission.Rejected -> throw GradleException("baseline-receipt-rejected:$id:${result.reason}")
        }
    }

    @TaskAction fun prove() {
        val id = GateId.valueOf(gateId.get())
        val directory = proofDirectory.get().asFile
        directory.mkdirs()
        val receipt = directory.resolve("receipt.json")
        receipt.delete() // A failing rerun must not leave this gate's old PASS receipt.
        if (git("status", "--porcelain", "--untracked-files=all").isNotEmpty())
            throw GradleException("baseline-dirty-checkout: commit or remove changes before issuing proof")
        val dependencies = spec(id).dependencies.associateWith(::readReceipt)
        val before = expected(id, dependencies)
        val output = when (val action = spec(id).action) {
            is GateAction.Check -> {
                val (exit, bytes) = execute(command(id), project.rootDir)
                directory.resolve("output.log").writeBytes(bytes)
                if (exit != 0) throw GradleException("baseline-check-rejected:$id (see output.log)")
                bytes
            }
            GateAction.BoundaryCheck -> {
                ExampleModule.entries.forEach { module ->
                    val child = project.rootProject.project(module.path)
                    val actual = child.configurations.flatMap { configuration ->
                        configuration.dependencies.withType(ProjectDependency::class.java).map { it.path }
                    }.toSet()
                    if (actual != module.dependencies) throw GradleException("baseline-module-dependencies:${module.path}")
                }
                "PASS declared-module-dependencies\n".toByteArray()
            }
            is GateAction.Unimplemented -> throw GradleException("baseline-blocked:$id:${action.adapter}; not RED and not PASS")
        }
        val observations = output.decodeToString().lineSequence().filter { it.startsWith("PASS ") }
            .map { it.removePrefix("PASS ") }.toList()
        if (observations.isEmpty()) throw GradleException("baseline-observation-missing:$id")
        if (git("status", "--porcelain", "--untracked-files=all").isNotEmpty() || expected(id, dependencies) != before)
            throw GradleException("baseline-input-changed:$id")
        directory.resolve("output.log").writeBytes(output)
        val document = mapOf("coordinates" to mapOf("program" to before.program, "head" to before.head,
            "base" to before.base, "inputs" to before.inputs, "command" to before.command,
            "dependencies" to dependencies.mapKeys { it.key.name }), "status" to "PASS",
            "observations" to observations, "artifacts" to mapOf("output.log" to sha(output), "bytecode" to bytecodeDigest()))
        receipt.writeText(json(document) + "\n")
        try { readReceipt(id) } catch (failure: Exception) {
            receipt.delete()
            throw failure
        }
    }
}
