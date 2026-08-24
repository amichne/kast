package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerCaptureFailure
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerEvidence
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerImport
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProvenanceAuthority
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProvenanceFailure
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProvenanceResolution
import io.github.amichne.kast.workspace.intellij.provenance.combineGradleSourceRootProducerEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SourceRootProvenanceTest {
    @TempDir
    lateinit var workspaceDirectory: Path

    @Test
    fun `producer evidence wins over source-root path names`() {
        val authoredLookingGenerated = workspaceDirectory.resolve("src/main/kotlin")
            .toAbsolutePath().normalize()
        val generatedLookingAuthored = workspaceDirectory.resolve("build/generated/authored")
            .toAbsolutePath().normalize()
        val authority = GradleSourceRootProvenanceAuthority.compile(
            listOf(
                captured(
                    authoredLookingGenerated to GradleSourceRootProducerProvenance.GENERATED,
                    generatedLookingAuthored to GradleSourceRootProducerProvenance.AUTHORED,
                ),
            ),
        )

        assertEquals(
            WorkspaceSourceRootProvenance.GENERATED,
            authority.provenance(authoredLookingGenerated, ExternalSystemSourceType.SOURCE),
        )
        assertEquals(
            WorkspaceSourceRootProvenance.AUTHORED,
            authority.provenance(generatedLookingAuthored, ExternalSystemSourceType.SOURCE),
        )
    }

    @Test
    fun `ordinary source without producer evidence remains unknown`() {
        val resolution = GradleSourceRootProvenanceAuthority.compile(emptyList()).resolve(
            workspaceDirectory.resolve("src/main/kotlin"),
            ExternalSystemSourceType.SOURCE,
        )

        assertEquals(
            GradleSourceRootProvenanceFailure.MISSING_PRODUCER_EVIDENCE,
            assertInstanceOf<GradleSourceRootProvenanceResolution.Unknown>(resolution).failure,
        )
    }

    @Test
    fun `conflicting producer evidence remains unknown`() {
        val root = workspaceDirectory.resolve("src/main/kotlin").toAbsolutePath().normalize()
        val authority = GradleSourceRootProvenanceAuthority.compile(
            listOf(
                captured(root to GradleSourceRootProducerProvenance.AUTHORED),
                captured(root to GradleSourceRootProducerProvenance.GENERATED),
            ),
        )

        val resolution = authority.resolve(root, ExternalSystemSourceType.SOURCE)

        assertEquals(
            GradleSourceRootProvenanceFailure.CONFLICTING_PRODUCER_EVIDENCE,
            assertInstanceOf<GradleSourceRootProvenanceResolution.Unknown>(resolution).failure,
        )
    }

    @Test
    fun `conflicting exact entries are rejected before import`() {
        val root = workspaceDirectory.resolve("src/main/kotlin").toAbsolutePath().normalize()

        val imported = combineGradleSourceRootProducerEvidence(
            ideaEntries = listOf(
                GradleSourceRootProducerEvidence(
                    root.toFile(),
                    GradleSourceRootProducerProvenance.AUTHORED,
                ),
            ),
            producerEntries = listOf(
                GradleSourceRootProducerEvidence(
                    root.toFile(),
                    GradleSourceRootProducerProvenance.AUTHORED,
                ),
                GradleSourceRootProducerEvidence(
                    root.toFile(),
                    GradleSourceRootProducerProvenance.GENERATED,
                ),
            ),
        )

        assertEquals(
            GradleSourceRootProducerCaptureFailure.CONFLICTING_SOURCE_ROOT_EVIDENCE,
            assertInstanceOf<GradleSourceRootProducerImport.Rejected>(imported).failure,
        )
    }

    @Test
    fun `conflicting exact IDEA entries are rejected before import`() {
        val root = workspaceDirectory.resolve("src/main/kotlin").toAbsolutePath().normalize()

        val imported = combineGradleSourceRootProducerEvidence(
            ideaEntries = listOf(
                GradleSourceRootProducerEvidence(
                    root.toFile(),
                    GradleSourceRootProducerProvenance.AUTHORED,
                ),
                GradleSourceRootProducerEvidence(
                    root.toFile(),
                    GradleSourceRootProducerProvenance.GENERATED,
                ),
            ),
            producerEntries = listOf(
                GradleSourceRootProducerEvidence(
                    root.toFile(),
                    GradleSourceRootProducerProvenance.GENERATED,
                ),
            ),
        )

        assertEquals(
            GradleSourceRootProducerCaptureFailure.CONFLICTING_SOURCE_ROOT_EVIDENCE,
            assertInstanceOf<GradleSourceRootProducerImport.Rejected>(imported).failure,
        )
    }

    @Test
    fun `producer entries projected outside the standard IDEA model remain authoritative`() {
        val mainRoot = workspaceDirectory.resolve("src/main/kotlin").toAbsolutePath().normalize()
        val testFixturesRoot = workspaceDirectory.resolve("src/testFixtures/kotlin")
            .toAbsolutePath().normalize()

        val imported = combineGradleSourceRootProducerEvidence(
            ideaEntries = listOf(
                GradleSourceRootProducerEvidence(
                    mainRoot.toFile(),
                    GradleSourceRootProducerProvenance.AUTHORED,
                ),
            ),
            producerEntries = listOf(
                GradleSourceRootProducerEvidence(
                    mainRoot.toFile(),
                    GradleSourceRootProducerProvenance.AUTHORED,
                ),
                GradleSourceRootProducerEvidence(
                    testFixturesRoot.toFile(),
                    GradleSourceRootProducerProvenance.AUTHORED,
                ),
            ),
        )
        val capture = assertInstanceOf<GradleSourceRootProducerImport.Captured>(imported)

        assertEquals(
            WorkspaceSourceRootProvenance.AUTHORED,
            GradleSourceRootProvenanceAuthority.compile(listOf(capture))
                .provenance(testFixturesRoot, ExternalSystemSourceType.TEST),
        )
    }

    @Test
    fun `explicit generated source type remains positive model authority`() {
        val root = workspaceDirectory.resolve("src/main/kotlin").toAbsolutePath().normalize()

        assertEquals(
            WorkspaceSourceRootProvenance.GENERATED,
            GradleSourceRootProvenanceAuthority.compile(emptyList())
                .provenance(root, ExternalSystemSourceType.SOURCE_GENERATED),
        )
    }

    @Test
    fun `explicit generated source type conflicts with authored producer evidence`() {
        val root = workspaceDirectory.resolve("src/main/kotlin").toAbsolutePath().normalize()
        val authority = GradleSourceRootProvenanceAuthority.compile(
            listOf(captured(root to GradleSourceRootProducerProvenance.AUTHORED)),
        )

        val resolution = authority.resolve(root, ExternalSystemSourceType.SOURCE_GENERATED)

        assertEquals(
            GradleSourceRootProvenanceFailure.CONFLICTING_PRODUCER_EVIDENCE,
            assertInstanceOf<GradleSourceRootProvenanceResolution.Unknown>(resolution).failure,
        )
    }

    private fun captured(
        vararg entries: Pair<Path, GradleSourceRootProducerProvenance>,
    ): GradleSourceRootProducerImport.Captured = GradleSourceRootProducerImport.Captured(
        entries.map { (path, provenance) ->
            GradleSourceRootProducerEvidence(path.toFile(), provenance)
        },
    )

    private fun GradleSourceRootProvenanceAuthority.provenance(
        path: Path,
        sourceType: ExternalSystemSourceType,
    ): WorkspaceSourceRootProvenance =
        assertInstanceOf<GradleSourceRootProvenanceResolution.Proven>(
            resolve(path, sourceType),
        ).provenance
}
