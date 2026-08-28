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

enum class Kvp021GateCommand(
    val gateId: String,
    val declaredCommand: String,
    val selectorPattern: String,
    val dedicatedTaskPath: String,
    val canonicalTaskPath: String,
) {
    RED(
        "KVP-021-RED",
        "./gradlew :runtime:ide-read:test --tests \"*CancellableReadNegativeTest\"",
        "*CancellableReadNegativeTest",
        ":runtime:ide-read:cancellableReadNegativeGate",
        ":runtime:ide-read:test",
    ),
    GREEN(
        "KVP-021-GREEN",
        "./gradlew :runtime:ide-read:test --tests \"*CancellableReadTest\"",
        "*CancellableReadTest",
        ":runtime:ide-read:cancellableReadGate",
        ":runtime:ide-read:test",
    ),
}

@Serializable
private data class Kvp021GateExecutionDocument(
    val schemaVersion: Int,
    val taskId: String,
    val gateId: String,
    val declaredCommand: String,
    val canonicalTaskPath: String,
    val dedicatedTaskPath: String,
    val selectorPattern: String,
    val headObservations: List<Kvp021GateHeadObservationDocument>,
    val phase: Kvp021GateExecutionPhase,
)

@Serializable
private data class Kvp021GateHeadObservationDocument(
    val stage: Kvp021GateHeadObservationStage,
    val exactHead: String,
)

@Serializable private enum class Kvp021GateHeadObservationStage { BEFORE, AFTER }
@Serializable internal enum class Kvp021GateExecutionPhase { STARTED, COMPLETE }

