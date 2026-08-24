package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class InstalledGradleImportObserverTest {
    @Test
    fun `path-aware callbacks publish closed terminal outcomes`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val success = InstalledGradleImportObserver(root)
        success.onSuccess(root.toString(), task("success"))
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            success.completion.get(1, TimeUnit.SECONDS),
        )

        val failure = InstalledGradleImportObserver(root)
        failure.onFailure(root.toString(), task("failure"), IllegalStateException("failed"))
        assertEquals(
            InstalledGradleImportOutcome.Failed,
            failure.completion.get(1, TimeUnit.SECONDS),
        )

        val cancellation = InstalledGradleImportObserver(root)
        cancellation.onCancel(root.toString(), task("cancelled"))
        assertEquals(
            InstalledGradleImportOutcome.Cancelled,
            cancellation.completion.get(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `id-only terminal callback requires path-aware task admission`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val admitted = InstalledGradleImportObserver(root)
        val admittedTask = task("admitted")
        admitted.onStart(root.toString(), admittedTask)
        admitted.onSuccess(admittedTask)
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            admitted.completion.get(1, TimeUnit.SECONDS),
        )

        val uncorrelated = InstalledGradleImportObserver(root)
        uncorrelated.onFailure(task("uncorrelated"), IllegalStateException("failed"))
        assertFalse(uncorrelated.completion.isDone)
    }

    @Test
    fun `two exact tasks complete only after the cohort terminates`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val observer = InstalledGradleImportObserver(root)
        val firstTask = task("first")
        val secondTask = task("second")

        observer.onStart(root.toString(), firstTask)
        observer.onStart(root.toString(), secondTask)
        observer.onSuccess(root.toString(), firstTask)

        assertFalse(observer.completion.isDone)

        observer.onSuccess(root.toString(), secondTask)
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            observer.completion.get(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `untracked terminal cannot complete an active cohort`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val observer = InstalledGradleImportObserver(root)
        val firstTask = task("first")
        val secondTask = task("second")

        observer.onStart(root.toString(), firstTask)
        observer.onStart(root.toString(), secondTask)
        observer.onSuccess(root.toString(), task("untracked"))

        assertFalse(observer.completion.isDone)

        observer.onSuccess(root.toString(), firstTask)
        assertFalse(observer.completion.isDone)

        observer.onSuccess(root.toString(), secondTask)
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            observer.completion.get(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `cohort remembers failure until every admitted task terminates`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val observer = InstalledGradleImportObserver(root)
        val failedTask = task("failed")
        val successfulTask = task("successful")

        observer.onStart(root.toString(), failedTask)
        observer.onStart(root.toString(), successfulTask)
        observer.onFailure(root.toString(), failedTask, IllegalStateException("failed"))

        assertFalse(observer.completion.isDone)

        observer.onSuccess(root.toString(), successfulTask)
        assertEquals(
            InstalledGradleImportOutcome.Failed,
            observer.completion.get(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `cohort remembers cancellation until every admitted task terminates`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val observer = InstalledGradleImportObserver(root)
        val cancelledTask = task("cancelled")
        val successfulTask = task("successful")

        observer.onStart(root.toString(), cancelledTask)
        observer.onStart(root.toString(), successfulTask)
        observer.onCancel(root.toString(), cancelledTask)

        assertFalse(observer.completion.isDone)

        observer.onSuccess(root.toString(), successfulTask)
        assertEquals(
            InstalledGradleImportOutcome.Cancelled,
            observer.completion.get(1, TimeUnit.SECONDS),
        )
    }

    private fun task(projectId: String): ExternalSystemTaskId = ExternalSystemTaskId.create(
        GradleConstants.SYSTEM_ID,
        ExternalSystemTaskType.RESOLVE_PROJECT,
        projectId,
    )
}
