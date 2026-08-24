package support.tasks

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

/** Registers the root-owned source guard for fixed build-logic JSON schemas. */
fun Project.registerGeneratedBuildLogicSerializationVerification():
    TaskProvider<VerifyGeneratedSerializationSourcesTask> {
    val verification = tasks.register(
        "verifyGeneratedBuildLogicSerialization",
        VerifyGeneratedSerializationSourcesTask::class.java,
    ) {
        group = "verification"
        description = "Rejects hand-written JSON structure in fixed build-logic schemas."
        sourceFiles.from(
            files(
                "build-logic/src/main/kotlin/support/architecture/projection/" +
                    "ArchitectureProjection.kt",
                "build-logic/src/main/kotlin/support/architecture/gradle/ArchitectureTasks.kt",
                "build-logic/src/main/kotlin/support/pr633/Pr633StackAdmission.kt",
                "build-logic/src/main/kotlin/support/pr633/Pr633StackEvidence.kt",
                "build-logic/src/main/kotlin/support/pr633/Pr633GateEvidenceSerialization.kt",
                "build-logic/src/main/kotlin/support/pr633/VerifyPr633StackTask.kt",
                "build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt",
            ),
        )
        forbiddenTokens.set(
            listOf(
                "JsonArray",
                "JsonElement",
                "JsonObject",
                "JsonPrimitive",
                "KSerializer",
                "MapSerializer",
                "append(\"{",
                "buildJsonArray",
                "buildJsonObject",
                "decodeFromString<",
                "encodeToString(this)",
                "jsonObject",
                "jsonPrimitive",
                "parseToJsonElement",
            ),
        )
        generatedAdapterNamePrefixes.set(
            listOf(
                "ArchitectureProjection",
                "ArchitectureTasks",
                "GenerateControlMetadataTask",
                "Pr633GateEvidenceSerialization",
                "Pr633StackAdmission",
                "Pr633StackEvidence",
                "VerifyPr633StackTask",
            ),
        )
        generatedAdapterNameSuffixes.set(listOf(".kt"))
        requiredGeneratedAdapterTokens.set(listOf(".serializer()"))
        reportingRoot.set(layout.projectDirectory)
        reportFile.set(
            layout.buildDirectory.file("reports/generated-build-logic-serialization.txt"),
        )
    }
    tasks.named("check").configure { dependsOn(verification) }
    return verification
}

/**
 * Fast source-policy guard: `Gradle source inputs ->
 * GeneratedSerializationSourceVerification.Accepted`.
 *
 * Acceptance establishes only that declared sources contain configured generated-factory markers,
 * contain no configured hand-written structure marker, and have no declared legacy adapter
 * present. Compiler-backed serializer construction and round-trip tests remain the semantic proof;
 * source text cannot prove serializer provenance. [GeneratedSerializationSourceViolation] is the
 * closed expected failure type. Raw files and policy strings are extracted only at this Gradle
 * task boundary.
 */
@CacheableTask
abstract class VerifyGeneratedSerializationSourcesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val forbiddenSourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenTokens: ListProperty<String>

    @get:Input
    abstract val generatedAdapterNamePrefixes: ListProperty<String>

    @get:Input
    abstract val generatedAdapterNameSuffixes: ListProperty<String>

    @get:Input
    abstract val requiredGeneratedAdapterTokens: ListProperty<String>

    @get:Internal
    abstract val reportingRoot: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * Fast guard transition: `Gradle source inputs -> accepted verification report`.
     *
     * Establishes the configured textual policy markers and materializes that observation.
     * [GeneratedSerializationSourceViolation] is projected to Gradle failure only here. Raw source
     * text and filenames do not escape this outer build boundary.
     */
    @TaskAction
    fun verify() {
        val root = reportingRoot.get().asFile
        val policy = GeneratedSerializationSourcePolicy(
            forbiddenTokens = forbiddenTokens.get().map(::SerializationSourceToken),
            generatedAdapterNamePrefixes = generatedAdapterNamePrefixes.get(),
            generatedAdapterNameSuffixes = generatedAdapterNameSuffixes.get(),
            requiredGeneratedAdapterTokens =
                requiredGeneratedAdapterTokens.get().map(::SerializationSourceToken),
        )
        val sources = sourceFiles.files.sortedBy(File::getPath).map { source ->
            GeneratedSerializationSource(
                path = source.relativePathFrom(root),
                filename = source.name,
                lines = source.readLines(),
            )
        }
        val presentForbiddenSources = forbiddenSourceFiles.files
            .filter(File::exists)
            .sortedBy(File::getPath)
            .map { source -> source.relativePathFrom(root) }

        when (
            val verification = verifyGeneratedSerializationSources(
                policy = policy,
                sources = sources,
                presentForbiddenSources = presentForbiddenSources,
            )
        ) {
            is GeneratedSerializationSourceVerification.Accepted -> {
                val report = reportFile.get().asFile
                report.parentFile.mkdirs()
                report.writeText(
                    "acceptedSources=${verification.sourceCount}\n" +
                        "acceptedGeneratedAdapters=${verification.generatedAdapterCount}\n",
                )
            }

            is GeneratedSerializationSourceVerification.Rejected -> throw GradleException(
                buildString {
                    appendLine("Generated serialization source verification failed:")
                    verification.violations.forEach { violation ->
                        appendLine("  ${violation.render()}")
                    }
                },
            )
        }
    }
}

