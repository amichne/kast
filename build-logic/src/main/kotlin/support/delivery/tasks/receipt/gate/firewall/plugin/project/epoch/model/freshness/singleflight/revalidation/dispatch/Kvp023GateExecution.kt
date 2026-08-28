package support.delivery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test

enum class Kvp023GateCommand(
    val gateId: String,
    val declaredCommand: String,
    val selectorPattern: String,
    val dedicatedTaskPath: String,
    val canonicalTaskPaths: List<String>,
) {
    RED(
        "KVP-023-RED",
        "./gradlew :runtime:ide-read:verifyReadOnlyGraphNegative",
        "*IdeReadRuntimeDispatchNegativeTest",
        ":runtime:ide-read:verifyReadOnlyGraphNegative",
        listOf(":runtime:ide-read:verifyReadOnlyGraphNegative"),
    ),
    GREEN(
        "KVP-023-GREEN",
        "./gradlew :runtime:ide-read:test :runtime:ide-read:verifyReadOnlyGraph",
        "*IdeReadRuntimeDispatchTest",
        ":runtime:ide-read:verifyReadOnlyGraph",
        listOf(":runtime:ide-read:test", ":runtime:ide-read:verifyReadOnlyGraph"),
    ),
}

@Serializable
private data class Kvp023GateExecutionDocument(
    val schemaVersion: Int,
    val taskId: String,
    val gateId: String,
    val declaredCommand: String,
    val canonicalTaskPaths: List<String>,
    val dedicatedTaskPath: String,
    val selectorPattern: String,
    val headObservations: List<Kvp023GateHeadObservationDocument>,
    val phase: Kvp023GateExecutionPhase,
)

@Serializable
private data class Kvp023GateHeadObservationDocument(
    val stage: Kvp023GateHeadObservationStage,
    val exactHead: String,
)

@Serializable private enum class Kvp023GateHeadObservationStage { BEFORE, AFTER }
@Serializable internal enum class Kvp023GateExecutionPhase { STARTED, COMPLETE }

internal enum class Kvp023GateExecutionFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    COMMAND_MISMATCH,
    TASK_PATH_MISMATCH,
    SELECTOR_MISMATCH,
    HEAD_MISMATCH,
    PHASE_MISMATCH,
}

