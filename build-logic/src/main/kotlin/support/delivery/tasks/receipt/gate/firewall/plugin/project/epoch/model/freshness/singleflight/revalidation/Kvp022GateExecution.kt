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

enum class Kvp022GateCommand(
    val gateId: String,
    val declaredCommand: String,
    val selectorPattern: String,
    val dedicatedTaskPath: String,
    val canonicalTaskPath: String,
) {
    RED(
        "KVP-022-RED",
        "./gradlew :runtime:ide-read:test --tests \"*EpochRevalidationNegativeTest\"",
        "*EpochRevalidationNegativeTest",
        ":runtime:ide-read:epochRevalidationNegativeGate",
        ":runtime:ide-read:test",
    ),
    GREEN(
        "KVP-022-GREEN",
        "./gradlew :runtime:ide-read:test --tests \"*EpochRevalidationTest\"",
        "*EpochRevalidationTest",
        ":runtime:ide-read:epochRevalidationGate",
        ":runtime:ide-read:test",
    ),
}

@Serializable
private data class Kvp022GateExecutionDocument(
    val schemaVersion: Int,
    val taskId: String,
    val gateId: String,
    val declaredCommand: String,
    val canonicalTaskPath: String,
    val dedicatedTaskPath: String,
    val selectorPattern: String,
    val headObservations: List<Kvp022GateHeadObservationDocument>,
    val phase: Kvp022GateExecutionPhase,
)

@Serializable
private data class Kvp022GateHeadObservationDocument(
    val stage: Kvp022GateHeadObservationStage,
    val exactHead: String,
)

@Serializable private enum class Kvp022GateHeadObservationStage { BEFORE, AFTER }
@Serializable internal enum class Kvp022GateExecutionPhase { STARTED, COMPLETE }

