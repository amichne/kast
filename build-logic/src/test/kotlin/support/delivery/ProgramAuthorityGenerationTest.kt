package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProgramAuthorityGenerationTest {
    @Test
    fun `source digests bind ids to paths independent of candidate order`() {
        val fixture = fixture()

        val generated = generateProgramAuthority(
            fixture.expectation,
            listOf(fixture.substratePath, fixture.deliveryPath),
            fixture::observe,
        )

        val complete = assertInstanceOf(ProgramAuthorityGeneration.Complete::class.java, generated)
        assertEquals(
            listOf(
                AuthoritySourceDocument("deliveryAuthority", DELIVERY_PATH, DELIVERY_DIGEST),
                AuthoritySourceDocument("intellijSubstrate", SUBSTRATE_PATH, SUBSTRATE_DIGEST),
            ),
            complete.authority.document.sourceArtifacts,
        )
        assertEquals(AuthorityContradiction.entries.toSet(), complete.authority.document.contradictions)
        assertInstanceOf(
            ProgramAuthorityAdmission.Complete::class.java,
            admitProgramAuthority(
                complete.authority.document,
                fixture.expectation,
                fixture::observe,
            ),
        )
    }

    @Test
    fun `incomplete or contradictory source evidence rejects as finite data`() {
        val fixture = fixture()
        assertEquals(
            ProgramAuthorityGeneration.Rejected(
                ProgramAuthorityGenerationFailure.CandidatePathNotAllowed(
                    AuthoritySourcePath(UNALLOWED_PATH),
                ),
            ),
            generateProgramAuthority(
                fixture.expectation,
                listOf(AuthoritySourcePath(UNALLOWED_PATH)),
                fixture::observe,
            ),
        )
        assertEquals(
            ProgramAuthorityGeneration.Rejected(
                ProgramAuthorityGenerationFailure.MissingDeclaredSource(
                    AuthoritySourceId("intellijSubstrate"),
                ),
            ),
            generateProgramAuthority(
                fixture.expectation,
                listOf(fixture.deliveryPath),
                fixture::observe,
            ),
        )
        assertEquals(
            ProgramAuthorityGeneration.Rejected(
                ProgramAuthorityGenerationFailure.UnexpectedDigest(
                    fixture.substratePath,
                    AuthorityArtifactDigest(UNDECLARED_DIGEST),
                ),
            ),
            generateProgramAuthority(
                fixture.expectation,
                listOf(fixture.deliveryPath, fixture.substratePath),
            ) { path ->
                if (path == fixture.substratePath) {
                    AuthoritySourceObservation.Complete(AuthorityArtifactDigest(UNDECLARED_DIGEST))
                } else {
                    fixture.observe(path)
                }
            },
        )
        assertEquals(
            ProgramAuthorityGeneration.Rejected(
                ProgramAuthorityGenerationFailure.DuplicateCandidatePath(fixture.deliveryPath),
            ),
            generateProgramAuthority(
                fixture.expectation,
                listOf(fixture.deliveryPath, fixture.deliveryPath),
                fixture::observe,
            ),
        )

        val ambiguousExpectation = expectation(
            mapOf(
                "deliveryAuthority" to DELIVERY_DIGEST,
                "intellijSubstrate" to DELIVERY_DIGEST,
            ),
        )
        assertEquals(
            ProgramAuthorityGeneration.Rejected(
                ProgramAuthorityGenerationFailure.AmbiguousDeclaredDigest(
                    AuthorityArtifactDigest(DELIVERY_DIGEST),
                ),
            ),
            generateProgramAuthority(
                ambiguousExpectation,
                listOf(fixture.deliveryPath, fixture.substratePath),
                fixture::observe,
            ),
        )
    }

    @Test
    fun `head movement rejects exact-head revalidation`() {
        val before = AuthorityGitRevision(EXACT_HEAD)
        val after = AuthorityGitRevision("0".repeat(40))

        assertEquals(
            GitHeadRevalidation.Rejected(before, after),
            revalidateGitHead(before, after),
        )
    }

    @Test
    fun `linked worktree resolves loose and packed refs through common git directory`(
        @TempDir temporaryDirectory: Path,
    ) {
        val repository = temporaryDirectory.resolve("feature")
        val commonGitDirectory = temporaryDirectory.resolve("main.git")
        val worktreeGitDirectory = commonGitDirectory.resolve("worktrees/feature")
        Files.createDirectories(repository)
        Files.createDirectories(worktreeGitDirectory)
        Files.createDirectories(commonGitDirectory.resolve("refs/heads"))
        Files.writeString(repository.resolve(".git"), "gitdir: $worktreeGitDirectory\n")
        Files.writeString(worktreeGitDirectory.resolve("HEAD"), "ref: refs/heads/feature\n")
        Files.writeString(worktreeGitDirectory.resolve("commondir"), "../..\n")
        Files.writeString(commonGitDirectory.resolve("refs/heads/feature"), "$EXACT_HEAD\n")

        assertEquals(
            GitHeadObservation.Complete(AuthorityGitRevision(EXACT_HEAD)),
            observeGitHead(repository),
        )

        Files.delete(commonGitDirectory.resolve("refs/heads/feature"))
        Files.writeString(
            commonGitDirectory.resolve("packed-refs"),
            "# pack-refs with: peeled fully-peeled sorted\n$EXACT_HEAD refs/heads/feature\n",
        )
        assertEquals(
            GitHeadObservation.Complete(AuthorityGitRevision(EXACT_HEAD)),
            observeGitHead(repository),
        )
    }

    private fun fixture(): Fixture = Fixture(
        expectation(
            mapOf(
                "deliveryAuthority" to DELIVERY_DIGEST,
                "intellijSubstrate" to SUBSTRATE_DIGEST,
            ),
        ),
        AuthoritySourcePath(DELIVERY_PATH),
        AuthoritySourcePath(SUBSTRATE_PATH),
    )

    private fun expectation(sourceDigests: Map<String, String>): ProgramAuthorityExpectation =
        when (
            val parsed = ProgramAuthorityExpectation.parse(
                BASE_REVISION,
                EXACT_HEAD,
                PROGRAM_FINGERPRINT,
                REQUIREMENT_FINGERPRINT,
                sourceDigests,
                listOf(DELIVERY_PATH, SUBSTRATE_PATH),
            )
        ) {
            is ProgramAuthorityExpectationResult.Complete -> parsed.expectation
            is ProgramAuthorityExpectationResult.Rejected -> error("invalid fixture: ${parsed.failure}")
        }

    private data class Fixture(
        val expectation: ProgramAuthorityExpectation,
        val deliveryPath: AuthoritySourcePath,
        val substratePath: AuthoritySourcePath,
    ) {
        fun observe(path: AuthoritySourcePath): AuthoritySourceObservation =
            when (path) {
                deliveryPath -> AuthoritySourceObservation.Complete(
                    AuthorityArtifactDigest(DELIVERY_DIGEST),
                )
                substratePath -> AuthoritySourceObservation.Complete(
                    AuthorityArtifactDigest(SUBSTRATE_DIGEST),
                )
                else -> error("unexpected fixture path: ${path.value}")
            }
    }

    private companion object {
        const val BASE_REVISION = "78262728313c90bb847e73425dc1a76d704397db"
        const val EXACT_HEAD = "68559ea2e693593686ab94c2c66b6a4399787af6"
        const val PROGRAM_FINGERPRINT = "86c915662683889239eb238eab856259cc43e99403cd6e94c15f5bd6eda7c9e4"
        const val REQUIREMENT_FINGERPRINT = "55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e"
        const val DELIVERY_DIGEST = "55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e"
        const val SUBSTRATE_DIGEST = "7827929f5b8e0bb4248d2135a7382834045c8158cec2a55c2a1933a7220a6b50"
        const val UNDECLARED_DIGEST = "a926effde75fa956c85e33180f77d0cdbdeaf1980ae37259eb2234b9e3ae200c"
        const val DELIVERY_PATH = "/authority/pasted.txt"
        const val SUBSTRATE_PATH = "/authority/substrate.html"
        const val UNALLOWED_PATH = "/outside/not-declared.txt"
    }
}
