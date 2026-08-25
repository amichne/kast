package support.delivery

import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateDeliveryProjectionsTask : DefaultTask() {
    @get:Input
    abstract val programProjection: Property<String>

    @get:OutputFile
    abstract val programOutputFile: RegularFileProperty

    @get:Input
    abstract val requirementTraceProjection: Property<String>

    @get:OutputFile
    abstract val requirementTraceOutputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        writeAtomically(programOutputFile, programProjection)
        writeAtomically(requirementTraceOutputFile, requirementTraceProjection)
    }

    private fun writeAtomically(output: RegularFileProperty, content: Property<String>) {
        val target = output.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content.get())
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

@CacheableTask
abstract class VerifyDeliveryProjectionsTask : DefaultTask() {
    @get:Input
    abstract val expectedProgramProjection: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val programProjectionFile: RegularFileProperty

    @get:Input
    abstract val expectedRequirementTraceProjection: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val requirementTraceProjectionFile: RegularFileProperty

    @TaskAction
    fun verifyProjections() {
        verifyProjection(programProjectionFile, expectedProgramProjection, "delivery program")
        verifyProjection(
            requirementTraceProjectionFile,
            expectedRequirementTraceProjection,
            "requirement trace",
        )
    }

    private fun verifyProjection(
        input: RegularFileProperty,
        expected: Property<String>,
        description: String,
    ) {
        if (input.get().asFile.readText() != expected.get()) {
            throw GradleException("checked-in $description differs from the typed Kotlin authority")
        }
    }
}
