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

enum class Kvp024GateCommand(
    val gateId: String,
    val declaredCommand: String,
    val selectorPattern: String,
    val dedicatedTaskPath: String,
    val canonicalTaskPaths: List<String>,
) {
    RED(
        "KVP-024-RED",
        "./gradlew :ide-plugin:test --tests \"*IdeEndpointPublicationNegativeTest\"",
        "*IdeEndpointPublicationNegativeTest",
        ":ide-plugin:verifyIdeEndpointPublicationNegative",
        listOf(":ide-plugin:test"),
    ),
    GREEN(
        "KVP-024-GREEN",
        "./gradlew :ide-plugin:test --tests \"*IdeEndpointPublicationTest\"",
        "*IdeEndpointPublicationTest",
        ":ide-plugin:verifyIdeEndpointPublication",
        listOf(":ide-plugin:test"),
    ),
}

@Serializable
private data class Kvp024GateExecutionDocument(
    val schemaVersion: Int,
    val taskId: String,
    val gateId: String,
    val declaredCommand: String,
    val canonicalTaskPaths: List<String>,
    val dedicatedTaskPath: String,
    val selectorPattern: String,
    val headObservations: List<Kvp024GateHeadObservationDocument>,
    val phase: Kvp024GateExecutionPhase,
)

@Serializable
private data class Kvp024GateHeadObservationDocument(
    val stage: Kvp024GateHeadObservationStage,
    val exactHead: String,
)

@Serializable private enum class Kvp024GateHeadObservationStage { BEFORE, AFTER }
@Serializable internal enum class Kvp024GateExecutionPhase { STARTED, COMPLETE }

