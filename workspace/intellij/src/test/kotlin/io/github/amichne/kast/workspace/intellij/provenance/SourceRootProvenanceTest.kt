package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleIdeaSourceRootEvidence
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerCaptureFailure
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerEvidence
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerIdentity
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerImport
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerProvenance
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootProducerRole
import io.github.amichne.kast.workspace.intellij.provenance.GradleSourceRootLookupIdentity
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
            lookup(workspaceDirectory.resolve("src/main/kotlin")),
            ExternalSystemSourceType.SOURCE,
        )

        assertEquals(
            GradleSourceRootProvenanceFailure.MISSING_PRODUCER_EVIDENCE,
            assertInstanceOf<GradleSourceRootProvenanceResolution.Unknown>(resolution).failure,
        )
    }

    @Test
    fun `unrelated producer evidence cannot authorize a rejected source root`() {
        val root = workspaceDirectory.resolve("shared/source").toAbsolutePath().normalize()
        val authority = GradleSourceRootProvenanceAuthority.compile(
            listOf(
                GradleSourceRootProducerImport.Rejected(
                    GradleSourceRootProducerCaptureFailure.PRODUCER_MODEL_UNAVAILABLE,
                ),
                GradleSourceRootProducerImport.Captured(
                    listOf(
                        producer(
                            root,
                            GradleSourceRootProducerProvenance.AUTHORED,
                            role = GradleSourceRootProducerRole.RESOURCE,
                        ),
                        producer(
                            root,
                            GradleSourceRootProducerProvenance.AUTHORED,
                            projectDirectory = workspaceDirectory.resolve("other-project"),
                            projectPath = ":other",
                        ),
                    ),
                ),
            ),
        )

        val resolution = authority.resolve(lookup(root), ExternalSystemSourceType.SOURCE)

        assertInstanceOf<GradleSourceRootProvenanceResolution.Unknown>(resolution)
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

        val resolution = authority.resolve(lookup(root), ExternalSystemSourceType.SOURCE)

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
                idea(root, GradleSourceRootProducerProvenance.AUTHORED),
            ),
            producerEntries = listOf(
                producer(root, GradleSourceRootProducerProvenance.AUTHORED),
                producer(root, GradleSourceRootProducerProvenance.GENERATED),
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
                idea(root, GradleSourceRootProducerProvenance.AUTHORED),
                idea(root, GradleSourceRootProducerProvenance.GENERATED),
            ),
            producerEntries = listOf(
                producer(root, GradleSourceRootProducerProvenance.GENERATED),
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
                idea(mainRoot, GradleSourceRootProducerProvenance.AUTHORED),
            ),
            producerEntries = listOf(
                producer(mainRoot, GradleSourceRootProducerProvenance.AUTHORED),
                producer(
                    testFixturesRoot,
                    GradleSourceRootProducerProvenance.AUTHORED,
                    sourceSetName = "testFixtures",
                ),
            ),
        )
        val capture = assertInstanceOf<GradleSourceRootProducerImport.Captured>(imported)

        assertEquals(
            WorkspaceSourceRootProvenance.AUTHORED,
            GradleSourceRootProvenanceAuthority.compile(listOf(capture))
                .provenance(
                    testFixturesRoot,
                    ExternalSystemSourceType.TEST,
                    sourceSetName = "testFixtures",
                ),
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

        val resolution = authority.resolve(
            lookup(root),
            ExternalSystemSourceType.SOURCE_GENERATED,
        )

        assertEquals(
            GradleSourceRootProvenanceFailure.CONFLICTING_PRODUCER_EVIDENCE,
            assertInstanceOf<GradleSourceRootProvenanceResolution.Unknown>(resolution).failure,
        )
    }

    private fun captured(
        vararg entries: Pair<Path, GradleSourceRootProducerProvenance>,
    ): GradleSourceRootProducerImport.Captured = GradleSourceRootProducerImport.Captured(
        entries.map { (path, provenance) ->
            producer(path, provenance)
        },
    )

    private fun idea(
        path: Path,
        provenance: GradleSourceRootProducerProvenance,
    ): GradleIdeaSourceRootEvidence = GradleIdeaSourceRootEvidence(path.toFile(), provenance)

    private fun producer(
        path: Path,
        provenance: GradleSourceRootProducerProvenance,
        projectDirectory: Path = workspaceDirectory,
        projectPath: String = ":",
        sourceSetName: String = "main",
        role: GradleSourceRootProducerRole = GradleSourceRootProducerRole.CODE,
    ): GradleSourceRootProducerEvidence = GradleSourceRootProducerEvidence(
        identity = GradleSourceRootProducerIdentity(
            projectDirectory = projectDirectory.toAbsolutePath().normalize().toFile(),
            projectPath = projectPath,
            sourceSetName = sourceSetName,
            sourceRoot = path.toAbsolutePath().normalize().toFile(),
            role = role,
        ),
        provenance = provenance,
    )

    private fun lookup(
        path: Path,
        projectDirectory: Path = workspaceDirectory,
        projectPath: String = ":",
        sourceSetName: String = "main",
    ): GradleSourceRootLookupIdentity = GradleSourceRootLookupIdentity(
        projectDirectory = projectDirectory.toAbsolutePath().normalize(),
        projectPath = projectPath,
        sourceSetName = sourceSetName,
        sourceRoot = path.toAbsolutePath().normalize(),
    )

    private fun GradleSourceRootProvenanceAuthority.provenance(
        path: Path,
        sourceType: ExternalSystemSourceType,
        sourceSetName: String = "main",
    ): WorkspaceSourceRootProvenance =
        assertInstanceOf<GradleSourceRootProvenanceResolution.Proven>(
            resolve(lookup(path, sourceSetName = sourceSetName), sourceType),
        ).provenance
}
