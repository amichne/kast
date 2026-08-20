package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Path

enum class InstalledProjectJvmFailure { PLATFORM_REJECTED }

sealed interface InstalledProjectJvmAssignment {
    data object Assigned : InstalledProjectJvmAssignment

    data class Rejected(
        val failure: InstalledProjectJvmFailure,
    ) : InstalledProjectJvmAssignment
}

/** Isolated IntelliJ project SDK identity derived only from the admitted process Java home. */
class InstalledProjectJvm private constructor(
    internal val home: Path,
) {
    /**
     * Proof transition: `InstalledProjectJvm + Project -> InstalledProjectJvmAssignment`.
     *
     * Establishes that any existing unresolved project SDK name resolves to a Java SDK created from
     * the admitted physical home before startup waits. The project SDK name itself is never changed,
     * so the repository project model remains unmodified. [InstalledProjectJvmFailure] is the closed
     * expected platform failure. Raw SDK names and home paths leave only at the isolated SDK-table
     * boundary.
     */
    fun assign(project: Project): InstalledProjectJvmAssignment = try {
        val assignment = Runnable {
            WriteAction.run<RuntimeException> {
                when (
                    val reference = ProjectJvmReference.from(
                        ProjectRootManager.getInstance(project).projectSdkName,
                    )
                ) {
                    ProjectJvmReference.Unspecified -> Unit
                    is ProjectJvmReference.Named -> {
                        val table = ProjectJdkTable.getInstance()
                        val existing = table.findJdk(reference.value)
                        if (existing?.homePath != home.toString()) {
                            if (existing != null) table.removeJdk(existing)
                            JavaSdk.getInstance()
                                .createJdk(reference.value, home.toString(), false)
                                .also(table::addJdk)
                        }
                    }
                }
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            assignment.run()
        } else {
            application.invokeAndWait(assignment)
        }
        InstalledProjectJvmAssignment.Assigned
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

private sealed interface ProjectJvmReference {
    data object Unspecified : ProjectJvmReference

    data class Named(val value: String) : ProjectJvmReference

    companion object {
        /** Refines a nullable platform SDK name into closed specified or unspecified state. */
        fun from(raw: String?): ProjectJvmReference =
            raw?.takeIf(String::isNotBlank)?.let(::Named) ?: Unspecified
    }
}
