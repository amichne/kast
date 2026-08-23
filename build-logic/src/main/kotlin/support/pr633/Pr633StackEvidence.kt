package support.pr633

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.process.ExecOperations

internal sealed interface StackVerificationResult<out T> {
    data class Proven<T>(val value: T) : StackVerificationResult<T>

    data class Rejected(val failure: StackVerificationFailure) : StackVerificationResult<Nothing>
}

internal sealed interface StackVerificationFailure {
    data class MalformedEvent(val detail: String) : StackVerificationFailure
    data class MalformedProgram(val detail: String) : StackVerificationFailure
    data class MalformedPathPolicy(val detail: String) : StackVerificationFailure
    data class InvalidGitSha(val source: GitShaSource, val value: String) : StackVerificationFailure
    data class MissingScopedTasks(
        val firstTaskId: String,
        val additionalTaskIds: Set<String>,
    ) : StackVerificationFailure {
        val taskIds: Set<String> = setOf(firstTaskId) + additionalTaskIds
    }
    data class DuplicateScopedTasks(
        val firstTaskId: String,
        val additionalTaskIds: Set<String>,
    ) : StackVerificationFailure {
        val taskIds: Set<String> = setOf(firstTaskId) + additionalTaskIds
    }
    data class InvalidAllowedWrite(
        val taskId: String,
        val value: String,
        val reason: RepositoryLocationFailure,
    ) :
        StackVerificationFailure
    data class InvalidForbiddenPrefix(val value: String, val reason: RepositoryLocationFailure) :
        StackVerificationFailure
    data class ProgramPolicyMismatch(val programId: String, val policyProgramId: String) :
        StackVerificationFailure
    data class InvalidChangedPath(val value: String, val reason: RepositoryLocationFailure) :
        StackVerificationFailure
    data class UnexpectedPullRequest(val expected: Int, val observed: Int) : StackVerificationFailure
    data class UnexpectedBaseRef(val expected: String, val observed: String) : StackVerificationFailure
    data class HeadMismatch(val local: GitSha, val event: GitSha) : StackVerificationFailure
    data class GitCommandFailed(
        val arguments: List<String>,
        val exitValue: Int,
        val standardError: String,
    ) : StackVerificationFailure
    data class GitInvocationFailed(val arguments: List<String>, val detail: String) :
        StackVerificationFailure
    data class GitDiffCheckFailed(
        val arguments: List<String>,
        val exitValue: Int,
        val problems: String,
    ) : StackVerificationFailure
    data class InvalidGitOutput(val arguments: List<String>) : StackVerificationFailure
    data class MainNotAncestor(val mainRef: String, val headRef: String) : StackVerificationFailure
    data class ForbiddenChangedPaths(
        val firstPath: RepositoryPath,
        val additionalPaths: List<RepositoryPath>,
    ) : StackVerificationFailure {
        val paths: List<RepositoryPath> = listOf(firstPath) + additionalPaths
    }
    data class OutsideTaskScopes(
        val firstPath: RepositoryPath,
        val additionalPaths: List<RepositoryPath>,
    ) : StackVerificationFailure {
        val paths: List<RepositoryPath> = listOf(firstPath) + additionalPaths
    }
}

/**
 * Proof transition: `StackVerificationFailure -> String` at the Gradle reporting boundary.
 *
 * Projects one closed failure variant into actionable task output. Raw path and Git values may be
 * extracted only here, where Gradle requires a textual failure.
 */
internal fun StackVerificationFailure.render(): String = when (this) {
    is StackVerificationFailure.MalformedEvent -> "Malformed PR event: $detail"
    is StackVerificationFailure.MalformedProgram -> "Malformed PR 633 program: $detail"
    is StackVerificationFailure.MalformedPathPolicy -> "Malformed PR 633 path policy: $detail"
    is StackVerificationFailure.InvalidGitSha ->
        "${source.description} is not a full lowercase Git SHA: '$value'"
    is StackVerificationFailure.MissingScopedTasks -> "Program is missing scoped tasks: ${taskIds.sorted()}"
    is StackVerificationFailure.DuplicateScopedTasks -> "Program duplicates scoped tasks: ${taskIds.sorted()}"
    is StackVerificationFailure.InvalidAllowedWrite ->
        "$taskId has invalid allowedWrites entry '$value': ${reason.description}"
    is StackVerificationFailure.InvalidForbiddenPrefix ->
        "Path policy has invalid forbidden prefix '$value': ${reason.description}"
    is StackVerificationFailure.ProgramPolicyMismatch ->
        "Path policy belongs to '$policyProgramId', not '$programId'"
    is StackVerificationFailure.InvalidChangedPath ->
        "Git reported invalid path '$value': ${reason.description}"
    is StackVerificationFailure.UnexpectedPullRequest -> "Expected PR #$expected, received #$observed"
    is StackVerificationFailure.UnexpectedBaseRef -> "PR targets '$observed', not '$expected'"
    is StackVerificationFailure.HeadMismatch ->
        "Checked-out head ${local.value} differs from GitHub event head ${event.value}"
    is StackVerificationFailure.GitCommandFailed ->
        "git ${arguments.joinToString(" ")} failed ($exitValue): $standardError"
    is StackVerificationFailure.GitInvocationFailed ->
        "git ${arguments.joinToString(" ")} could not run: $detail"
    is StackVerificationFailure.GitDiffCheckFailed ->
        "git ${arguments.joinToString(" ")} found PR diff errors ($exitValue): $problems"
    is StackVerificationFailure.InvalidGitOutput ->
        "git ${arguments.joinToString(" ")} returned non-UTF-8 output"
    is StackVerificationFailure.MainNotAncestor -> "$mainRef is not an ancestor of $headRef"
    is StackVerificationFailure.ForbiddenChangedPaths ->
        "PR contains forbidden cleanup or legacy paths: ${paths.map(RepositoryPath::value).sorted()}"
    is StackVerificationFailure.OutsideTaskScopes ->
        "PR changed paths outside KTP633-010 through KTP633-070: ${paths.map(RepositoryPath::value).sorted()}"
}

