package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProgramAuthorityAdmissionTest {
    @Test
    fun `exact document refines to admitted authority`() {
        val fixture = fixture()

        val result = admitProgramAuthority(
            encodeProgramAuthorityDocument(fixture.document),
            fixture.expectation,
            fixture::observe,
        )

        val complete = assertInstanceOf(ProgramAuthorityAdmission.Complete::class.java, result)
        assertEquals(EXACT_HEAD, complete.authority.exactHead.value)
        assertEquals(PROGRAM_FINGERPRINT, complete.authority.programFingerprint.value)
        assertTrue(complete.authority.contradictionProjection.contains("PROGRESSION_ENGINE_WAS_DECLARED_BUT_ABSENT"))
    }

    @Test
    fun `stale head changed requirement omitted contradiction and obsolete assumption reject`() {
        val fixture = fixture()
        val cases = listOf(
            fixture.document.copy(exactHead = "0".repeat(40)) to AuthorityAdmissionFailure.ExactHeadMismatch,
            fixture.document.copy(requirementFingerprint = "0".repeat(64)) to AuthorityAdmissionFailure.RequirementFingerprintMismatch,
            fixture.document.copy(
                contradictions = fixture.document.contradictions -
                    AuthorityContradiction.PROGRESSION_ENGINE_WAS_DECLARED_BUT_ABSENT,
            ) to AuthorityAdmissionFailure.ContradictionSetIncomplete,
            fixture.document.copy(
                obsoleteAssumptions = fixture.document.obsoleteAssumptions -
                    ObsoleteAuthorityAssumption.EXACTLY_TWO_RUNTIME_PROCESSES,
            ) to AuthorityAdmissionFailure.ObsoleteAssumptionSetIncomplete,
        )

        cases.forEach { (document, expectedFailure) ->
            val result = admitProgramAuthority(
                encodeProgramAuthorityDocument(document),
                fixture.expectation,
                fixture::observe,
            )
            assertEquals(ProgramAuthorityAdmission.Rejected(expectedFailure), result)
        }
    }

    @Test
    fun `missing source fails closed`() {
        val fixture = fixture()
        val result = admitProgramAuthority(
            encodeProgramAuthorityDocument(fixture.document),
            fixture.expectation,
        ) { AuthoritySourceObservation.Rejected(AuthoritySourceFailure.MISSING) }

        assertEquals(
            ProgramAuthorityAdmission.Rejected(
                AuthorityAdmissionFailure.SourceUnavailable(
                    AuthoritySourceId("deliveryAuthority"),
                    AuthoritySourceFailure.MISSING,
                ),
            ),
            result,
        )
    }

    @Test
    fun `unknown JSON fields reject instead of being ignored`() {
        val fixture = fixture()
        val raw = encodeProgramAuthorityDocument(fixture.document)
            .replaceFirst("{", "{\"manufacturedStatus\":true,")

        val result = admitProgramAuthority(raw, fixture.expectation, fixture::observe)

        assertEquals(
            ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.MalformedDocument),
            result,
        )
    }

    @Test
    fun `git head observation reads loose ref without process start`(@TempDir root: Path) {
        val git = Files.createDirectories(root.resolve(".git"))
        Files.createDirectories(git.resolve("refs/heads"))
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/task\n")
        Files.writeString(git.resolve("refs/heads/task"), "$EXACT_HEAD\n")

        assertEquals(
            GitHeadObservation.Complete(AuthorityGitRevision(EXACT_HEAD)),
            observeGitHead(root),
        )
    }

    @Test
    fun `boundary read rejects the first byte beyond its limit`(@TempDir root: Path) {
        val source = root.resolve("authority.txt")
        Files.writeString(source, "12345")

        assertEquals(
            BoundaryFileRead.Rejected(AuthoritySourceFailure.TOO_LARGE),
            readBoundaryFile(source, 4),
        )
    }

    private fun fixture(): Fixture {
        val expectation = when (
            val parsed = ProgramAuthorityExpectation.parse(
                BASE_REVISION,
                EXACT_HEAD,
                PROGRAM_FINGERPRINT,
                REQUIREMENT_FINGERPRINT,
                mapOf("deliveryAuthority" to SOURCE_DIGEST),
                listOf(SOURCE_PATH),
            )
        ) {
            is ProgramAuthorityExpectationResult.Complete -> parsed.expectation
            is ProgramAuthorityExpectationResult.Rejected -> error("invalid test expectation: ${parsed.failure}")
        }
        val document = ProgramAuthorityDocument(
            1,
            BASE_REVISION,
            EXACT_HEAD,
            PROGRAM_FINGERPRINT,
            REQUIREMENT_FINGERPRINT,
            listOf(AuthoritySourceDocument("deliveryAuthority", SOURCE_PATH, SOURCE_DIGEST)),
            AuthorityContradiction.entries.toSet(),
            ObsoleteAuthorityAssumption.entries.toSet(),
            UnprovenAuthorityClaim.entries.toSet(),
        )
        return Fixture(expectation, document)
    }

    private data class Fixture(
        val expectation: ProgramAuthorityExpectation,
        val document: ProgramAuthorityDocument,
    ) {
        fun observe(path: AuthoritySourcePath): AuthoritySourceObservation {
            assertEquals(SOURCE_PATH, path.value)
            return AuthoritySourceObservation.Complete(AuthorityArtifactDigest(SOURCE_DIGEST))
        }
    }

    private companion object {
        const val BASE_REVISION = "78262728313c90bb847e73425dc1a76d704397db"
        const val EXACT_HEAD = "a844f297ffaf79c2a0af5b6b3ec8f9afeaee0a25"
        const val PROGRAM_FINGERPRINT = "86c915662683889239eb238eab856259cc43e99403cd6e94c15f5bd6eda7c9e4"
        const val REQUIREMENT_FINGERPRINT = "55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e"
        const val SOURCE_DIGEST = "7827929f5b8e0bb4248d2135a7382834045c8158cec2a55c2a1933a7220a6b50"
        const val SOURCE_PATH = "/mnt/data/pasted.txt"
    }
}
