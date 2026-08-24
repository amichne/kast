package support.pr633

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

/**
 * Proof transition: `GitHub event + PR program + deny-list + Git graph -> StackVerificationReport`.
 *
 * Establishes the exact PR number, base, head, main ancestry, and admission of every added,
 * changed, renamed, or deleted path by KTP633-010 through KTP633-070. Expected failures are the
 * closed `StackVerificationFailure` variants. Raw JSON, Git output, and primitive report values
 * are extracted only at this Gradle task boundary.
 *
 * CI must check out `github.event.pull_request.head.sha` with full history before this task runs.
 * Local runs pass an equivalent event document with `-Ppr633EventFile=...`.
 */
@UntrackedTask(because = "Reads mutable local and remote Git refs")
abstract class VerifyPr633StackTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val eventFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val programFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pathPolicyFile: RegularFileProperty

    @get:Input
    abstract val expectedPullRequest: Property<Int>

    @get:Input
    abstract val expectedBaseRef: Property<String>

    @get:Input
    abstract val mainGitRef: Property<String>

    @get:Input
    abstract val headGitRef: Property<String>

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * Proof transition: `configured stack boundary inputs -> StackVerificationReport`.
     *
     * Consumes exact event, SHA, ancestry, and path-admission capabilities and materializes their
     * values only in the deterministic report. Closed failures reach Gradle through the sole
     * boundary extractor.
     */
    @TaskAction
    fun verify() {
        val event = Pr633Event.parse(eventFile.get().asFile.readText()).atGradleBoundary()
        val pathAdmission = ChangedPathAdmissionPolicy.parse(
            RawPathAdmissionAuthorities(
                program = programFile.get().asFile.readText(),
                pathPolicy = pathPolicyFile.get().asFile.readText(),
            ),
        ).atGradleBoundary()
        val localHead = resolveGitSha(
            execOperations,
            repositoryDirectory.get().asFile,
            headGitRef.get(),
            GitShaSource.CHECKED_OUT_HEAD,
        ).atGradleBoundary()
        val mainHead = resolveGitSha(
            execOperations,
            repositoryDirectory.get().asFile,
            mainGitRef.get(),
            GitShaSource.MAIN_REF,
        ).atGradleBoundary()
        val identity = VerifiedStackIdentity.refine(
            event,
            localHead,
            expectedPullRequest.get(),
            expectedBaseRef.get(),
        ).atGradleBoundary()
        val ancestry = proveMainAncestry(mainHead, localHead).atGradleBoundary()
        val changedPaths = readChangedPaths(mainHead, localHead).atGradleBoundary()
        val admittedPaths = pathAdmission.admit(changedPaths.values).atGradleBoundary()

        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        val report = StackVerificationReportDocument(
            schemaVersion = 1,
            pullRequest = identity.pullRequest,
            baseRef = identity.baseRef,
            headSha = ancestry.headSha.value,
            mainRef = ancestry.mainRef,
            mainSha = ancestry.mainSha.value,
            changedPaths = admittedPaths.sortedValues(),
            status = Pr633EvidenceStatus.PASSED,
        )
        output.writeText(
            pr633EvidenceJson.encodeToString(StackVerificationReportDocument.serializer(), report) + "\n",
        )
    }

    /**
     * Proof transition: `Git merge-base observation -> MainAncestryProof`.
     *
     * Establishes that the configured main ref is an ancestor of the configured head ref.
     * Expected failures are closed non-ancestry or Git-command variants; no raw output escapes.
     */
    private fun proveMainAncestry(
        mainHead: GitSha,
        localHead: GitSha,
    ): StackVerificationResult<MainAncestryProof> {
        val command = GitCommand(
            listOf("merge-base", "--is-ancestor", mainHead.value, localHead.value),
        )
        return when (val observation = observeGit(command)) {
            is StackVerificationResult.Rejected -> observation
            is StackVerificationResult.Proven -> MainAncestryProof.refine(
                observation.value,
                mainGitRef.get(),
                mainHead,
                localHead,
            )
        }
    }

    /**
     * Proof transition: `Git tree diff -> RawChangedPaths`.
     *
     * Establishes one successful, NUL-delimited projection containing both sides of renames and
     * every Git change kind, including deletions. Expected failures are closed Git execution/output
     * variants; raw path strings may be extracted only by `ChangedPathAdmissionPolicy.admit`.
     */
    private fun readChangedPaths(
        mainHead: GitSha,
        localHead: GitSha,
    ): StackVerificationResult<RawChangedPaths> {
        val command = GitCommand(
            listOf(
                "diff",
                "--no-renames",
                "--name-only",
                "--diff-filter=ACDMRTUXB",
                "-z",
                "${mainHead.value}...${localHead.value}",
            ),
        )
        return when (val observation = observeGit(command)) {
            is StackVerificationResult.Rejected -> observation
            is StackVerificationResult.Proven -> {
                if (observation.value.exitValue != 0) {
                    StackVerificationResult.Rejected(observation.value.commandFailure())
                } else {
                    StackVerificationResult.Proven(
                        RawChangedPaths(
                            observation.value.standardOutput.split('\u0000')
                                .filter(String::isNotEmpty),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Proof transition: `GitCommand -> DecodedGitObservation` for this repository.
     *
     * Establishes process observation and strict decoding of both streams. Expected failures are
     * the closed Git invocation and output variants of `StackVerificationFailure`.
     */
    private fun observeGit(command: GitCommand): StackVerificationResult<DecodedGitObservation> =
        observeDecodedGit(execOperations, repositoryDirectory.get().asFile, command)
}

/**
 * Proof transition: `origin/main + HEAD Git tree range -> VerifiedPr633GitDiff`.
 *
 * Establishes that `git diff --check` succeeds for the committed admitted PR target range, not
 * merely the mutable worktree. Expected failures are closed `StackVerificationFailure` variants;
 * Git diagnostics are projected to text only at the Gradle task boundary.
 */
@UntrackedTask(because = "Reads mutable local and remote Git refs")
abstract class VerifyPr633GitDiffTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val mainGitRef: Property<String>

    @get:Input
    abstract val headGitRef: Property<String>

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * Proof transition: `configured Git refs -> VerifiedPr633GitDiff` at the Gradle boundary.
     *
     * Executes the typed range refinement and projects only its closed expected failure variants
     * into Gradle's exception protocol.
     */
    @TaskAction
    fun verify() {
        val verified = verifyAdmittedRange().atGradleBoundary()
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        val report = GitDiffVerificationReportDocument(
            schemaVersion = 1,
            mainSha = verified.mainSha.value,
            headSha = verified.headSha.value,
            status = Pr633EvidenceStatus.PASSED,
        )
        output.writeText(
            pr633EvidenceJson.encodeToString(GitDiffVerificationReportDocument.serializer(), report) + "\n",
        )
    }

    /**
     * Proof transition: `Git diff --check observation -> VerifiedPr633GitDiff`.
     *
     * Establishes a zero exit for `mainGitRef...headGitRef`. A nonzero exit carries the decoded
     * Git problems in `StackVerificationFailure.GitDiffCheckFailed`; invalid UTF-8 remains the
     * closed `StackVerificationFailure.InvalidGitOutput` state.
     */
    private fun verifyAdmittedRange(): StackVerificationResult<VerifiedPr633GitDiff> {
        val mainHead = when (
            val resolved = resolveGitSha(
                execOperations,
                repositoryDirectory.get().asFile,
                mainGitRef.get(),
                GitShaSource.MAIN_REF,
            )
        ) {
            is StackVerificationResult.Proven -> resolved.value
            is StackVerificationResult.Rejected -> return resolved
        }
        val localHead = when (
            val resolved = resolveGitSha(
                execOperations,
                repositoryDirectory.get().asFile,
                headGitRef.get(),
                GitShaSource.CHECKED_OUT_HEAD,
            )
        ) {
            is StackVerificationResult.Proven -> resolved.value
            is StackVerificationResult.Rejected -> return resolved
        }
        val command = GitCommand(
            listOf("diff", "--check", "${mainHead.value}...${localHead.value}"),
        )
        return when (
            val observation = observeDecodedGit(
                execOperations,
                repositoryDirectory.get().asFile,
                command,
            )
        ) {
            is StackVerificationResult.Rejected -> observation
            is StackVerificationResult.Proven -> VerifiedPr633GitDiff.refine(
                observation.value,
                mainHead,
                localHead,
            )
        }
    }
}

private data class RawChangedPaths(val values: List<String>)

private class MainAncestryProof private constructor(
    val mainRef: String,
    val mainSha: GitSha,
    val headSha: GitSha,
) {
    companion object {
        /**
         * Proof transition: `DecodedGitObservation + GitSha range -> MainAncestryProof`.
         *
         * Establishes exit zero from `merge-base --is-ancestor`. Expected failures are the closed
         * non-ancestry and Git-command variants; the returned capability carries the proven main.
         */
        fun refine(
            observation: DecodedGitObservation,
            mainRef: String,
            mainSha: GitSha,
            headSha: GitSha,
        ): StackVerificationResult<MainAncestryProof> = when (observation.exitValue) {
            0 -> StackVerificationResult.Proven(MainAncestryProof(mainRef, mainSha, headSha))
            1 -> StackVerificationResult.Rejected(
                StackVerificationFailure.MainNotAncestor(mainSha.value, headSha.value),
            )
            else -> StackVerificationResult.Rejected(observation.commandFailure())
        }
    }
}

private class VerifiedPr633GitDiff private constructor(
    val mainSha: GitSha,
    val headSha: GitSha,
) {
    companion object {
        /**
         * Proof transition: `DecodedGitObservation + GitSha range -> VerifiedPr633GitDiff`.
         *
         * Establishes exit zero from `git diff --check main...head` and carries that exact range
         * into the output report. Expected failure is `GitDiffCheckFailed`.
         */
        fun refine(
            observation: DecodedGitObservation,
            mainSha: GitSha,
            headSha: GitSha,
        ): StackVerificationResult<VerifiedPr633GitDiff> =
            if (observation.exitValue == 0) {
                StackVerificationResult.Proven(VerifiedPr633GitDiff(mainSha, headSha))
            } else {
                StackVerificationResult.Rejected(
                    StackVerificationFailure.GitDiffCheckFailed(
                        observation.command.arguments,
                        observation.exitValue,
                        listOf(
                            observation.standardOutput.trim(),
                            observation.standardError.trim(),
                        ).filter(String::isNotEmpty).joinToString("\n"),
                    ),
                )
            }
    }
}

/**
 * Proof transition: `StackVerificationResult<T> -> T` at the Gradle task boundary.
 *
 * Preserves every successful refinement and projects closed expected failures to Gradle's required
 * exception protocol. No core parser or admission rule may call this boundary extractor.
 */
private fun <T> StackVerificationResult<T>.atGradleBoundary(): T = when (this) {
    is StackVerificationResult.Proven -> value
    is StackVerificationResult.Rejected -> error(failure.render())
}
