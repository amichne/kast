package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SeedProjectCompatibilityTest {
    @Test
    fun `external seed without project evidence admits only global categories`(
        @TempDir temporary: Path,
    ) {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val proof = SeedProjectProofState.classify(SeedProjectEvidence.Absent, project)

        assertEquals(SeedProjectProofState.GlobalOnly, proof)
        assertEquals(
            setOf(IndexSeedCategory.GLOBAL_VFS, IndexSeedCategory.GLOBAL_INDEXES),
            proof.categories,
        )
    }

    @Test
    fun `project categories require exact Gradle model and classpath identity`(
        @TempDir temporary: Path,
    ) {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        val expected = identity(project, gradleDistribution = "8.8", marker = 'a')
        val exact = SeedProjectProofState.classify(
            SeedProjectEvidence.Comparison(expected, expected),
            project,
        )

        assertTrue(exact is SeedProjectProofState.Verified)
        assertEquals(IndexSeedCategory.entries.toSet(), exact.categories)

        val observed = identity(project, gradleDistribution = "8.7", marker = 'a')
        val retired = SeedProjectProofState.classify(
            SeedProjectEvidence.Comparison(expected, observed),
            project,
        )

        assertTrue(retired is SeedProjectProofState.Retired)
        assertEquals(
            setOf(IndexSeedCategory.GLOBAL_VFS, IndexSeedCategory.GLOBAL_INDEXES),
            retired.categories,
        )
        retired as SeedProjectProofState.Retired
        assertEquals(expected, retired.expected)
        assertEquals(observed, retired.observed)
    }

    @Test
    fun `exact evidence for a different project cannot admit project categories`(
        @TempDir temporary: Path,
    ) {
        val requestedProject = Files.createDirectory(temporary.resolve("requested")).toRealPath()
        val otherProject = Files.createDirectory(temporary.resolve("other")).toRealPath()
        val evidence = identity(otherProject, gradleDistribution = "8.8", marker = 'a')

        val proof = SeedProjectProofState.classify(
            SeedProjectEvidence.Comparison(evidence, evidence),
            requestedProject,
        )

        assertTrue(proof is SeedProjectProofState.Retired)
        assertEquals(
            setOf(IndexSeedCategory.GLOBAL_VFS, IndexSeedCategory.GLOBAL_INDEXES),
            proof.categories,
        )
    }

    @Test
    fun `project identity rejects malformed fingerprints`(@TempDir temporary: Path) {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()

        assertTrue(
            SeedProjectIdentity.admit(
                candidate(project, gradleDistribution = "8.8", marker = 'x').copy(
                    classpathFingerprint = "not-a-digest",
                ),
            ) is SeedProjectIdentityAdmission.Rejected,
        )
    }

    private fun identity(
        project: Path,
        gradleDistribution: String,
        marker: Char,
    ): SeedProjectIdentity = when (
        val admission = SeedProjectIdentity.admit(
            candidate(project, gradleDistribution, marker),
        )
    ) {
        is SeedProjectIdentityAdmission.Admitted -> admission.identity
        is SeedProjectIdentityAdmission.Rejected -> error(admission.failure)
    }

    private fun candidate(
        project: Path,
        gradleDistribution: String,
        marker: Char,
    ): SeedProjectIdentityCandidate = SeedProjectIdentityCandidate(
        project,
        gradleDistribution,
        digest(marker),
        digest('b'),
        digest('c'),
        digest('d'),
        digest('e'),
    )

    private fun digest(marker: Char): String = "sha256:${marker.toString().repeat(64)}"
}
