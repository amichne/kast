package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

@TestApplication
class RecoveryAuditAdmissionTest {
    @Test
    fun `recovery audit withdraws reads and restores the exact verified generation`() {
        val generation = publishedGeneration()
        val admission = readyAdmission(generation)

        val audit = admission.beginRecoveryAudit("periodic recovery audit is verifying workspace identity")

        assertEquals(generation, audit.generation)
        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
        assertThrows(IllegalStateException::class.java, admission::openRead)
        assertEquals(
            IdeaIndexSemanticAdmission.RecoveryAuditRestoration.Restored(generation),
            admission.restoreReadyAfterRecoveryAudit(audit),
        )
        assertEquals(IdeaIndexSemanticAdmission.Status.Ready(generation), admission.status())
    }

    @Test
    fun `event invalidates recovery audit restoration`() {
        val admission = readyAdmission()
        val audit = admission.beginRecoveryAudit("periodic recovery audit is verifying workspace identity")

        admission.dirty("source changed during recovery audit")

        assertEquals(
            IdeaIndexSemanticAdmission.RecoveryAuditRestoration.Invalidated,
            admission.restoreReadyAfterRecoveryAudit(audit),
        )
        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)
    }

    private fun readyAdmission(
        generation: PublishedWorkspaceGenerationManifest = publishedGeneration(),
    ): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = projectStub(),
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    ).also { admission ->
        admission.await { false }
        val token = admission.beginReconciliation("test generation is verified")
        check(
            admission.publishReady(token) { WorkspaceGenerationCommit.Durable(generation) } is
                IdeaIndexSemanticAdmission.ReadyPublication.Admitted,
        )
    }

    private fun publishedGeneration(): PublishedWorkspaceGenerationManifest = testPublishedWorkspaceGeneration(
        generation = WorkspaceSemanticGeneration(1),
        identity = WorkspaceStateIdentity("test-workspace-state"),
    )

    private fun projectStub(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> "stub"
            "isDisposed" -> false
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "ProjectStub"
            else -> null
        }
    } as Project
}
