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
    fun `pre-closure cancellation is retained for an exact replacement`(
        @TempDir workspace: Path,
    ) {
        val root = workspace.toRealPath()
        val observer = InstalledGradleImportObserver(root)
        val cancelled = task("cancelled")
        val replacement = task("replacement")

        observer.onStart(root.toString(), cancelled)
        observer.onCancel(root.toString(), cancelled)
        assertFalse(observer.completion.isDone)

        observer.onStart(root.toString(), replacement)
        observer.closeProjectOpenAdmission()
        assertFalse(observer.completion.isDone)

        observer.onSuccess(root.toString(), replacement)
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            observer.completion.get(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `pre-closure failure remains terminal for an exact replacement`(
        @TempDir workspace: Path,
    ) {
        val root = workspace.toRealPath()
        val observer = InstalledGradleImportObserver(root)
        val failed = task("failed")
        val replacement = task("replacement")

        observer.onStart(root.toString(), failed)
        observer.onFailure(root.toString(), failed, IllegalStateException("failed"))
        assertFalse(observer.completion.isDone)

        observer.onStart(root.toString(), replacement)
        observer.closeProjectOpenAdmission()
        observer.onSuccess(root.toString(), replacement)

        assertEquals(
            InstalledGradleImportOutcome.Failed,
            observer.completion.get(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `path-aware callbacks publish closed terminal outcomes`(@TempDir workspace: Path) {
        val root = workspace.toRealPath()
        val success = InstalledGradleImportObserver(root)
        success.onSuccess(root.toString(), task("success"))
        success.closeProjectOpenAdmission()
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            success.completion.get(1, TimeUnit.SECONDS),
        )

        val failure = InstalledGradleImportObserver(root)
        failure.onFailure(root.toString(), task("failure"), IllegalStateException("failed"))
        failure.closeProjectOpenAdmission()
        assertEquals(
            InstalledGradleImportOutcome.Failed,
            failure.completion.get(1, TimeUnit.SECONDS),
        )

        val cancellation = InstalledGradleImportObserver(root)
        cancellation.onCancel(root.toString(), task("cancelled"))
        cancellation.closeProjectOpenAdmission()
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
        admitted.closeProjectOpenAdmission()
        admitted.onSuccess(admittedTask)
        assertEquals(
            InstalledGradleImportOutcome.Completed,
            admitted.completion.get(1, TimeUnit.SECONDS),
        )

        val uncorrelated = InstalledGradleImportObserver(root)
        uncorrelated.onFailure(task("uncorrelated"), IllegalStateException("failed"))
        uncorrelated.closeProjectOpenAdmission()
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
        observer.closeProjectOpenAdmission()
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
        observer.closeProjectOpenAdmission()
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
        observer.closeProjectOpenAdmission()
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
        observer.closeProjectOpenAdmission()
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