internal enum class Kvp021GateExecutionFailure {
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

internal class AdmittedKvp021GateExecution private constructor(
    val canonicalDocument: String,
    val command: Kvp021GateCommand,
    val exactHead: AuthorityGitRevision,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp021GateCommand, AuthorityGitRevision,
         * Kvp021GateExecutionPhase) -> Kvp021GateExecutionAdmission`.
         *
         * Establishes canonical evidence for one exact KVP-021 selector, its dedicated Test task,
         * and one unchanged Git head. Expected failures remain closed
         * [Kvp021GateExecutionFailure] data. Raw JSON is extracted only at gate and receipt edges.
         */
        fun admit(
            raw: String,
            expectedCommand: Kvp021GateCommand,
            expectedHead: AuthorityGitRevision,
            expectedPhase: Kvp021GateExecutionPhase,
        ): Kvp021GateExecutionAdmission {
            val document = try {
                KVP021_GATE_JSON.decodeFromString(Kvp021GateExecutionDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return gateRejected(Kvp021GateExecutionFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return gateRejected(Kvp021GateExecutionFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP021_GATE_SCHEMA_VERSION -> return gateRejected(
                    Kvp021GateExecutionFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-021" || document.gateId != expectedCommand.gateId ->
                    return gateRejected(Kvp021GateExecutionFailure.IDENTITY_MISMATCH)
                document.declaredCommand != expectedCommand.declaredCommand -> return gateRejected(
                    Kvp021GateExecutionFailure.COMMAND_MISMATCH,
                )
                document.canonicalTaskPath != expectedCommand.canonicalTaskPath ||
                    document.dedicatedTaskPath != expectedCommand.dedicatedTaskPath ->
                    return gateRejected(Kvp021GateExecutionFailure.TASK_PATH_MISMATCH)
                document.selectorPattern != expectedCommand.selectorPattern -> return gateRejected(
                    Kvp021GateExecutionFailure.SELECTOR_MISMATCH,
                )
                document.headObservations != canonicalHeadObservations(
                    expectedHead,
                    expectedPhase,
                ) -> return gateRejected(
                    Kvp021GateExecutionFailure.HEAD_MISMATCH,
                )
                document.phase != expectedPhase -> return gateRejected(
                    Kvp021GateExecutionFailure.PHASE_MISMATCH,
                )
            }
            val canonical = encodeKvp021GateExecution(document)
            if (raw != canonical) return gateRejected(
                Kvp021GateExecutionFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp021GateExecutionAdmission.Admitted(
                AdmittedKvp021GateExecution(canonical, expectedCommand, expectedHead),
            )
        }
    }
}

internal sealed interface Kvp021GateExecutionAdmission {
    data class Admitted(val execution: AdmittedKvp021GateExecution) :
        Kvp021GateExecutionAdmission

    data class Rejected(val failure: Kvp021GateExecutionFailure) : Kvp021GateExecutionAdmission
}

internal fun canonicalKvp021GateExecution(
    command: Kvp021GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp021GateExecutionPhase,
): String = encodeKvp021GateExecution(
    Kvp021GateExecutionDocument(
        schemaVersion = KVP021_GATE_SCHEMA_VERSION,
        taskId = "KVP-021",
        gateId = command.gateId,
        declaredCommand = command.declaredCommand,
        canonicalTaskPath = command.canonicalTaskPath,
        dedicatedTaskPath = command.dedicatedTaskPath,
        selectorPattern = command.selectorPattern,
        headObservations = canonicalHeadObservations(head, phase),
        phase = phase,
    ),
)

internal sealed interface Kvp021GateEvidenceFileObservation {
    data class Observed(val execution: AdmittedKvp021GateExecution) :
        Kvp021GateEvidenceFileObservation

    data class Rejected(val failure: Kvp021GateEvidenceFileFailure) :
        Kvp021GateEvidenceFileObservation
}

internal sealed interface Kvp021GateEvidenceFileFailure {
    data class ReadRejected(val path: Path) : Kvp021GateEvidenceFileFailure
    data class AdmissionRejected(val failure: Kvp021GateExecutionFailure) :
        Kvp021GateEvidenceFileFailure
}

/**
 * Proof transition: `(Path, Kvp021GateCommand, AuthorityGitRevision,
 * Kvp021GateExecutionPhase) -> Kvp021GateEvidenceFileObservation`.
 *
 * Establishes readable canonical gate evidence at the expected phase and exact head. File and
 * admission failures remain closed [Kvp021GateEvidenceFileFailure] data.
 */
internal fun observeKvp021GateEvidence(
    path: Path,
    command: Kvp021GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp021GateExecutionPhase,
): Kvp021GateEvidenceFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return Kvp021GateEvidenceFileObservation.Rejected(
            Kvp021GateEvidenceFileFailure.ReadRejected(path),
        )
    } catch (_: SecurityException) {
        return Kvp021GateEvidenceFileObservation.Rejected(
            Kvp021GateEvidenceFileFailure.ReadRejected(path),
        )
    }
    return when (val admission = AdmittedKvp021GateExecution.admit(raw, command, head, phase)) {
        is Kvp021GateExecutionAdmission.Admitted ->
            Kvp021GateEvidenceFileObservation.Observed(admission.execution)
        is Kvp021GateExecutionAdmission.Rejected -> Kvp021GateEvidenceFileObservation.Rejected(
            Kvp021GateEvidenceFileFailure.AdmissionRejected(admission.failure),
        )
    }
}

private sealed interface Kvp021GateTaskConfigurationRefinement {
    data class Admitted(val configuration: Kvp021GateTaskConfiguration) :
        Kvp021GateTaskConfigurationRefinement

    data class Rejected(val failure: Kvp021GateTaskConfigurationFailure) :
        Kvp021GateTaskConfigurationRefinement
}

private enum class Kvp021GateTaskConfigurationFailure {
    UNKNOWN_COMMAND,
    INVALID_REPOSITORY_ROOT,
    TASK_PATH_MISMATCH,
    SELECTOR_MISMATCH,
}

private class Kvp021GateTaskConfiguration private constructor(
    val command: Kvp021GateCommand,
    val repositoryRoot: Path,
) {
    companion object {
        /**
         * Proof transition: raw Gradle task configuration ->
         * `Kvp021GateTaskConfigurationRefinement`.
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
        ): Kvp021GateTaskConfigurationRefinement {
            val command = Kvp021GateCommand.entries.singleOrNull {
                it.declaredCommand == declaredCommand
            } ?: return rejectedConfiguration(Kvp021GateTaskConfigurationFailure.UNKNOWN_COMMAND)
            if (taskPath != command.dedicatedTaskPath) return rejectedConfiguration(
                Kvp021GateTaskConfigurationFailure.TASK_PATH_MISMATCH,
            )
            if (includePatterns != setOf(command.selectorPattern) || excludePatterns.isNotEmpty()) {
                return rejectedConfiguration(Kvp021GateTaskConfigurationFailure.SELECTOR_MISMATCH)
            }
            val repositoryRoot = try {
                Path.of(rawRepositoryRoot).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return rejectedConfiguration(
                    Kvp021GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            } catch (_: SecurityException) {
                return rejectedConfiguration(
                    Kvp021GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            }
            if (!Files.isDirectory(repositoryRoot)) return rejectedConfiguration(
                Kvp021GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
            )
            return Kvp021GateTaskConfigurationRefinement.Admitted(
                Kvp021GateTaskConfiguration(command, repositoryRoot),
            )
        }
    }
}

@UntrackedTask(because = "Executes one exact-head KVP-021 selector and writes canonical evidence")
abstract class Kvp021CancellableReadGateTask : Test() {
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:Input abstract val declaredCommand: Property<String>
    @get:OutputFile abstract val gateEvidenceFile: RegularFileProperty

    /** Captures canonical STARTED evidence before the inherited Test actions execute. */
    fun beginGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp021GateExecution(
                configuration.command,
                head,
                Kvp021GateExecutionPhase.STARTED,
            ),
        )
    }

    /** Revalidates exact HEAD and replaces STARTED evidence only after Test success. */
    fun completeGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        when (val observation = observeKvp021GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp021GateExecutionPhase.STARTED,
        )) {
            is Kvp021GateEvidenceFileObservation.Observed -> Unit
            is Kvp021GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-021 gate start evidence rejected: ${observation.failure}",
            )
        }
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp021GateExecution(
                configuration.command,
                head,
                Kvp021GateExecutionPhase.COMPLETE,
            ),
        )
        when (val observation = observeKvp021GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp021GateExecutionPhase.COMPLETE,
        )) {
            is Kvp021GateEvidenceFileObservation.Observed -> Unit
            is Kvp021GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-021 gate completion evidence rejected: ${observation.failure}",
            )
        }
        revalidateExactHead(configuration.repositoryRoot, head)
    }

    private fun admittedConfiguration(): Kvp021GateTaskConfiguration = when (
        val refinement = Kvp021GateTaskConfiguration.refine(
            declaredCommand.get(),
            path,
            filter.includePatterns.toSet(),
            filter.excludePatterns.toSet(),
            repositoryRootPath.get(),
        )
    ) {
        is Kvp021GateTaskConfigurationRefinement.Admitted -> refinement.configuration
        is Kvp021GateTaskConfigurationRefinement.Rejected -> throw GradleException(
            "KVP-021 gate task configuration rejected: ${refinement.failure}",
        )
    }
}

private fun encodeKvp021GateExecution(document: Kvp021GateExecutionDocument) =
    KVP021_GATE_JSON.encodeToString(Kvp021GateExecutionDocument.serializer(), document) + "\n"

private fun canonicalHeadObservations(
    head: AuthorityGitRevision,
    phase: Kvp021GateExecutionPhase,
) = when (phase) {
    Kvp021GateExecutionPhase.STARTED -> listOf(
        Kvp021GateHeadObservationDocument(Kvp021GateHeadObservationStage.BEFORE, head.value),
    )
    Kvp021GateExecutionPhase.COMPLETE -> listOf(
        Kvp021GateHeadObservationDocument(Kvp021GateHeadObservationStage.BEFORE, head.value),
        Kvp021GateHeadObservationDocument(Kvp021GateHeadObservationStage.AFTER, head.value),
    )
}

private fun gateRejected(failure: Kvp021GateExecutionFailure) =
    Kvp021GateExecutionAdmission.Rejected(failure)

private fun rejectedConfiguration(failure: Kvp021GateTaskConfigurationFailure) =
    Kvp021GateTaskConfigurationRefinement.Rejected(failure)

private const val KVP021_GATE_SCHEMA_VERSION = 1
private val KVP021_GATE_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
