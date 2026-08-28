package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Strong root-identity, report, relation, and forbidden-traversal proof. */
class ProjectReadEpochIdentityTest {
    @Test
    fun `project and Gradle roots reject absence and malformed text exactly`() {
        assertEquals(
            Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectRootUnavailable),
            ProjectEpochRootIdentity.admit(null),
        )
        assertEquals(
            Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectRootMalformed),
            ProjectEpochRootIdentity.admit("relative"),
        )
        assertEquals(
            Refinement.Rejected(ProjectReadEpochObservationFailure.GradleRootUnavailable),
            GradleEpochRootIdentity.admit(null),
        )
        assertEquals(
            Refinement.Rejected(ProjectReadEpochObservationFailure.GradleRootMalformed),
            GradleEpochRootIdentity.admit("relative"),
        )
        listOf(
            "/workspace/../kast",
            "/workspace/\u0000",
            "/" + "a".repeat(4_096),
            "/" + "€".repeat(2_731),
        ).forEach { raw ->
            assertEquals(
                Refinement.Rejected(ProjectReadEpochObservationFailure.GradleRootMalformed),
                GradleEpochRootIdentity.admit(raw),
            )
        }
    }

    @Test
    fun `canonicalized root spellings compare through strong identities`() {
        assertEquals(
            ProjectGradleRootRelation.SAME,
            fixtureProjectEpochRoot("/workspace/kast").relationTo(
                fixtureGradleEpochRoot("/workspace//kast/"),
            ),
        )
    }
}

class ReportedProjectReadEpochTest {
    @Test
    fun `generated report has the exact independently owned product bytes`() {
        val report = System.getProperty(REPORT_PROPERTY)?.let(Path::of)
            ?: fail("missing generated KVP-017 report property $REPORT_PROPERTY")
        val expected = requireNotNull(javaClass.getResource(EXPECTED_REPORT_RESOURCE)) {
            "missing expected KVP-017 report resource $EXPECTED_REPORT_RESOURCE"
        }.readText()

        assertEquals(expected, Files.readString(report))
    }

    @Test
    fun `report case order is observed through retained product sources`() {
        val stable = reportedEpochBoundary()
        val source = ReportEpochSource(stable)
        val before = source.observeEpoch()
        fun moved(boundary: ProjectReadEpochBoundary): ProjectReadEpochRelation {
            source.boundary = boundary
            return before.relationTo(source.observeEpoch())
        }

        assertEquals(
            listOf(
                ProjectReadEpochRelation.SAME,
                moved(stable.copy(projectModelRevision = reportSignal(2))),
                moved(stable.copy(lastImportTimestamp = 11)),
                moved(stable.copy(lastImportTimestamp = 11, lastSuccessfulImportTimestamp = 11)),
                moved(stable.copy(gradleRoot = fixtureGradleEpochRoot("/workspace/gradle-moved"))),
                moved(stable.copy(psiModificationCount = reportSignal(2))),
                moved(stable.copy(rootFilteredVfsBatchCount = reportSignal(2))),
                moved(stable.copy(rootModelModificationCount = reportSignal(2))),
                moved(stable.copy(dumbModeModificationCount = reportSignal(3))),
                moved(
                    stable.copy(
                        projectModelRevision = reportSignal(2),
                        psiModificationCount = reportSignal(2),
                        rootFilteredVfsBatchCount = reportSignal(2),
                        rootModelModificationCount = reportSignal(2),
                        dumbModeModificationCount = reportSignal(3),
                    ),
                ),
                moved(stable.copy(rootFilteredVfsBatchCount = reportSignal(1_001))),
                before.relationTo(ReportEpochSource(stable).observeEpoch()),
                before.relationTo(ReportEpochSource(stable).observeEpoch()),
            ),
            listOf(
                ProjectReadEpochRelation.SAME,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.MOVED,
                ProjectReadEpochRelation.INCOMPARABLE,
                ProjectReadEpochRelation.INCOMPARABLE,
            ),
        )
    }

    private companion object {
        const val REPORT_PROPERTY = "kast.ide.project.read.epoch.report"
        const val EXPECTED_REPORT_RESOURCE = "/KVP-017-read-epoch.expected.json"
    }
}

class ProjectReadEpochTraversalGuardTest {
    @Test
    fun `VFS child and recursive traversal member injections are forbidden`() {
        val forbidden = listOf(
            EpochMemberReference("com/intellij/openapi/vfs/VirtualFile", "getChildren"),
            EpochMemberReference(
                "com/intellij/openapi/vfs/VfsUtilCore",
                "iterateChildrenRecursively",
            ),
            EpochMemberReference(
                "com/intellij/openapi/vfs/VfsUtilCore",
                "visitChildrenRecursively",
            ),
            EpochMemberReference(
                "com/intellij/openapi/vfs/VfsUtil",
                "processFilesRecursively",
            ),
        )

        assertEquals(
            List(forbidden.size) { true },
            forbidden.map(EpochSignalClassContract::rejectsProductionMember),
        )
        assertEquals(
            false,
            EpochSignalClassContract.rejectsProductionMember(
                EpochMemberReference("com/intellij/openapi/vfs/VirtualFile", "getPath"),
            ),
        )
    }
}

private class ReportEpochSource(var boundary: ProjectReadEpochBoundary) {
    private val source = ProjectReadEpoch.Source.create { ProjectReadEpochState.admit(boundary) }

    fun observeEpoch(): ProjectReadEpoch<*> = when (val observed = source.observe()) {
        is ProjectReadEpochObservation.Observed -> observed.epoch
        is ProjectReadEpochObservation.Rejected -> fail("unexpected ${observed.failure}")
    }
}

private fun reportedEpochBoundary() = ProjectReadEpochBoundary(
    projectModelRevision = reportSignal(1),
    projectRoot = fixtureProjectEpochRoot("/workspace/kast"),
    gradleRoot = fixtureGradleEpochRoot("/workspace/kast"),
    lastImportTimestamp = 10,
    lastSuccessfulImportTimestamp = 10,
    psiModificationCount = reportSignal(1),
    rootFilteredVfsBatchCount = reportSignal(1),
    rootModelModificationCount = reportSignal(1),
    dumbModeModificationCount = reportSignal(1),
    dumb = false,
)

private fun reportSignal(value: Long) = ProjectReadEpochSignalSample.Value(value)

internal fun fixtureProjectEpochRoot(raw: String): ProjectEpochRootIdentity =
    when (val result = ProjectEpochRootIdentity.admit(raw)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("invalid project-root fixture: ${result.failure}")
    }

internal fun fixtureGradleEpochRoot(raw: String): GradleEpochRootIdentity =
    when (val result = GradleEpochRootIdentity.admit(raw)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("invalid Gradle-root fixture: ${result.failure}")
    }
