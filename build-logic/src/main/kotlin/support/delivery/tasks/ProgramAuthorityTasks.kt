package support.delivery

import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Observes live Git metadata and declared authority artifacts")
abstract class GenerateKastVfsPassiveAuthorityTask : DefaultTask() {
    @get:Input
    abstract val repositoryRootPath: Property<String>

    @get:Input
    abstract val baseRevision: Property<String>

    @get:Input
    abstract val programFingerprint: Property<String>

    @get:Input
    abstract val requirementFingerprint: Property<String>

    @get:Input
    abstract val sourceDigests: MapProperty<String, String>

    @get:Input
    abstract val allowedReads: ListProperty<String>

    @get:Input
    abstract val candidatePaths: ListProperty<String>

    @get:OutputFile
    abstract val authorityFile: RegularFileProperty

    @get:OutputFile
    abstract val contradictionFile: RegularFileProperty

    @TaskAction
    fun generateAuthority() {
        val repositoryRoot = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val exactHead = observeExactHead(repositoryRoot)
        val expectation = configuredExpectation(
            baseRevision.get(),
            exactHead,
            programFingerprint.get(),
            requirementFingerprint.get(),
            sourceDigests.get(),
            allowedReads.get(),
        ).orReject("configured authority expectation")
        val candidates = when (
            val selected = expectation.selectDeclaredSourceCandidates(candidatePaths.get())
        ) {
            is DeclaredSourceCandidateSelection.Complete -> selected.candidates
            is DeclaredSourceCandidateSelection.Rejected -> {
                rejectAuthority("authority source candidate", "MALFORMED_PATH:${selected.path.value}")
            }
        }
        val generation = generateProgramAuthority(expectation, candidates) {
            observeAuthoritySource(repositoryRoot, it)
        }
        val authority = when (generation) {
            is ProgramAuthorityGeneration.Complete -> generation.authority
            is ProgramAuthorityGeneration.Rejected -> {
                rejectAuthority("authority generation", generation.failure.render())
            }
        }
        revalidateExactHead(repositoryRoot, exactHead)
        writeTextAtomically(
            authorityFile.get().asFile.toPath(),
            encodeProgramAuthorityDocument(authority.document),
        )
        writeTextAtomically(
            contradictionFile.get().asFile.toPath(),
            authority.document.contradictionProjection(),
        )
        revalidateExactHead(repositoryRoot, exactHead)
    }
}

@UntrackedTask(because = "Observes live Git metadata and declared authority artifacts")
abstract class VerifyKastVfsPassiveAuthorityTask : DefaultTask() {
    @get:Input
    abstract val repositoryRootPath: Property<String>

    @get:Input
    abstract val authorityFilePath: Property<String>

    @get:Input
    abstract val contradictionFilePath: Property<String>

    @get:Input
    abstract val baseRevision: Property<String>

    @get:Input
    abstract val programFingerprint: Property<String>

    @get:Input
    abstract val requirementFingerprint: Property<String>

    @get:Input
    abstract val sourceDigests: MapProperty<String, String>

    @get:Input
    abstract val allowedReads: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verifyAuthority() {
        val repositoryRoot = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val exactHead = observeExactHead(repositoryRoot)
        val expectation = configuredExpectation(
            baseRevision.get(),
            exactHead,
            programFingerprint.get(),
            requirementFingerprint.get(),
            sourceDigests.get(),
            allowedReads.get(),
        ).orReject("configured authority expectation")
        val authorityText = readBoundaryFile(
            Path.of(authorityFilePath.get()),
            MAX_AUTHORITY_BYTES,
        ).asTextOrReject("authority ledger")
        val admission = admitProgramAuthority(authorityText, expectation) {
            observeAuthoritySource(repositoryRoot, it)
        }
        val authority = when (admission) {
            is ProgramAuthorityAdmission.Complete -> admission.authority
            is ProgramAuthorityAdmission.Rejected -> {
                rejectAuthority("authority admission", admission.failure.render())
            }
        }
        val contradictionText = readBoundaryFile(
            Path.of(contradictionFilePath.get()),
            MAX_AUTHORITY_BYTES,
        ).asTextOrReject("contradiction projection")
        if (contradictionText != authority.contradictionProjection) {
            rejectAuthority("contradiction projection", "bytes differ from admitted authority")
        }
        revalidateExactHead(repositoryRoot, exactHead)
        val report = AuthorityVerificationDocument(
            1,
            "KVP-001-GREEN",
            expectation.baseRevision.value,
            authority.exactHead.value,
            authority.programFingerprint.value,
            authority.requirementFingerprint.value,
            authority.sourceDigests.entries.associate {
                it.key.value to it.value.value
            }.toSortedMap(),
        )
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            authorityEvidenceJson.encodeToString(
                AuthorityVerificationDocument.serializer(),
                report,
            ) + "\n",
        )
        revalidateExactHead(repositoryRoot, exactHead)
    }
}

