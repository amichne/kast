package io.github.amichne.kast.change.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.contract.InstalledAddDeclarationIntentCompiler
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObserver
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure

class HostedChangePorts private constructor(
    val sourceObserver: AddDeclarationSourceObserver,
    val sourceWriter: AddDeclarationSourceWriter,
    val sourceRollback: AddDeclarationSourceRollback,
    val intentCompiler: InstalledAddDeclarationIntentCompiler,
    val semanticObserver: HostedAddDeclarationSemanticObserver,
) {
    companion object {
        internal fun retained(
            sourceObserver: AddDeclarationSourceObserver,
            sourceWriter: AddDeclarationSourceWriter,
            sourceRollback: AddDeclarationSourceRollback,
            intentCompiler: InstalledAddDeclarationIntentCompiler,
            semanticObserver: HostedAddDeclarationSemanticObserver,
        ): HostedChangePorts = HostedChangePorts(
            sourceObserver,
            sourceWriter,
            sourceRollback,
            intentCompiler,
            semanticObserver,
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
    when (val validation = ExistingProjectValidation.validate(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        ExistingProjectValidation.Validated -> Unit
        is ExistingProjectValidation.Rejected -> return HostedChangeAdmission.Rejected(
            HostedProjectAdmissionFailure.ProjectRejected(validation.failure),
        )
    }
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
                adapter()?.write(authority, durability) ?: unavailableWrite()
            },
            sourceRollback = { authority, record ->
                adapter()?.rollback(authority, record) ?: unavailableRollback()
            },
            intentCompiler = { selector, raw ->
                compileIntent(project, root, selector, raw)
            },
            semanticObserver = { workspace, plan ->
                observeHostedAddDeclaration(project, root, workspace, plan)
            },
        ),
    )
}
