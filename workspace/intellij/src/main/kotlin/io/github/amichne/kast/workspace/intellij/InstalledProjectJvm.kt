package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
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
     * startup waits. An existing nonblank SDK name is preserved; an unnamed project receives the
     * private sidecar identity. [InstalledProjectJvmFailure] is the closed expected platform
     * failure. Raw SDK names and home paths leave only at the process-local IntelliJ SDK-table and
     * project-model boundary.
     */
    fun assign(project: Project): InstalledProjectJvmAssignment = try {
        val assignment = Runnable {
            WriteAction.run<RuntimeException> {
                val roots = ProjectRootManager.getInstance(project)
                val reference = ProjectJvmReference.from(roots.projectSdkName)
                val tableName = reference.tableName()
                val table = ProjectJdkTable.getInstance()
                val existing = table.findJdk(tableName.value)
                val javaSdk = JavaSdk.getInstance()
                val admitted = existing?.takeIf { sdk ->
                    sdk.sdkType == javaSdk && sdk.homePath == home.toString()
                } ?: javaSdk.createJdk(tableName.value, home.toString(), false)
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
        InstalledProjectJvmAssignment.Assigned(AssignedInstalledProjectJvm(this, project))
    } catch (failure: RuntimeException) {
        System.err.println("kast-indexer: project JVM assignment failed")
        failure.printStackTrace(System.err)
        InstalledProjectJvmAssignment.Rejected(InstalledProjectJvmFailure.PLATFORM_REJECTED)
    }

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
class AssignedInstalledProjectJvm internal constructor(
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
     * Proof transition: `(AssignedInstalledProjectJvm, Module) -> Boolean`.
     *
     * `true` establishes that the exact live module resolves the admitted Java SDK home. This raw
     * platform observation is internal to the installed IntelliJ adapter and must run under a read
     * action.
     */
    internal fun admits(module: Module): Boolean {
        if (module.isDisposed || module.project !== project) return false
        val sdk = ModuleRootManager.getInstance(module).sdk ?: return false
        return sdk.sdkType == JavaSdk.getInstance() && sdk.homePath == projectJvm.home.toString()
    }
}

internal enum class InstalledProjectOpenPreparationFailure {
    PROJECT_JVM_REJECTED,
    GRADLE_SETTINGS_REJECTED,
}

internal sealed interface InstalledProjectOpenPreparationState {
    data object Pending : InstalledProjectOpenPreparationState

    data class Prepared(
        val projectJvm: AssignedInstalledProjectJvm,
        val importOperation: InstalledGradleImportOperation,
    ) : InstalledProjectOpenPreparationState

    data class Rejected(
        val failure: InstalledProjectOpenPreparationFailure,
    ) : InstalledProjectOpenPreparationState
}

/** Installs the admitted project and Gradle JVMs before project-open configurators can sync. */
internal class InstalledProjectOpenPreparation(
    private val workspaceRoot: Path,
    private val gradleJvm: InstalledGradleJvm,
) {
    private var state: InstalledProjectOpenPreparationState =
        InstalledProjectOpenPreparationState.Pending

    /**
     * Proof transition: `Project -> InstalledProjectOpenPreparationState`.
     *
     * [InstalledProjectOpenPreparationState.Prepared] establishes the admitted project SDK and,
     * for an already-linked exact workspace, the admitted Gradle JVM before project-open
     * configurators run. [InstalledProjectOpenPreparationFailure] closes platform or settings
     * rejection. Live project and Gradle settings remain inside this project-open boundary.
     */
    fun prepare(project: Project): InstalledProjectOpenPreparationState {
        if (state !is InstalledProjectOpenPreparationState.Pending) return state
        val linkPresence = when (val resolution = try {
            linkedGradleProject(GradleSettings.getInstance(project), workspaceRoot)
        } catch (_: RuntimeException) {
            InstalledGradleLinkPresenceResolution.Rejected
        }) {
            is InstalledGradleLinkPresenceResolution.Resolved -> resolution.presence
            InstalledGradleLinkPresenceResolution.Rejected -> return reject(
                InstalledProjectOpenPreparationFailure.GRADLE_SETTINGS_REJECTED,
            )
        }
        val importApplication = when (val application = linkPresence.applyImportJvm(gradleJvm)) {
            is InstalledGradleImportApplication.Applied -> application
            InstalledGradleImportApplication.Rejected -> return reject(
                InstalledProjectOpenPreparationFailure.GRADLE_SETTINGS_REJECTED,
            )
        }
        val assigned = when (val assignment = InstalledProjectJvm.from(gradleJvm).assign(project)) {
            is InstalledProjectJvmAssignment.Assigned -> assignment.projectJvm
            is InstalledProjectJvmAssignment.Rejected -> {
                return when (importApplication.rollback()) {
                    InstalledGradleImportRollback.RolledBack -> reject(
                        InstalledProjectOpenPreparationFailure.PROJECT_JVM_REJECTED,
                    )
                    InstalledGradleImportRollback.Rejected -> reject(
                        InstalledProjectOpenPreparationFailure.GRADLE_SETTINGS_REJECTED,
                    )
                }
            }
        }
        return InstalledProjectOpenPreparationState.Prepared(
            assigned,
            importApplication.operation,
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
    class Applied internal constructor(
        val operation: InstalledGradleImportOperation,
        private val rollbackOperation: () -> InstalledGradleImportRollback,
    ) : InstalledGradleImportApplication {
        fun rollback(): InstalledGradleImportRollback = rollbackOperation()
    }

    data object Rejected : InstalledGradleImportApplication
}

internal sealed interface InstalledGradleImportRollback {
    data object RolledBack : InstalledGradleImportRollback
    data object Rejected : InstalledGradleImportRollback
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
 * operation and a rollback capability until project JVM assignment also succeeds. Rejected closes
 * platform settings mutation failure. Raw selector extraction occurs only at this Gradle
 * project-settings boundary.
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
        InstalledGradleImportApplication.Applied(
            InstalledGradleImportOperation.RefreshLinked,
        ) {
            try {
                settings.gradleJvm = previous
                InstalledGradleImportRollback.RolledBack
            } catch (_: RuntimeException) {
                InstalledGradleImportRollback.Rejected
            }
        }
    }
    InstalledGradleLinkPresence.Unlinked -> InstalledGradleImportApplication.Applied(
        InstalledGradleImportOperation.LinkUnlinked,
    ) { InstalledGradleImportRollback.RolledBack }
}

private sealed interface ProjectJvmReference {
    data object Unspecified : ProjectJvmReference

    data class Named(val value: String) : ProjectJvmReference

    fun tableName(): ProjectJvmTableName = when (this) {
        Unspecified -> ProjectJvmTableName.privateSidecar()
        is Named -> ProjectJvmTableName.fromProvenName(value)
    }

    companion object {
        /**
         * Proof transition: `String? -> ProjectJvmReference`.
         *
         * [Named] establishes an exact nonblank IntelliJ SDK-table name. [Unspecified] closes both
         * absent `null` names and invalid blank names without admitting a raw value inward. Nullable
         * SDK-name extraction is permitted only from `ProjectRootManager.projectSdkName` at the
         * SDK-table boundary in [InstalledProjectJvm.assign].
         */
        fun from(raw: String?): ProjectJvmReference =
            raw?.takeIf(String::isNotBlank)?.let(::Named) ?: Unspecified
    }
}

@JvmInline
private value class ProjectJvmTableName private constructor(
    val value: String,
) {
    companion object {
        fun privateSidecar(): ProjectJvmTableName = ProjectJvmTableName(PRIVATE_SIDECAR_JVM_NAME)

        fun fromProvenName(value: String): ProjectJvmTableName =
            ProjectJvmTableName(value.also { name -> require(name.isNotBlank()) })
    }
}

private const val PRIVATE_SIDECAR_JVM_NAME = "Kast private Java 25"