/**
 * Proof transition: repository `Path` -> `AuthorityGitRevision` at the Gradle task boundary.
 *
 * Establishes one exact lowercase Git HEAD identity. [GitHeadObservation.Rejected] is exhausted to
 * a Gradle task failure here. Raw Git metadata stays inside [observeGitHead].
 */
internal fun observeExactHead(repositoryRoot: Path): AuthorityGitRevision =
    when (val observation = observeGitHead(repositoryRoot)) {
        is GitHeadObservation.Complete -> observation.revision
        is GitHeadObservation.Rejected -> {
            rejectAuthority("git head observation", observation.failure.name)
        }
    }

/**
 * Proof transition: an earlier `AuthorityGitRevision` plus a fresh repository observation ->
 * revalidated `AuthorityGitRevision` at the Gradle task boundary.
 *
 * Equal observations preserve the revision. [GitHeadRevalidation.Rejected] becomes the outer
 * Gradle failure before any authority artifact or GREEN report is written.
 */
internal fun revalidateExactHead(
    repositoryRoot: Path,
    before: AuthorityGitRevision,
): AuthorityGitRevision = when (val revalidation = revalidateGitHead(before, observeExactHead(repositoryRoot))) {
    is GitHeadRevalidation.Complete -> revalidation.revision
    is GitHeadRevalidation.Rejected -> rejectAuthority(
        "git head revalidation",
        "MOVED:${revalidation.before.value}:${revalidation.after.value}",
    )
}

/**
 * Proof transition: raw Gradle authority inputs plus `AuthorityGitRevision` ->
 * `ProgramAuthorityExpectationResult`.
 *
 * [ProgramAuthorityExpectationResult.Complete] carries validated Git and digest identities plus
 * typed source paths. Expected malformed configuration stays
 * [ProgramAuthorityExpectationResult.Rejected]. Raw values remain at this Gradle task boundary.
 */
internal fun configuredExpectation(
    baseRevision: String,
    exactHead: AuthorityGitRevision,
    programFingerprint: String,
    requirementFingerprint: String,
    sourceDigests: Map<String, String>,
    allowedReads: List<String>,
): ProgramAuthorityExpectationResult = ProgramAuthorityExpectation.parse(
    baseRevision,
    exactHead.value,
    programFingerprint,
    requirementFingerprint,
    sourceDigests,
    allowedReads,
)

/**
 * Proof transition: `ProgramAuthorityExpectationResult` -> `ProgramAuthorityExpectation` at the
 * Gradle task boundary.
 *
 * Preserves the admitted expectation. A closed [AuthorityAdmissionFailure] becomes the outer Gradle
 * failure and cannot enter generation or verification.
 */
internal fun ProgramAuthorityExpectationResult.orReject(
    owner: String,
): ProgramAuthorityExpectation = when (this) {
    is ProgramAuthorityExpectationResult.Complete -> expectation
    is ProgramAuthorityExpectationResult.Rejected -> rejectAuthority(owner, failure.render())
}

internal sealed interface DeclaredSourceCandidateSelection {
    data class Complete(
        val candidates: List<AuthoritySourcePath>,
    ) : DeclaredSourceCandidateSelection

    data class Rejected(
        val path: AuthoritySourcePath,
    ) : DeclaredSourceCandidateSelection
}

/**
 * Proof transition: raw configured candidate paths plus admitted allowed reads -> declared source
 * candidate paths.
 *
 * Establishes that every returned candidate has host-valid path syntax. Admission against the
 * allowed-read set and digest-derived identity remains in [generateProgramAuthority]. A malformed
 * path returns [DeclaredSourceCandidateSelection.Rejected]. Raw path extraction stays at this
 * Gradle task boundary.
 */
internal fun ProgramAuthorityExpectation.selectDeclaredSourceCandidates(
    rawCandidates: List<String>,
): DeclaredSourceCandidateSelection {
    val candidates = mutableListOf<AuthoritySourcePath>()
    for (rawCandidate in rawCandidates) {
        val sourcePath = AuthoritySourcePath(rawCandidate)
        val path = try {
            Path.of(sourcePath.value)
        } catch (_: InvalidPathException) {
            return DeclaredSourceCandidateSelection.Rejected(sourcePath)
        }
        if (path.toString().isEmpty()) {
            return DeclaredSourceCandidateSelection.Rejected(sourcePath)
        }
        candidates += sourcePath
    }
    return DeclaredSourceCandidateSelection.Complete(candidates.sortedBy { it.value })
}