internal enum class Kvp024GateExecutionFailure {
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

internal class AdmittedKvp024GateExecution private constructor(
    val canonicalDocument: String,
    val command: Kvp024GateCommand,
    val exactHead: AuthorityGitRevision,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp024GateCommand, AuthorityGitRevision,
         * Kvp024GateExecutionPhase) -> Kvp024GateExecutionAdmission`.
         *
         * Establishes canonical evidence for one exact KVP-024 selector, its dedicated Test task,
         * and independently recorded BEFORE/AFTER head observations. Expected failures remain
         * closed [Kvp024GateExecutionFailure] data. Raw JSON is extracted only at gate and receipt
         * boundaries.
         */
        fun admit(
            raw: String,
            expectedCommand: Kvp024GateCommand,
            expectedHead: AuthorityGitRevision,
            expectedPhase: Kvp024GateExecutionPhase,
        ): Kvp024GateExecutionAdmission {
            val document = try {
                KVP024_GATE_JSON.decodeFromString(Kvp024GateExecutionDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return gateRejected(Kvp024GateExecutionFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return gateRejected(Kvp024GateExecutionFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP024_GATE_SCHEMA_VERSION -> return gateRejected(
                    Kvp024GateExecutionFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-024" || document.gateId != expectedCommand.gateId ->
                    return gateRejected(Kvp024GateExecutionFailure.IDENTITY_MISMATCH)
                document.declaredCommand != expectedCommand.declaredCommand -> return gateRejected(
                    Kvp024GateExecutionFailure.COMMAND_MISMATCH,
                )
                document.canonicalTaskPaths != expectedCommand.canonicalTaskPaths ||
                    document.dedicatedTaskPath != expectedCommand.dedicatedTaskPath ->
                    return gateRejected(Kvp024GateExecutionFailure.TASK_PATH_MISMATCH)
                document.selectorPattern != expectedCommand.selectorPattern -> return gateRejected(
                    Kvp024GateExecutionFailure.SELECTOR_MISMATCH,
                )
                document.headObservations != canonicalHeadObservations(
                    expectedHead,
                    expectedPhase,
                ) -> return gateRejected(Kvp024GateExecutionFailure.HEAD_MISMATCH)
                document.phase != expectedPhase -> return gateRejected(
                    Kvp024GateExecutionFailure.PHASE_MISMATCH,
                )
            }
            val canonical = encodeKvp024GateExecution(document)
            if (raw != canonical) return gateRejected(
                Kvp024GateExecutionFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp024GateExecutionAdmission.Admitted(
                AdmittedKvp024GateExecution(canonical, expectedCommand, expectedHead),
            )
        }
    }
}

internal sealed interface Kvp024GateExecutionAdmission {
    data class Admitted(val execution: AdmittedKvp024GateExecution) :
        Kvp024GateExecutionAdmission

    data class Rejected(val failure: Kvp024GateExecutionFailure) : Kvp024GateExecutionAdmission
}

internal fun canonicalKvp024GateExecution(
    command: Kvp024GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp024GateExecutionPhase,
): String = encodeKvp024GateExecution(
    Kvp024GateExecutionDocument(
        schemaVersion = KVP024_GATE_SCHEMA_VERSION,
        taskId = "KVP-024",
        gateId = command.gateId,
        declaredCommand = command.declaredCommand,
        canonicalTaskPaths = command.canonicalTaskPaths,
        dedicatedTaskPath = command.dedicatedTaskPath,
        selectorPattern = command.selectorPattern,
        headObservations = canonicalHeadObservations(head, phase),
        phase = phase,
    ),
)

internal sealed interface Kvp024GateEvidenceFileObservation {
    data class Observed(val execution: AdmittedKvp024GateExecution) :
        Kvp024GateEvidenceFileObservation

    data class Rejected(val failure: Kvp024GateEvidenceFileFailure) :
        Kvp024GateEvidenceFileObservation
}

internal sealed interface Kvp024GateEvidenceFileFailure {
    data class ReadRejected(val path: Path) : Kvp024GateEvidenceFileFailure
    data class AdmissionRejected(val failure: Kvp024GateExecutionFailure) :
        Kvp024GateEvidenceFileFailure
}

/**
 * Proof transition: `(Path, Kvp024GateCommand, AuthorityGitRevision,
 * Kvp024GateExecutionPhase) -> Kvp024GateEvidenceFileObservation`.
 *
 * Establishes readable canonical gate evidence at the expected phase and exact head.
 */
internal fun observeKvp024GateEvidence(
    path: Path,
    command: Kvp024GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp024GateExecutionPhase,
): Kvp024GateEvidenceFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return gateEvidenceReadRejected(path)
    } catch (_: SecurityException) {
        return gateEvidenceReadRejected(path)
    }
    return when (val admission = AdmittedKvp024GateExecution.admit(raw, command, head, phase)) {
        is Kvp024GateExecutionAdmission.Admitted ->
            Kvp024GateEvidenceFileObservation.Observed(admission.execution)
        is Kvp024GateExecutionAdmission.Rejected ->
            Kvp024GateEvidenceFileObservation.Rejected(
                Kvp024GateEvidenceFileFailure.AdmissionRejected(admission.failure),
            )
    }
}

private sealed interface Kvp024GateTaskConfigurationRefinement {
    data class Admitted(val configuration: Kvp024GateTaskConfiguration) :
        Kvp024GateTaskConfigurationRefinement

    data class Rejected(val failure: Kvp024GateTaskConfigurationFailure) :
        Kvp024GateTaskConfigurationRefinement
}

private enum class Kvp024GateTaskConfigurationFailure {
    UNKNOWN_COMMAND,
    INVALID_REPOSITORY_ROOT,
    TASK_PATH_MISMATCH,
    SELECTOR_MISMATCH,
}

private class Kvp024GateTaskConfiguration private constructor(
    val command: Kvp024GateCommand,
    val repositoryRoot: Path,
) {
    companion object {
        /**
         * Proof transition: raw Gradle task configuration ->
         * `Kvp024GateTaskConfigurationRefinement`.
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
        ): Kvp024GateTaskConfigurationRefinement {
            val command = Kvp024GateCommand.entries.singleOrNull {
                it.declaredCommand == declaredCommand
            } ?: return rejectedConfiguration(Kvp024GateTaskConfigurationFailure.UNKNOWN_COMMAND)
            if (taskPath != command.dedicatedTaskPath) return rejectedConfiguration(
                Kvp024GateTaskConfigurationFailure.TASK_PATH_MISMATCH,
            )
            if (includePatterns != setOf(command.selectorPattern) || excludePatterns.isNotEmpty()) {
                return rejectedConfiguration(Kvp024GateTaskConfigurationFailure.SELECTOR_MISMATCH)
            }
            val root = try {
                Path.of(rawRepositoryRoot).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return rejectedConfiguration(
                    Kvp024GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            } catch (_: SecurityException) {
                return rejectedConfiguration(
                    Kvp024GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            }
            if (!Files.isDirectory(root)) return rejectedConfiguration(
                Kvp024GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
            )
            return Kvp024GateTaskConfigurationRefinement.Admitted(
                Kvp024GateTaskConfiguration(command, root),
            )
        }
    }
}

@UntrackedTask(because = "Executes one exact-head KVP-024 selector and writes canonical evidence")
abstract class Kvp024IdeEndpointPublicationGateTask : Test() {
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:Input abstract val declaredCommand: Property<String>
    @get:OutputFile abstract val gateEvidenceFile: RegularFileProperty

    /** Captures canonical STARTED evidence before the inherited Test actions execute. */
    fun beginGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp024GateExecution(
                configuration.command,
                head,
                Kvp024GateExecutionPhase.STARTED,
            ),
        )
    }

    /** Revalidates exact HEAD and replaces STARTED evidence only after Test success. */
    fun completeGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        when (val observation = observeKvp024GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp024GateExecutionPhase.STARTED,
        )) {
            is Kvp024GateEvidenceFileObservation.Observed -> Unit
            is Kvp024GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-024 gate start evidence rejected: ${observation.failure}",
            )
        }
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp024GateExecution(
                configuration.command,
                head,
                Kvp024GateExecutionPhase.COMPLETE,
            ),
        )
        when (val observation = observeKvp024GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp024GateExecutionPhase.COMPLETE,
        )) {
            is Kvp024GateEvidenceFileObservation.Observed -> Unit
            is Kvp024GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-024 gate completion evidence rejected: ${observation.failure}",
            )
        }
        revalidateExactHead(configuration.repositoryRoot, head)
    }

    private fun admittedConfiguration(): Kvp024GateTaskConfiguration = when (
        val result = Kvp024GateTaskConfiguration.refine(
            declaredCommand.get(),
            path,
            filter.includePatterns.toSet(),
            filter.excludePatterns.toSet(),
            repositoryRootPath.get(),
        )
    ) {
        is Kvp024GateTaskConfigurationRefinement.Admitted -> result.configuration
        is Kvp024GateTaskConfigurationRefinement.Rejected -> throw GradleException(
            "KVP-024 gate task configuration rejected: ${result.failure}",
        )
    }
}

private fun encodeKvp024GateExecution(document: Kvp024GateExecutionDocument) =
    KVP024_GATE_JSON.encodeToString(Kvp024GateExecutionDocument.serializer(), document) + "\n"

private fun canonicalHeadObservations(
    head: AuthorityGitRevision,
    phase: Kvp024GateExecutionPhase,
) = when (phase) {
    Kvp024GateExecutionPhase.STARTED -> listOf(
        Kvp024GateHeadObservationDocument(Kvp024GateHeadObservationStage.BEFORE, head.value),
    )
    Kvp024GateExecutionPhase.COMPLETE -> listOf(
        Kvp024GateHeadObservationDocument(Kvp024GateHeadObservationStage.BEFORE, head.value),
        Kvp024GateHeadObservationDocument(Kvp024GateHeadObservationStage.AFTER, head.value),
    )
}

private fun gateEvidenceReadRejected(path: Path) =
    Kvp024GateEvidenceFileObservation.Rejected(Kvp024GateEvidenceFileFailure.ReadRejected(path))

private fun gateRejected(failure: Kvp024GateExecutionFailure) =
    Kvp024GateExecutionAdmission.Rejected(failure)

private fun rejectedConfiguration(failure: Kvp024GateTaskConfigurationFailure) =
    Kvp024GateTaskConfigurationRefinement.Rejected(failure)

private const val KVP024_GATE_SCHEMA_VERSION = 1
private val KVP024_GATE_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
