package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class InstalledProjectJvmFailure { PLATFORM_REJECTED }

sealed interface InstalledProjectJvmAssignment {
    data class Assigned(
        val projectJvm: AssignedInstalledProjectJvm,
    ) : InstalledProjectJvmAssignment

    data class Rejected(
        val failure: InstalledProjectJvmFailure,
    ) : InstalledProjectJvmAssignment
}

/** IntelliJ project SDK-table identity derived only from the admitted process Java home. */
class InstalledProjectJvm private constructor(
    internal val home: Path,
) {
    /**
     * Proof transition: `InstalledProjectJvm + Project -> InstalledProjectJvmAssignment`.
     *
     * Establishes a project SDK backed by a Java SDK created from the admitted physical home before
     * startup waits. The project always receives Kast's private sidecar identity so reassertion can
     * never replace a Gradle toolchain SDK-table entry used by modules. [InstalledProjectJvmFailure]
     * is the closed expected platform failure. Raw SDK names and home paths leave only at the
     * process-local IntelliJ SDK-table and project-model boundary.
     */
    fun assign(project: Project): InstalledProjectJvmAssignment =
        AssignedInstalledProjectJvm.establish(this, project)

    companion object {
        /**
         * Proof transition: `InstalledGradleJvm -> InstalledProjectJvm`.
         *
         * Preserves the admitted physical Java home for resolution under the project's existing SDK
         * identity. Raw home extraction is permitted only by [assign] at the isolated SDK-table
         * boundary.
         */
        fun from(jvm: InstalledGradleJvm): InstalledProjectJvm =
            InstalledProjectJvm(jvm.home)
    }
}

/** Exact project/JVM assignment proof retained across the subsequent Gradle import boundary. */
class AssignedInstalledProjectJvm private constructor(
    private val projectJvm: InstalledProjectJvm,
    private val project: Project,
) {
    /**
     * Proof transition: `(AssignedInstalledProjectJvm, Project) ->
     * InstalledProjectJvmAssignment`.
     *
     * Re-establishes the same admitted SDK-table identity after Gradle import for the exact project
     * carried by this capability. [InstalledProjectJvmFailure] is the closed expected failure.
     * Live project access remains inside the installed IntelliJ workspace adapter.
     */
    fun reassertAfterImport(observed: Project): InstalledProjectJvmAssignment =
        if (observed === project) {
            projectJvm.assign(observed)
        } else {
            InstalledProjectJvmAssignment.Rejected(InstalledProjectJvmFailure.PLATFORM_REJECTED)
        }

    /**
     * Proof transition: `AssignedInstalledProjectJvm -> Boolean`.
     *
     * `true` establishes that the exact live project still resolves the admitted process Java SDK
     * home. Gradle-owned module SDKs are intentionally outside this invariant because toolchains
     * may select a different Java version. This raw platform observation is internal to the
     * installed IntelliJ adapter and must run under a read action.
     */
    internal fun admitsProjectSdk(): Boolean {
        if (project.isDisposed) return false
        val sdk = ProjectRootManager.getInstance(project).projectSdk ?: return false
        return sdk.sdkType == JavaSdk.getInstance() && sdk.homePath == projectJvm.home.toString()
    }

    companion object {
        /**
         * The sole constructor authority for this proof. A value is returned only after the exact
         * project model has accepted the admitted SDK-table identity.
         */
        internal fun establish(
            projectJvm: InstalledProjectJvm,
            project: Project,
        ): InstalledProjectJvmAssignment = try {
            val assignment = Runnable {
                WriteAction.run<RuntimeException> {
                    val roots = ProjectRootManager.getInstance(project)
                    val tableName = ProjectJvmTableName.privateSidecar()
                    val table = ProjectJdkTable.getInstance()
                    val existing = table.findJdk(tableName.value)
                    val javaSdk = JavaSdk.getInstance()
                    val admitted = existing?.takeIf { sdk ->
                        sdk.sdkType == javaSdk && sdk.homePath == projectJvm.home.toString()
                    } ?: javaSdk.createJdk(tableName.value, projectJvm.home.toString(), false)
                    if (admitted !== existing) {
                        if (existing != null) table.removeJdk(existing)
                        try {
                            table.addJdk(admitted)
                        } catch (failure: RuntimeException) {
                            if (existing != null) runCatching { table.addJdk(existing) }
                            throw failure
                        }
                    }
                    try {
                        if (roots.projectSdk !== admitted) roots.projectSdk = admitted
                    } catch (failure: RuntimeException) {
                        if (admitted !== existing) {
                            runCatching { table.removeJdk(admitted) }
                            if (existing != null) runCatching { table.addJdk(existing) }
                        }
                        throw failure
                    }
                }
            }
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread) {
                assignment.run()
            } else {
                application.invokeAndWait(assignment)
            }
            InstalledProjectJvmAssignment.Assigned(
                AssignedInstalledProjectJvm(projectJvm, project),
            )
        } catch (failure: RuntimeException) {
            System.err.println("kast-indexer: project JVM assignment failed")
            failure.printStackTrace(System.err)
            InstalledProjectJvmAssignment.Rejected(InstalledProjectJvmFailure.PLATFORM_REJECTED)
        }
    }
}

internal enum class InstalledProjectOpenPreparationFailure {
    PROJECT_JVM_REJECTED,
}

internal sealed interface InstalledProjectOpenPreparationState {
    data object Pending : InstalledProjectOpenPreparationState

    data class Prepared(
        val projectJvm: AssignedInstalledProjectJvm,
    ) : InstalledProjectOpenPreparationState

    data class Rejected(
        val failure: InstalledProjectOpenPreparationFailure,
    ) : InstalledProjectOpenPreparationState
}

