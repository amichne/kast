package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure

/** The private profile needs its own SDK before native Gradle JVM selection can run without a dialog. */
internal fun ManagedHeadlessHost.prepareProjectJbr(project: Project): Refinement<Unit, WorkspaceRefreshFailure> {
    val java = JavaSdk.getInstance()
    val home = environment.javaHome.toString()
    if (!java.isValidSdkHome(home)) {
        recordManagedHeadless(ManagedHeadlessObservation.JbrRejected(HeadlessJbrFailure.INVALID_HOME))
        return Refinement.Rejected(WorkspaceRefreshFailure.EFFECT_FAILED)
    }
    val table = ProjectJdkTable.getInstance()
    val existing = table.allJdks.filter { it.homePath == home && it.sdkType == java }
    if (existing.size > 1) {
        recordManagedHeadless(ManagedHeadlessObservation.JbrRejected(HeadlessJbrFailure.AMBIGUOUS_REGISTRATION))
        return Refinement.Rejected(WorkspaceRefreshFailure.EFFECT_FAILED)
    }
    val sdk = existing.singleOrNull() ?: java.createJdk("Kast JBR: $home", home, false)
    WriteAction.run<RuntimeException> {
        if (existing.isEmpty()) table.addJdk(sdk)
        ProjectRootManager.getInstance(project).projectSdk = sdk
    }
    recordManagedHeadless(ManagedHeadlessObservation.JbrConfigured)
    return Refinement.Refined(Unit)
}
