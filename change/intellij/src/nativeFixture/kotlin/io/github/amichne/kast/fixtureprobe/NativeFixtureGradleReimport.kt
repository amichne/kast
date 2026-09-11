package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager
import com.intellij.openapi.project.Project
import java.nio.file.Files

internal object NativeFixtureGradleReimport {
    fun begin(project: Project, sandbox: ProbeSandbox, request: ProbeRequest): ProbeResult<Unit> {
        if (!sandbox.valid(project)) return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
        when (val image = validateBuild(sandbox, request)) {
            is ProbeResult.Accepted -> Unit
            is ProbeResult.Rejected -> return image
        }
        if (
            ExternalSystemTaskType.entries.any {
                ExternalSystemProcessingManager.getInstance().hasTaskOfTypeInProgress(it, project)
            }
        ) {
            return ProbeResult.Rejected(ProbeFailure.REIMPORT_UNAVAILABLE)
        }
        val specification =
            ImportSpecBuilder(project, ProjectSystemId("GRADLE"))
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withImportProjectData(true)
                .withActivateToolWindowOnStart(false)
                .withActivateToolWindowOnFailure(false)
                .dontNavigateToError()
                .build()
        ExternalProjectsManager.getInstance(project).refreshProject(sandbox.project.toString(), specification)
        return ProbeResult.Accepted(Unit)
    }

    fun validateBuild(sandbox: ProbeSandbox, request: ProbeRequest): ProbeResult<Unit> {
        val expected =
            request.build as? ProbeBuildGuard.Expected
                ?: return ProbeResult.Rejected(ProbeFailure.BUILD_IMAGE_GUARD_REQUIRED)
        return try {
            val path = sandbox.project.resolve("build.gradle.kts")
            if (!Files.isRegularFile(path) || path.toRealPath() != path)
                return ProbeResult.Rejected(ProbeFailure.BUILD_IMAGE_UNAVAILABLE)
            val bytes = Files.newInputStream(path).use { source -> source.readNBytes(MAXIMUM_BUILD_BYTES + 1) }
            if (bytes.size > MAXIMUM_BUILD_BYTES) ProbeResult.Rejected(ProbeFailure.BUILD_IMAGE_UNAVAILABLE)
            else if (ProbeDigest.observe(bytes) != expected.digest)
                ProbeResult.Rejected(ProbeFailure.BUILD_IMAGE_CHANGED)
            else ProbeResult.Accepted(Unit)
        } catch (_: Exception) {
            ProbeResult.Rejected(ProbeFailure.BUILD_IMAGE_UNAVAILABLE)
        }
    }
}

private const val MAXIMUM_BUILD_BYTES = 65536