/** Installs the admitted project JVM before project-open state is loaded without configurators. */
internal class InstalledProjectOpenPreparation(
    private val gradleJvm: InstalledGradleJvm,
) {
    private var state: InstalledProjectOpenPreparationState =
        InstalledProjectOpenPreparationState.Pending

    /**
     * Proof transition: `Project -> InstalledProjectOpenPreparationState`.
     *
     * [InstalledProjectOpenPreparationState.Prepared] establishes the admitted project SDK before
     * the project is opened without configurators. Gradle settings are deliberately resolved only
     * after project state has loaded. [InstalledProjectOpenPreparationFailure] closes platform
     * rejection. Live project access remains inside this project-open boundary.
     */
    fun prepare(project: Project): InstalledProjectOpenPreparationState {
        if (state !is InstalledProjectOpenPreparationState.Pending) return state
        val assigned = when (val assignment = InstalledProjectJvm.from(gradleJvm).assign(project)) {
            is InstalledProjectJvmAssignment.Assigned -> assignment.projectJvm
            is InstalledProjectJvmAssignment.Rejected -> return reject(
                InstalledProjectOpenPreparationFailure.PROJECT_JVM_REJECTED,
            )
        }
        return InstalledProjectOpenPreparationState.Prepared(
            assigned,
        ).also { prepared -> state = prepared }
    }

    /** Returns the closed preparation proof produced before project-open configuration. */
    fun observe(): InstalledProjectOpenPreparationState = state

    private fun reject(
        failure: InstalledProjectOpenPreparationFailure,
    ): InstalledProjectOpenPreparationState.Rejected =
        InstalledProjectOpenPreparationState.Rejected(failure).also { rejected -> state = rejected }
}

internal sealed interface InstalledGradleLinkPresenceResolution {
    data class Resolved(
        val presence: InstalledGradleLinkPresence,
    ) : InstalledGradleLinkPresenceResolution

    data object Rejected : InstalledGradleLinkPresenceResolution
}

internal sealed interface InstalledGradleLinkPresence {
    data class Linked internal constructor(
        internal val settings: GradleProjectSettings,
    ) : InstalledGradleLinkPresence

    data object Unlinked : InstalledGradleLinkPresence
}

internal sealed interface InstalledGradleImportOperation {
    data object RefreshLinked : InstalledGradleImportOperation
    data object LinkUnlinked : InstalledGradleImportOperation
}

internal sealed interface InstalledGradleImportApplication {
    data class Applied internal constructor(
        val operation: InstalledGradleImportOperation,
    ) : InstalledGradleImportApplication

    data object Rejected : InstalledGradleImportApplication
}

/**
 * Proof transition: `GradleSettings + Path -> InstalledGradleLinkPresenceResolution`.
 *
 * Establishes whether the exact normalized root has one linked Gradle settings authority.
 * [InstalledGradleLinkPresenceResolution.Rejected] closes malformed platform paths. Raw platform
 * settings remain inside the Gradle import boundary.
 */
internal fun linkedGradleProject(
    gradleSettings: GradleSettings,
    workspaceRoot: Path,
): InstalledGradleLinkPresenceResolution {
    gradleSettings.linkedProjectsSettings.forEach { settings ->
        val rawPath = settings.externalProjectPath ?: return@forEach
        val candidate = try {
            Path.of(rawPath).toRealPath()
        } catch (_: InvalidPathException) {
            return InstalledGradleLinkPresenceResolution.Rejected
        } catch (_: IOException) {
            return InstalledGradleLinkPresenceResolution.Rejected
        } catch (_: SecurityException) {
            return InstalledGradleLinkPresenceResolution.Rejected
        }
        if (candidate == workspaceRoot) {
            return InstalledGradleLinkPresenceResolution.Resolved(
                InstalledGradleLinkPresence.Linked(settings),
            )
        }
    }
    return InstalledGradleLinkPresenceResolution.Resolved(InstalledGradleLinkPresence.Unlinked)
}

/**
 * Proof transition: `InstalledGradleLinkPresence + InstalledGradleJvm ->
 * InstalledGradleImportApplication`.
 *
 * [InstalledGradleImportApplication.Applied] establishes the only valid exact-workspace import
 * operation after the admitted Gradle JVM selector has been applied. Rejected closes platform
 * settings mutation failure. Raw selector extraction occurs only at this Gradle project-settings
 * boundary.
 */
internal fun InstalledGradleLinkPresence.applyImportJvm(
    gradleJvm: InstalledGradleJvm,
): InstalledGradleImportApplication = when (this) {
    is InstalledGradleLinkPresence.Linked -> {
        val previous = try {
            settings.gradleJvm
        } catch (_: RuntimeException) {
            return InstalledGradleImportApplication.Rejected
        }
        try {
            settings.gradleJvm = gradleJvm.projectSettingsSelector()
        } catch (_: RuntimeException) {
            runCatching { settings.gradleJvm = previous }
            return InstalledGradleImportApplication.Rejected
        }
        InstalledGradleImportApplication.Applied(InstalledGradleImportOperation.RefreshLinked)
    }
    InstalledGradleLinkPresence.Unlinked -> InstalledGradleImportApplication.Applied(
        InstalledGradleImportOperation.LinkUnlinked,
    )
}

@JvmInline
private value class ProjectJvmTableName private constructor(
    val value: String,
) {
    companion object {
        fun privateSidecar(): ProjectJvmTableName = ProjectJvmTableName(PRIVATE_SIDECAR_JVM_NAME)
    }
}

private const val PRIVATE_SIDECAR_JVM_NAME = "Kast private Java 25"
