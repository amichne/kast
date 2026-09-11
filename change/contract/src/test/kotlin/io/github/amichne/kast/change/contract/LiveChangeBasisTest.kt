package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LiveChangeBasisTest {
    @Test
    fun `detached basis rejects root and version mismatch without creating authority`() {
        val model = model()
        assertEquals(LiveChangeBasisFailure.WRONG_ROOT, failure(LiveChangeBasis.observe(reference("/other"), model)))
        assertEquals(
            LiveChangeBasisFailure.VERSION_UNSUPPORTED,
            failure(LiveChangeBasis.observe(reference().copy(version = 0), model)),
        )
    }

    @Test
    fun `all live observation movement invalidates the planning basis`() {
        val prior = LiveChangeBasis.observe(reference(), model()).value()
        assertEquals(LiveChangeBasisComparison.UNCHANGED, prior.compare(reference(), model()))
        assertEquals(
            LiveChangeBasisComparison.WRONG_OWNER,
            prior.compare(reference().copy(host = IdeReadHostLifetime.fromBoundary(UUID(0, 2))), model()),
        )
        assertEquals(
            LiveChangeBasisComparison.EPOCH_MOVED,
            prior.compare(reference().copy(epoch = IdeReadEpochRevision.parse(2).value()), model()),
        )
        assertEquals(LiveChangeBasisComparison.MODEL_MOVED, prior.compare(reference(), model("other")))
        assertEquals(LiveChangeBasisComparison.WRONG_ROOT, prior.compare(reference("/other"), model()))
        assertEquals(
            LiveChangeBasisComparison.VERSION_UNSUPPORTED,
            prior.compare(reference().copy(version = 2), model()),
        )
    }

    @Test
    fun `live verification replaces only publication dependent obligations`() {
        val contract = LiveAddDeclarationVerificationContract.required
        val shared =
            AddDeclarationObligation.entries
                .filterNot { obligation ->
                    obligation == AddDeclarationObligation.GENERATION_UNCHANGED ||
                        obligation == AddDeclarationObligation.RESULT_GENERATION_PUBLISHED
                }
                .toSet()
        assertEquals(shared, contract.semanticObligations.toSet())
        assertEquals(LiveAddDeclarationObligation.entries.toSet(), contract.liveObligations.toSet())
    }

    private fun reference(path: String = "/workspace") =
        LiveSemanticReadReference(
            workspaceRoot = root(path),
            host = IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
            epoch = IdeReadEpochRevision.parse(1).value(),
            contentView = IdeReadContentView.SAVED_PSI_COMMITTED,
            version = LiveSemanticReadReference.VERSION,
        )

    private fun model(module: String = "app"): WorkspaceSearchScopeModel =
        when (
            val result =
                WorkspaceSearchScopeModel.compile(
                    root("/workspace"),
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module,
                            linkedBuildRoot = Path.of("/workspace"),
                            gradleProjectPath = ":",
                            sourceSetName = "main",
                            sourceRoot = Path.of("/workspace/src/main/kotlin"),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                )
        ) {
            is WorkspaceSearchScopeModelCompilation.Compiled -> result.model
            is WorkspaceSearchScopeModelCompilation.Rejected -> error(result.failures.toString())
        }

    private fun root(path: String) = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(path)).value()

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <T, F> failure(result: Refinement<T, F>): F =
        when (result) {
            is Refinement.Refined -> error("Unexpected refinement")
            is Refinement.Rejected -> result.failure
        }
}