internal enum class RepositoryLocationFailure(val description: String) {
    BLANK_OR_PADDED("blank or padded"),
    NOT_REPOSITORY_RELATIVE("not repository-relative"),
    NOT_NORMALIZED("not normalized"),
    EXPECTED_FILE("expected a file path"),
    EXPECTED_PREFIX("expected a directory prefix ending with '/'"),
}

internal enum class GitShaSource(val description: String) {
    EVENT_HEAD("event head"),
    CHECKED_OUT_HEAD("checked-out head"),
    MAIN_REF("resolved main ref"),
}

@JvmInline
internal value class GitSha private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> GitSha`.
         *
         * Establishes a full lowercase Git object identity. Expected failure is the closed
         * `StackVerificationFailure.InvalidGitSha`; raw extraction is permitted only at Git and
         * report boundaries.
         */
        fun parse(value: String, source: GitShaSource): StackVerificationResult<GitSha> =
            if (value.matches(Regex("[0-9a-f]{40}"))) {
                StackVerificationResult.Proven(GitSha(value))
            } else {
                StackVerificationResult.Rejected(StackVerificationFailure.InvalidGitSha(source, value))
            }
    }
}

internal class Pr633Event private constructor(
    val pullRequest: Int,
    val baseRef: String,
    val headSha: GitSha,
) {
    companion object {
        /**
         * Proof transition: `String -> Pr633Event`.
         *
         * Establishes the required pull-request number, base ref, and typed head SHA fields.
         * Expected failures are closed `StackVerificationFailure` variants; raw JSON extraction is
         * permitted only inside this event boundary.
         */
        fun parse(raw: String): StackVerificationResult<Pr633Event> = try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val pullRequest = root.getValue("pull_request").jsonObject
            val number = root.getValue("number").jsonPrimitive.content.toInt()
            val base = pullRequest.getValue("base").jsonObject.getValue("ref").jsonPrimitive.content
            val rawHead = pullRequest.getValue("head").jsonObject.getValue("sha").jsonPrimitive.content
            when (val head = GitSha.parse(rawHead, GitShaSource.EVENT_HEAD)) {
                is StackVerificationResult.Proven -> StackVerificationResult.Proven(
                    Pr633Event(number, base, head.value),
                )
                is StackVerificationResult.Rejected -> head
            }
        } catch (failure: RuntimeException) {
            StackVerificationResult.Rejected(StackVerificationFailure.MalformedEvent(failure.toString()))
        }
    }
}

internal class VerifiedStackIdentity private constructor(
    val pullRequest: Int,
    val baseRef: String,
    val headSha: GitSha,
) {
    companion object {
        /**
         * Proof transition: `Pr633Event + GitSha + expected PR/base -> VerifiedStackIdentity`.
         *
         * Establishes exact PR number, base branch, and event/local-head equality. Expected
         * failures are the closed identity variants of `StackVerificationFailure`; primitive
         * projection is permitted only in the final task report.
         */
        fun refine(
            event: Pr633Event,
            localHead: GitSha,
            expectedPullRequest: Int,
            expectedBaseRef: String,
        ): StackVerificationResult<VerifiedStackIdentity> = when {
            event.pullRequest != expectedPullRequest -> StackVerificationResult.Rejected(
                StackVerificationFailure.UnexpectedPullRequest(expectedPullRequest, event.pullRequest),
            )
            event.baseRef != expectedBaseRef -> StackVerificationResult.Rejected(
                StackVerificationFailure.UnexpectedBaseRef(expectedBaseRef, event.baseRef),
            )
            event.headSha != localHead -> StackVerificationResult.Rejected(
                StackVerificationFailure.HeadMismatch(localHead, event.headSha),
            )
            else -> StackVerificationResult.Proven(
                VerifiedStackIdentity(event.pullRequest, event.baseRef, localHead),
            )
        }
    }
}

internal data class GitCommand(val arguments: List<String>)

internal data class GitObservation(
    val command: GitCommand,
    val exitValue: Int,
    val standardOutput: ByteArray,
    val standardError: ByteArray,
)

internal class DecodedGitObservation private constructor(
    val command: GitCommand,
    val exitValue: Int,
    val standardOutput: String,
    val standardError: String,
) {
    /**
     * Projects an already decoded non-success observation to the closed Git command failure.
     *
     * The command-specific caller first distinguishes its semantic exit codes. This projection
     * preserves the remaining exit and diagnostic data without re-reading raw bytes.
     */
    fun commandFailure(): StackVerificationFailure.GitCommandFailed =
        StackVerificationFailure.GitCommandFailed(
            command.arguments,
            exitValue,
            standardError.trim(),
        )

    companion object {
        /**
         * Proof transition: `GitObservation -> DecodedGitObservation`.
         *
         * Establishes strict UTF-8 decoding for both standard output and standard error while
         * preserving the command and exit value. Expected failure is the closed
         * `StackVerificationFailure.InvalidGitOutput`; raw byte extraction ends here.
         */
        fun refine(observation: GitObservation): StackVerificationResult<DecodedGitObservation> =
            try {
                StackVerificationResult.Proven(
                    DecodedGitObservation(
                        observation.command,
                        observation.exitValue,
                        observation.standardOutput.decodeToString(throwOnInvalidSequence = true),
                        observation.standardError.decodeToString(throwOnInvalidSequence = true),
                    ),
                )
            } catch (_: CharacterCodingException) {
                StackVerificationResult.Rejected(
                    StackVerificationFailure.InvalidGitOutput(observation.command.arguments),
                )
            }
    }
}

/**
 * Proof transition: `GitCommand + repository directory -> GitObservation`.
 *
 * Captures process exit, output, and error bytes without interpreting command success. Expected
 * process-start failure is `StackVerificationFailure.GitInvocationFailed`; raw bytes are consumed
 * only by `DecodedGitObservation.refine`.
 */
internal fun observeGit(
    execOperations: ExecOperations,
    repositoryDirectory: File,
    command: GitCommand,
): StackVerificationResult<GitObservation> = try {
    val standardOutput = ByteArrayOutputStream()
    val standardError = ByteArrayOutputStream()
    val result = execOperations.exec {
        workingDir(repositoryDirectory)
        commandLine(listOf("git") + command.arguments)
        this.standardOutput = standardOutput
        this.errorOutput = standardError
        isIgnoreExitValue = true
    }
    StackVerificationResult.Proven(
        GitObservation(
            command,
            result.exitValue,
            standardOutput.toByteArray(),
            standardError.toByteArray(),
        ),
    )
} catch (failure: RuntimeException) {
    StackVerificationResult.Rejected(
        StackVerificationFailure.GitInvocationFailed(command.arguments, failure.toString()),
    )
}

/**
 * Proof transition: `GitCommand + repository directory -> DecodedGitObservation`.
 *
 * Composes process observation with strict decoding so command-specific refinements cannot forget
 * to validate either output stream. Expected failures are the closed Git invocation and invalid
 * output variants of `StackVerificationFailure`.
 */
internal fun observeDecodedGit(
    execOperations: ExecOperations,
    repositoryDirectory: File,
    command: GitCommand,
): StackVerificationResult<DecodedGitObservation> =
    when (val observation = observeGit(execOperations, repositoryDirectory, command)) {
        is StackVerificationResult.Proven -> DecodedGitObservation.refine(observation.value)
        is StackVerificationResult.Rejected -> observation
    }

/**
 * Proof transition: `Git ref String -> GitSha` through the repository Git boundary.
 *
 * Establishes successful `rev-parse` execution, strict UTF-8 output, and a full lowercase object
 * identity. Expected failures are the closed Git invocation/output/command/SHA variants; the raw
 * ref is used only as a Git command argument.
 */
internal fun resolveGitSha(
    execOperations: ExecOperations,
    repositoryDirectory: File,
    ref: String,
    source: GitShaSource,
): StackVerificationResult<GitSha> {
    val command = GitCommand(listOf("rev-parse", ref))
    return when (
        val observation = observeDecodedGit(execOperations, repositoryDirectory, command)
    ) {
        is StackVerificationResult.Rejected -> observation
        is StackVerificationResult.Proven -> if (observation.value.exitValue == 0) {
            GitSha.parse(observation.value.standardOutput.trim(), source)
        } else {
            StackVerificationResult.Rejected(observation.value.commandFailure())
        }
    }
}
