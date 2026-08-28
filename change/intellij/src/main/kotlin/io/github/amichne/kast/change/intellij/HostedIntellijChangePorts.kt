package io.github.amichne.kast.change.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure
import java.util.concurrent.ConcurrentHashMap

class HostedChangePorts private constructor(
    val sourceObserver: AddDeclarationSourceObserver,
    val sourceWriter: AddDeclarationSourceWriter,
    val sourceRollback: AddDeclarationSourceRollback,
    val recoveryRollback: AddDeclarationRollbackPort,
    val intentCompiler: InstalledAddDeclarationIntentCompiler,
) {
    companion object {
        internal fun retained(
            sourceObserver: AddDeclarationSourceObserver,
            sourceWriter: AddDeclarationSourceWriter,
            sourceRollback: AddDeclarationSourceRollback,
            recoveryRollback: AddDeclarationRollbackPort,
            intentCompiler: InstalledAddDeclarationIntentCompiler,
        ): HostedChangePorts = HostedChangePorts(
            sourceObserver,
            sourceWriter,
            sourceRollback,
            recoveryRollback,
            intentCompiler,
        )
    }
}

sealed interface HostedChangeAdmission {
    data class Admitted(val ports: HostedChangePorts) : HostedChangeAdmission
    data class Rejected(val failure: HostedProjectAdmissionFailure) : HostedChangeAdmission
}

/** Direct-Project hosted source effect admission with no ambient project rediscovery. */
fun admitHostedIntellijChangePorts(
    project: Project,
    root: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
): HostedChangeAdmission {
    when (val admission = AdmittedIdeProject.admit(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        is ExistingProjectAdmission.Admitted -> Unit
        is ExistingProjectAdmission.Rejected -> return HostedChangeAdmission.Rejected(
            HostedProjectAdmissionFailure.ProjectRejected(admission.failure),
        )
    }
    val authorities = ConcurrentHashMap<String, MutationAuthority>()
    fun adapter(): IntellijChangeSourceAdapter? = if (project.isDisposed) {
        null
    } else {
        IntellijChangeSourceAdapter(project)
    }
    return HostedChangeAdmission.Admitted(
        HostedChangePorts.retained(
            sourceObserver = { source ->
                adapter()?.observe(source) ?: unavailableObservation()
            },
            sourceWriter = { authority, durability ->
                authorities[authority.binding.value] = authority
                adapter()?.write(authority, durability) ?: unavailableWrite()
            },
            sourceRollback = { authority, record ->
                adapter()?.rollback(authority, record) ?: unavailableRollback()
            },
            recoveryRollback = recovery@{ record ->
                val authority = authorities[record.binding.value]
                    ?: return@recovery unavailableRollback()
                adapter()?.rollback(authority, record) ?: unavailableRollback()
            },
            intentCompiler = { selector, raw ->
                compileIntent(project, root, selector, raw)
            },
        ),
    )
}
