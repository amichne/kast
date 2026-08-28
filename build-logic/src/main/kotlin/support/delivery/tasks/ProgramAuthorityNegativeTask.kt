package support.delivery

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes deterministic in-memory negative authority fixtures")
abstract class VerifyKastVfsPassiveAuthorityNegativeTask : DefaultTask() {
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
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verifyNegativeCases() {
        val expectation = configuredExpectation(
            baseRevision.get(),
            AuthorityGitRevision(baseRevision.get()),
            programFingerprint.get(),
            requirementFingerprint.get(),
            sourceDigests.get(),
            allowedReads.get(),
        ).orReject("negative fixture expectation")
        val sourceCandidates = when (
            val selected = expectation.selectDeclaredSourceCandidates(candidatePaths.get())
        ) {
            is DeclaredSourceCandidateSelection.Complete -> selected.candidates
            is DeclaredSourceCandidateSelection.Rejected -> {
                rejectAuthority("negative fixture", "MALFORMED_PATH:${selected.path.value}")
            }
        }
        if (sourceCandidates.size != expectation.sourceDigests.size) {
            rejectAuthority("negative fixture", "source IDs cannot be paired with source candidates")
        }
        val observations = sourceCandidates.sortedBy { it.value }
            .zip(expectation.sourceDigests.values.sortedBy { it.value })
            .associate { (path, digest) ->
                path to AuthoritySourceObservation.Complete(digest)
            }
        val exactDocument = when (
            val generation = generateProgramAuthority(
                expectation,
                sourceCandidates,
                observations::getValue,
            )
        ) {
            is ProgramAuthorityGeneration.Complete -> generation.authority.document
            is ProgramAuthorityGeneration.Rejected -> {
                rejectAuthority("negative fixture generation", generation.failure.render())
            }
        }
        fun admission(document: ProgramAuthorityDocument): ProgramAuthorityAdmission =
            admitProgramAuthority(encodeProgramAuthorityDocument(document), expectation) { path ->
                observations.getValue(path)
            }
        if (admission(exactDocument) !is ProgramAuthorityAdmission.Complete) {
            rejectAuthority("negative fixture", "exact control document was rejected")
        }
        expectRejection(
            admission(exactDocument.copy(exactHead = "0".repeat(40))),
            AuthorityAdmissionFailure.ExactHeadMismatch,
        )
        expectRejection(
            admission(exactDocument.copy(requirementFingerprint = "0".repeat(64))),
            AuthorityAdmissionFailure.RequirementFingerprintMismatch,
        )
        expectRejection(
            admission(
                exactDocument.copy(
                    contradictions = exactDocument.contradictions -
                        AuthorityContradiction.PROGRESSION_ENGINE_WAS_DECLARED_BUT_ABSENT,
                ),
            ),
            AuthorityAdmissionFailure.ContradictionSetIncomplete,
        )
        expectRejection(
            admission(
                exactDocument.copy(
                    obsoleteAssumptions = exactDocument.obsoleteAssumptions -
                        ObsoleteAuthorityAssumption.EXACTLY_TWO_RUNTIME_PROCESSES,
                ),
            ),
            AuthorityAdmissionFailure.ObsoleteAssumptionSetIncomplete,
        )
        val report = AuthorityNegativeProofDocument(1, "KVP-001-RED", AuthorityNegativeCase.entries)
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            authorityEvidenceJson.encodeToString(
                AuthorityNegativeProofDocument.serializer(),
                report,
            ) + "\n",
        )
    }
}

private fun expectRejection(
    result: ProgramAuthorityAdmission,
    expected: AuthorityAdmissionFailure,
) {
    if (result !is ProgramAuthorityAdmission.Rejected || result.failure != expected) {
        rejectAuthority("negative fixture", "expected ${expected.render()}, received $result")
    }
}