internal enum class Kvp022GateExecutionFailure {
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

internal class AdmittedKvp022GateExecution private constructor(
    val canonicalDocument: String,
    val command: Kvp022GateCommand,
    val exactHead: AuthorityGitRevision,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp022GateCommand, AuthorityGitRevision,
         * Kvp022GateExecutionPhase) -> Kvp022GateExecutionAdmission`.
         *
         * Establishes canonical evidence for one exact KVP-022 selector, its dedicated Test task,
         * and independently recorded BEFORE/AFTER head observations. Expected failures remain
         * closed [Kvp022GateExecutionFailure] data. Raw JSON is extracted only at gate and receipt
         * boundaries.
         */
        fun admit(
            raw: String,
            expectedCommand: Kvp022GateCommand,
            expectedHead: AuthorityGitRevision,
            expectedPhase: Kvp022GateExecutionPhase,
        ): Kvp022GateExecutionAdmission {
            val document = try {
                KVP022_GATE_JSON.decodeFromString(Kvp022GateExecutionDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return gateRejected(Kvp022GateExecutionFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return gateRejected(Kvp022GateExecutionFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP022_GATE_SCHEMA_VERSION -> return gateRejected(
                    Kvp022GateExecutionFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-022" || document.gateId != expectedCommand.gateId ->
                    return gateRejected(Kvp022GateExecutionFailure.IDENTITY_MISMATCH)
                document.declaredCommand != expectedCommand.declaredCommand -> return gateRejected(
                    Kvp022GateExecutionFailure.COMMAND_MISMATCH,
                )
                document.canonicalTaskPath != expectedCommand.canonicalTaskPath ||
                    document.dedicatedTaskPath != expectedCommand.dedicatedTaskPath ->
                    return gateRejected(Kvp022GateExecutionFailure.TASK_PATH_MISMATCH)
                document.selectorPattern != expectedCommand.selectorPattern -> return gateRejected(
                    Kvp022GateExecutionFailure.SELECTOR_MISMATCH,
                )
                document.headObservations != canonicalHeadObservations(
                    expectedHead,
                    expectedPhase,
                ) -> return gateRejected(Kvp022GateExecutionFailure.HEAD_MISMATCH)
                document.phase != expectedPhase -> return gateRejected(
                    Kvp022GateExecutionFailure.PHASE_MISMATCH,
                )
            }
            val canonical = encodeKvp022GateExecution(document)
            if (raw != canonical) return gateRejected(
                Kvp022GateExecutionFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp022GateExecutionAdmission.Admitted(
                AdmittedKvp022GateExecution(canonical, expectedCommand, expectedHead),
            )
        }
    }
}

internal sealed interface Kvp022GateExecutionAdmission {
    data class Admitted(val execution: AdmittedKvp022GateExecution) :
        Kvp022GateExecutionAdmission

    data class Rejected(val failure: Kvp022GateExecutionFailure) : Kvp022GateExecutionAdmission
}

internal fun canonicalKvp022GateExecution(
    command: Kvp022GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp022GateExecutionPhase,
): String = encodeKvp022GateExecution(
    Kvp022GateExecutionDocument(
        schemaVersion = KVP022_GATE_SCHEMA_VERSION,
        taskId = "KVP-022",
        gateId = command.gateId,
        declaredCommand = command.declaredCommand,
        canonicalTaskPath = command.canonicalTaskPath,
        dedicatedTaskPath = command.dedicatedTaskPath,
        selectorPattern = command.selectorPattern,
        headObservations = canonicalHeadObservations(head, phase),
        phase = phase,
    ),
)

internal sealed interface Kvp022GateEvidenceFileObservation {
    data class Observed(val execution: AdmittedKvp022GateExecution) :
        Kvp022GateEvidenceFileObservation

    data class Rejected(val failure: Kvp022GateEvidenceFileFailure) :
        Kvp022GateEvidenceFileObservation
}

internal sealed interface Kvp022GateEvidenceFileFailure {
    data class ReadRejected(val path: Path) : Kvp022GateEvidenceFileFailure
    data class AdmissionRejected(val failure: Kvp022GateExecutionFailure) :
        Kvp022GateEvidenceFileFailure
}

/**
 * Proof transition: `(Path, Kvp022GateCommand, AuthorityGitRevision,
 * Kvp022GateExecutionPhase) -> Kvp022GateEvidenceFileObservation`.
 *
 * Establishes readable canonical gate evidence at the expected phase and exact head.
 */
internal fun observeKvp022GateEvidence(
    path: Path,
    command: Kvp022GateCommand,
    head: AuthorityGitRevision,
    phase: Kvp022GateExecutionPhase,
): Kvp022GateEvidenceFileObservation {
    val raw = try {
        Files.readString(path)
    } catch (_: IOException) {
        return gateEvidenceReadRejected(path)
    } catch (_: SecurityException) {
        return gateEvidenceReadRejected(path)
    }
    return when (val admission = AdmittedKvp022GateExecution.admit(raw, command, head, phase)) {
        is Kvp022GateExecutionAdmission.Admitted ->
            Kvp022GateEvidenceFileObservation.Observed(admission.execution)
        is Kvp022GateExecutionAdmission.Rejected ->
            Kvp022GateEvidenceFileObservation.Rejected(
                Kvp022GateEvidenceFileFailure.AdmissionRejected(admission.failure),
            )
    }
}

private sealed interface Kvp022GateTaskConfigurationRefinement {
    data class Admitted(val configuration: Kvp022GateTaskConfiguration) :
        Kvp022GateTaskConfigurationRefinement

    data class Rejected(val failure: Kvp022GateTaskConfigurationFailure) :
        Kvp022GateTaskConfigurationRefinement
}

private enum class Kvp022GateTaskConfigurationFailure {
    UNKNOWN_COMMAND,
    INVALID_REPOSITORY_ROOT,
    TASK_PATH_MISMATCH,
    SELECTOR_MISMATCH,
}

private class Kvp022GateTaskConfiguration private constructor(
    val command: Kvp022GateCommand,
    val repositoryRoot: Path,
) {
    companion object {
        /**
         * Proof transition: raw Gradle task configuration ->
         * `Kvp022GateTaskConfigurationRefinement`.
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
        ): Kvp022GateTaskConfigurationRefinement {
            val command = Kvp022GateCommand.entries.singleOrNull {
                it.declaredCommand == declaredCommand
            } ?: return rejectedConfiguration(Kvp022GateTaskConfigurationFailure.UNKNOWN_COMMAND)
            if (taskPath != command.dedicatedTaskPath) return rejectedConfiguration(
                Kvp022GateTaskConfigurationFailure.TASK_PATH_MISMATCH,
            )
            if (includePatterns != setOf(command.selectorPattern) || excludePatterns.isNotEmpty()) {
                return rejectedConfiguration(Kvp022GateTaskConfigurationFailure.SELECTOR_MISMATCH)
            }
            val root = try {
                Path.of(rawRepositoryRoot).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return rejectedConfiguration(
                    Kvp022GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            } catch (_: SecurityException) {
                return rejectedConfiguration(
                    Kvp022GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
                )
            }
            if (!Files.isDirectory(root)) return rejectedConfiguration(
                Kvp022GateTaskConfigurationFailure.INVALID_REPOSITORY_ROOT,
            )
            return Kvp022GateTaskConfigurationRefinement.Admitted(
                Kvp022GateTaskConfiguration(command, root),
            )
        }
    }
}

@UntrackedTask(because = "Executes one exact-head KVP-022 selector and writes canonical evidence")
abstract class Kvp022EpochRevalidationGateTask : Test() {
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:Input abstract val declaredCommand: Property<String>
    @get:OutputFile abstract val gateEvidenceFile: RegularFileProperty

    /** Captures canonical STARTED evidence before the inherited Test actions execute. */
    fun beginGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp022GateExecution(
                configuration.command,
                head,
                Kvp022GateExecutionPhase.STARTED,
            ),
        )
    }

    /** Revalidates exact HEAD and replaces STARTED evidence only after Test success. */
    fun completeGateExecution() {
        val configuration = admittedConfiguration()
        val head = observeExactHead(configuration.repositoryRoot)
        when (val observation = observeKvp022GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp022GateExecutionPhase.STARTED,
        )) {
            is Kvp022GateEvidenceFileObservation.Observed -> Unit
            is Kvp022GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-022 gate start evidence rejected: ${observation.failure}",
            )
        }
        writeTextAtomically(
            gateEvidenceFile.get().asFile.toPath(),
            canonicalKvp022GateExecution(
                configuration.command,
                head,
                Kvp022GateExecutionPhase.COMPLETE,
            ),
        )
        when (val observation = observeKvp022GateEvidence(
            gateEvidenceFile.get().asFile.toPath(),
            configuration.command,
            head,
            Kvp022GateExecutionPhase.COMPLETE,
        )) {
            is Kvp022GateEvidenceFileObservation.Observed -> Unit
            is Kvp022GateEvidenceFileObservation.Rejected -> throw GradleException(
                "KVP-022 gate completion evidence rejected: ${observation.failure}",
            )
        }
        revalidateExactHead(configuration.repositoryRoot, head)
    }

    private fun admittedConfiguration(): Kvp022GateTaskConfiguration = when (
        val result = Kvp022GateTaskConfiguration.refine(
            declaredCommand.get(),
            path,
            filter.includePatterns.toSet(),
            filter.excludePatterns.toSet(),
            repositoryRootPath.get(),
        )
    ) {
        is Kvp022GateTaskConfigurationRefinement.Admitted -> result.configuration
        is Kvp022GateTaskConfigurationRefinement.Rejected -> throw GradleException(
            "KVP-022 gate task configuration rejected: ${result.failure}",
        )
    }
}

private fun encodeKvp022GateExecution(document: Kvp022GateExecutionDocument) =
    KVP022_GATE_JSON.encodeToString(Kvp022GateExecutionDocument.serializer(), document) + "\n"

private fun canonicalHeadObservations(
    head: AuthorityGitRevision,
    phase: Kvp022GateExecutionPhase,
) = when (phase) {
    Kvp022GateExecutionPhase.STARTED -> listOf(
        Kvp022GateHeadObservationDocument(Kvp022GateHeadObservationStage.BEFORE, head.value),
    )
    Kvp022GateExecutionPhase.COMPLETE -> listOf(
        Kvp022GateHeadObservationDocument(Kvp022GateHeadObservationStage.BEFORE, head.value),
        Kvp022GateHeadObservationDocument(Kvp022GateHeadObservationStage.AFTER, head.value),
    )
}

private fun gateEvidenceReadRejected(path: Path) =
    Kvp022GateEvidenceFileObservation.Rejected(Kvp022GateEvidenceFileFailure.ReadRejected(path))

private fun gateRejected(failure: Kvp022GateExecutionFailure) =
    Kvp022GateExecutionAdmission.Rejected(failure)

private fun rejectedConfiguration(failure: Kvp022GateTaskConfigurationFailure) =
    Kvp022GateTaskConfigurationRefinement.Rejected(failure)

private const val KVP022_GATE_SCHEMA_VERSION = 1
private val KVP022_GATE_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