private data class SerializationSourceToken(
    val value: String,
)

private data class GeneratedSerializationSource(
    val path: String,
    val filename: String,
    val lines: List<String>,
)

private data class GeneratedSerializationSourcePolicy(
    val forbiddenTokens: List<SerializationSourceToken>,
    val generatedAdapterNamePrefixes: List<String>,
    val generatedAdapterNameSuffixes: List<String>,
    val requiredGeneratedAdapterTokens: List<SerializationSourceToken>,
) {
    fun isGeneratedAdapter(filename: String): Boolean =
        generatedAdapterNamePrefixes.any(filename::startsWith) &&
            generatedAdapterNameSuffixes.any(filename::endsWith)
}

private sealed interface GeneratedSerializationSourceVerification {
    data class Accepted(
        val sourceCount: Int,
        val generatedAdapterCount: Int,
    ) : GeneratedSerializationSourceVerification

    data class Rejected(
        val violations: List<GeneratedSerializationSourceViolation>,
    ) : GeneratedSerializationSourceVerification
}

private sealed interface GeneratedSerializationSourceViolation {
    data class ForbiddenToken(
        val path: String,
        val line: Int,
        val token: SerializationSourceToken,
    ) : GeneratedSerializationSourceViolation

    data class MissingGeneratedAdapterToken(
        val path: String,
        val token: SerializationSourceToken,
    ) : GeneratedSerializationSourceViolation

    data class LegacySourcePresent(
        val path: String,
    ) : GeneratedSerializationSourceViolation
}

/**
 * Fast guard transition: `(GeneratedSerializationSourcePolicy, source text) ->
 * GeneratedSerializationSourceVerification`.
 *
 * [GeneratedSerializationSourceVerification.Accepted] records the configured source markers
 * across every declared source; it does not prove compiler generation. The closed
 * [GeneratedSerializationSourceViolation] variants represent every rejection this textual guard
 * can observe. Raw filesystem extraction is owned by [VerifyGeneratedSerializationSourcesTask].
 */
private fun verifyGeneratedSerializationSources(
    policy: GeneratedSerializationSourcePolicy,
    sources: List<GeneratedSerializationSource>,
    presentForbiddenSources: List<String>,
): GeneratedSerializationSourceVerification {
    val violations = buildList {
        sources.forEach { source ->
            source.lines.forEachIndexed { index, line ->
                policy.forbiddenTokens.filter { token -> token.value in line }.forEach { token ->
                    add(
                        GeneratedSerializationSourceViolation.ForbiddenToken(
                            path = source.path,
                            line = index + 1,
                            token = token,
                        ),
                    )
                }
            }
            if (policy.isGeneratedAdapter(source.filename)) {
                val content = source.lines.joinToString("\n")
                policy.requiredGeneratedAdapterTokens
                    .filterNot { token -> token.value in content }
                    .forEach { token ->
                        add(
                            GeneratedSerializationSourceViolation.MissingGeneratedAdapterToken(
                                path = source.path,
                                token = token,
                            ),
                        )
                    }
            }
        }
        presentForbiddenSources.forEach { path ->
            add(GeneratedSerializationSourceViolation.LegacySourcePresent(path))
        }
    }
    return if (violations.isEmpty()) {
        GeneratedSerializationSourceVerification.Accepted(
            sourceCount = sources.size,
            generatedAdapterCount = sources.count { source ->
                policy.isGeneratedAdapter(source.filename)
            },
        )
    } else {
        GeneratedSerializationSourceVerification.Rejected(violations)
    }
}

private fun GeneratedSerializationSourceViolation.render(): String = when (this) {
    is GeneratedSerializationSourceViolation.ForbiddenToken ->
        "$path:$line: forbidden source token `${token.value}`"
    is GeneratedSerializationSourceViolation.MissingGeneratedAdapterToken ->
        "$path: missing generated adapter token `${token.value}`"
    is GeneratedSerializationSourceViolation.LegacySourcePresent ->
        "$path: legacy hand-written serializer adapter is present"
}

private fun File.relativePathFrom(root: File): String =
    relativeTo(root).invariantSeparatorsPath