internal class AdmittedKvp023GateExecution private constructor(
    val canonicalDocument: String,
    val command: Kvp023GateCommand,
    val exactHead: AuthorityGitRevision,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp023GateCommand, AuthorityGitRevision,
         * Kvp023GateExecutionPhase) -> Kvp023GateExecutionAdmission`.
         *
         * Establishes canonical evidence for one exact KVP-023 selector, its dedicated Test task,
         * and independently recorded BEFORE/AFTER head observations. Expected failures remain
         * closed [Kvp023GateExecutionFailure] data. Raw JSON is extracted only at gate and receipt
         * boundaries.
         */
        fun admit(
            raw: String,
            expectedCommand: Kvp023GateCommand,
            expectedHead: AuthorityGitRevision,
            expectedPhase: Kvp023GateExecutionPhase,
        ): Kvp023GateExecutionAdmission {
            val document = try {
                KVP023_GATE_JSON.decodeFromString(Kvp023GateExecutionDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return gateRejected(Kvp023GateExecutionFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return gateRejected(Kvp023GateExecutionFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP023_GATE_SCHEMA_VERSION -> return gateRejected(
                    Kvp023GateExecutionFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-023" || document.gateId != expectedCommand.gateId ->
                    return gateRejected(Kvp023GateExecutionFailure.IDENTITY_MISMATCH)
                document.declaredCommand != expectedCommand.declaredCommand -> return gateRejected(
                    Kvp023GateExecutionFailure.COMMAND_MISMATCH,
                )
                document.canonicalTaskPaths != expectedCommand.canonicalTaskPaths ||
                    document.dedicatedTaskPath != expectedCommand.dedicatedTaskPath ->
                    return gateRejected(Kvp023GateExecutionFailure.TASK_PATH_MISMATCH)
                document.selectorPattern != expectedCommand.selectorPattern -> return gateRejected(
                    Kvp023GateExecutionFailure.SELECTOR_MISMATCH,
                )
                document.headObservations != canonicalHeadObservations(
                    expectedHead,
                    expectedPhase,
                ) -> return gateRejected(Kvp023GateExecutionFailure.HEAD_MISMATCH)
                document.phase != expectedPhase -> return gateRejected(
                    Kvp023GateExecutionFailure.PHASE_MISMATCH,
                )
            }
            val canonical = encodeKvp023GateExecution(document)
            if (raw != canonical) return gateRejected(
                Kvp023GateExecutionFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp023GateExecutionAdmission.Admitted(
                AdmittedKvp023GateExecution(canonical, expectedCommand, expectedHead),
            )
        }
    }
}

internal sealed interface Kvp023GateExecutionAdmission {
    data class Admitted(val execution: AdmittedKvp023GateExecution) :
        Kvp023GateExecutionAdmission

    data class Rejected(val failure: Kvp023GateExecutionFailure) : Kvp023GateExecutionAdmission
}

internal fun canonicalKvp023GateExecution(
    command: Kvp023GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp023GateExecutionPhase,
): String = encodeKvp023GateExecution(
    Kvp023GateExecutionDocument(
        schemaVersion = KVP023_GATE_SCHEMA_VERSION,
        taskId = "KVP-023",
        gateId = command.gateId,
        declaredCommand = command.declaredCommand,
        canonicalTaskPaths = command.canonicalTaskPaths,
        dedicatedTaskPath = command.dedicatedTaskPath,
        selectorPattern = command.selectorPattern,
        headObservations = canonicalHeadObservations(head, phase),
        phase = phase,
    ),
)

internal sealed interface Kvp023GateEvidenceFileObservation {
    data class Observed(val execution: AdmittedKvp023GateExecution) :
        Kvp023GateEvidenceFileObservation

    data class Rejected(val failure: Kvp023GateEvidenceFileFailure) :
        Kvp023GateEvidenceFileObservation
}

internal sealed interface Kvp023GateEvidenceFileFailure {
    data class ReadRejected(val path: Path) : Kvp023GateEvidenceFileFailure
    data class AdmissionRejected(val failure: Kvp023GateExecutionFailure) :
        Kvp023GateEvidenceFileFailure
}

/**
 * Proof transition: `(Path, Kvp023GateCommand, AuthorityGitRevision,
 * Kvp023GateExecutionPhase) -> Kvp023GateEvidenceFileObservation`.
 *
 * Establishes readable canonical gate evidence at the expected phase and exact head.
 */
internal fun observeKvp023GateEvidence(
    path: Path,
    command: Kvp023GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp023GateExecutionPhase,
): Kvp023GateEvidenceFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return gateEvidenceReadRejected(path)
    } catch (_: SecurityException) {
        return gateEvidenceReadRejected(path)
    }
    return when (val admission = AdmittedKvp023GateExecution.admit(raw, command, head, phase)) {
        is Kvp023GateExecutionAdmission.Admitted ->
            Kvp023GateEvidenceFileObservation.Observed(admission.execution)
        is Kvp023GateExecutionAdmission.Rejected ->
            Kvp023GateEvidenceFileObservation.Rejected(
                Kvp023GateEvidenceFileFailure.AdmissionRejected(admission.failure),
            )
    }
}

private sealed interface Kvp023GateTaskConfigurationRefinement {
    data class Admitted(val configuration: Kvp023GateTaskConfiguration) :
        Kvp023GateTaskConfigurationRefinement

    data class Rejected(val failure: Kvp023GateTaskConfigurationFailure) :
        Kvp023GateTaskConfigurationRefinement
}

private enum class Kvp023GateTaskConfigurationFailure {
    UNKNOWN_COMMAND,
    INVALID_REPOSITORY_ROOT,
    TASK_PATH_MISMATCH,
    SELECTOR_MISMATCH,
}

private class Kvp023GateTaskConfiguration private constructor(
    val command: Kvp023GateCommand,
    val repositoryRoot: Path,
) {
    companion object {
        /**
         * Proof transition: raw Gradle task configuration ->
         * `Kvp023GateTaskConfigurationRefinement`.
         *
         * Establishes the canonical command-to-dedicated-task mapping, exactly one matching test
         * filter, no exclusions, and a normalized repository root.
         */
        fun refine(
            declaredCommand: String,
            taskPath: String,
            includePatterns: Set<String>,
            excludePatterns: Set<String>,
            rawRepositoryRoot: String,
        ): Kvp023GateTaskConfigurationRefinement {
            val command = Kvp023GateCommand.entries.singleOrNull {
                it.declaredCommand == declaredCommand
            } ?: return rejectedConfiguration(Kvp023GateTaskConfigurationFailure.UNKNOWN_COMMAND)
            if (taskPath != command.dedicatedTaskPath) return rejectedConfiguration(
                Kvp023GateTaskConfigurationFailure.TASK_PATH_MISMATCH,
            )
            if (includePatterns != setOf(command.selectorPattern) || excludePatterns.isNotEmpty()) {
                return rejectedConfiguration(Kvp023GateTaskConfigurationFailure.SELECTOR_MISMATCH)
            }
            val root = try {
                Path.of(rawRepositoryRoot).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return rejectedConfiguration(
                    Kvp023GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            } catch (_: SecurityException) {
                return rejectedConfiguration(
                    Kvp023GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            }
            if (!Files.isDirectory(root)) return rejectedConfiguration(
                Kvp023GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
            )
            return Kvp023GateTaskConfigurationRefinement.Admitted(
                Kvp023GateTaskConfiguration(command, root),
            )
        }
    }
}

@UntrackedTask(because = "Executes one exact-head KVP-023 selector and writes canonical evidence")
abstract class Kvp023ReadOnlyGraphGateTask : Test() {
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:Input abstract val declaredCommand: Property<String>
    @get:OutputFile abstract val gateEvidenceFile: RegularFileProperty

    /** Captures canonical STARTED evidence before the inherited Test actions execute. */
    fun beginGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp023GateExecution(
                configuration.command,
                head,
                Kvp023GateExecutionPhase.STARTED,
            ),
        )
    }

    /** Revalidates exact HEAD and replaces STARTED evidence only after Test success. */
    fun completeGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        when (val observation = observeKvp023GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp023GateExecutionPhase.STARTED,
        )) {
            is Kvp023GateEvidenceFileObservation.Observed -> Unit
            is Kvp023GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-023 gate start evidence rejected: ${observation.failure}",
            )
        }
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp023GateExecution(
                configuration.command,
                head,
                Kvp023GateExecutionPhase.COMPLETE,
            ),
        )
        when (val observation = observeKvp023GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp023GateExecutionPhase.COMPLETE,
        )) {
            is Kvp023GateEvidenceFileObservation.Observed -> Unit
            is Kvp023GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-023 gate completion evidence rejected: ${observation.failure}",
            )
        }
        revalidateExactHead(configuration.repositoryRoot, head)
    }

    private fun admittedConfiguration(): Kvp023GateTaskConfiguration = when (
        val result = Kvp023GateTaskConfiguration.refine(
            declaredCommand.get(),
            path,
            filter.includePatterns.toSet(),
            filter.excludePatterns.toSet(),
            repositoryRootPath.get(),
        )
    ) {
        is Kvp023GateTaskConfigurationRefinement.Admitted -> result.configuration
        is Kvp023GateTaskConfigurationRefinement.Rejected -> throw GradleException(
            "KVP-023 gate task configuration rejected: ${result.failure}",
        )
    }
}

private fun encodeKvp023GateExecution(document: Kvp023GateExecutionDocument) =
    KVP023_GATE_JSON.encodeToString(Kvp023GateExecutionDocument.serializer(), document) + "\n"

private fun canonicalHeadObservations(
    head: AuthorityGitRevision,
    phase: Kvp023GateExecutionPhase,
) = when (phase) {
    Kvp023GateExecutionPhase.STARTED -> listOf(
        Kvp023GateHeadObservationDocument(Kvp023GateHeadObservationStage.BEFORE, head.value),
    )
    Kvp023GateExecutionPhase.COMPLETE -> listOf(
        Kvp023GateHeadObservationDocument(Kvp023GateHeadObservationStage.BEFORE, head.value),
        Kvp023GateHeadObservationDocument(Kvp023GateHeadObservationStage.AFTER, head.value),
    )
}

private fun gateEvidenceReadRejected(path: Path) =
    Kvp023GateEvidenceFileObservation.Rejected(Kvp023GateEvidenceFileFailure.ReadRejected(path))

private fun gateRejected(failure: Kvp023GateExecutionFailure) =
    Kvp023GateExecutionAdmission.Rejected(failure)

private fun rejectedConfiguration(failure: Kvp023GateTaskConfigurationFailure) =
    Kvp023GateTaskConfigurationRefinement.Rejected(failure)

private const val KVP023_GATE_SCHEMA_VERSION = 1
private val KVP023_GATE_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
