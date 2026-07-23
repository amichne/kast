package io.github.amichne.kast.idea

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequest
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResponse
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResult
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.server.RuntimeProjectOpenController
import java.awt.Frame
import java.util.concurrent.atomic.AtomicReference

internal class KastRuntimeProjectOpenController(
    private val hostProject: Project,
    config: KastConfig,
) : RuntimeProjectOpenController {
    private val requests = KastOpenProjectRequestStore(config)

    override fun openProject(request: RuntimeOpenProjectRequest): RuntimeOpenProjectResponse {
        requireSupportedIdeaHost()
        val canonicalRoot = request.canonicalRoot
        if (!requests.consume(canonicalRoot, request.requestId, allowUntargeted = false)) {
            throw openProjectError(
                "IDEA_OPEN_REQUEST_REJECTED",
                "The local project-open request is missing, expired, already consumed, or belongs to another IDEA process.",
            )
        }
        ProjectManager.getInstance().openProjects
            .firstOrNull { project -> project.basePath?.let(::canonicalRootOrNull) == canonicalRoot }
            ?.let {
                return RuntimeOpenProjectResponse(RuntimeOpenProjectResult.ALREADY_OPEN)
            }

        val root = canonicalRoot.toJavaPath()
        val anchor = ProjectUtil.getActiveProject() ?: hostProject
        val anchorFrame = WindowManager.getInstance().getFrame(anchor)
        val opened = AtomicReference<Project?>()
        ApplicationManager.getApplication().invokeAndWait {
            opened.set(
                ProjectUtil.openOrImport(
                    root,
                    OpenProjectTask.build()
                        .withForceOpenInNewFrame(true)
                        .withProjectToClose(null),
                ),
            )
        }
        val project = opened.get()
            ?: throw openProjectError(
                "IDEA_PROJECT_OPEN_FAILED",
                "IntelliJ Platform did not return an opened project for $root.",
            )
        KastOpenedProjectProvenance.mark(project)
        inheritPlacementWithoutActivation(anchorFrame, WindowManager.getInstance().getFrame(project))
        return RuntimeOpenProjectResponse(RuntimeOpenProjectResult.OPENED_NEW_PROJECT)
    }

    private fun canonicalRootOrNull(value: String): RuntimeOpenProjectRoot? =
        runCatching { RuntimeOpenProjectRoot.of(java.nio.file.Path.of(value)) }.getOrNull()

    private fun inheritPlacementWithoutActivation(anchor: Frame?, target: Frame?) {
        if (anchor == null || target == null || target.isActive) return
        target.bounds = anchor.bounds
        target.extendedState = anchor.extendedState
    }
}

internal fun requireSupportedIdeaHost() {
    val build = ApplicationInfo.getInstance().build
    if (!isSupportedIdeaHost(build.productCode, build.baselineVersion)) {
        throw openProjectError(
            "IDEA_VERSION_UNSUPPORTED",
            "Kast supports IntelliJ IDEA build 262 and Android Studio build 261; this host is ${build.asString()}.",
        )
    }
}

internal fun isSupportedIdeaHost(productCode: String, baselineVersion: Int): Boolean =
    when (productCode) {
        "AI" -> baselineVersion == 261
        "IC", "IU" -> baselineVersion == 262
        else -> false
    }

private fun openProjectError(code: String, message: String): AnalysisException =
    AnalysisException(
        statusCode = 409,
        errorCode = code,
        message = message,
    )
