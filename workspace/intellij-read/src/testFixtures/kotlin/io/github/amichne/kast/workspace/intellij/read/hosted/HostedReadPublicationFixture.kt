package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import java.nio.file.Path

/** Real detached context and publication owner; fixed original epoch, no platform access or native claim. */
class HostedReadPublicationFixture {
    private val owner = HostedReadPublicationOwner()
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    private val authority = LiveReadAuthorityFixture.create(root)
    private val model =
        (WorkspaceSearchScopeModel.compile(root, ImportedWorkspaceModelState.COMPLETE, emptyList())
                as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    val context =
        HostedSemanticReadContext(
            authority,
            model,
            IntellijSemanticSourceFileAdmission { _, _ -> error("Publication cannot inspect native source files") },
            timeAllowance = HostedSemanticTimeAllowance.admit(ReadLimits.Default, 1_000).refined(),
            freshness = HostedReadFreshness(::current, { current() }),
            publicationEffects = owner,
        )

    private fun current(): Refinement<Unit, HostedQueryFailure> =
        when (val owned = authority.withCurrentOwner { Unit }) {
            is Refinement.Refined -> owned
            is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.LiveAuthority(owned.failure))
        }

    fun commitRejected(): Refinement<Unit, HostedQueryFailure> {
        context.end()
        return owner.commitRetainedRejection()
    }

    fun discard() {
        context.end()
        owner.discard()
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Publication fixture admission failed: $failure")
    }
