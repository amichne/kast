package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.runtime.composition.protocol.CanonicalWorkspaceInspectHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDescribeHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDiscoverHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolResolveHandler
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.startCoroutine

class InstalledKastRuntimeTest {
    @Test
    fun `installed paths refine before the production graph receives authority`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val state = Files.createDirectories(temporary.resolve("state"))
        var observed: InstalledKastRuntimeRequest? = null
        val dispatch = KastRuntimeDispatchOperations { KastRuntimeDispatch.Responded(it) }

        val construction = InstalledKastRuntime.create(
            root,
            state,
            InstalledRuntimeAssembler { request ->
                observed = request
                InstalledRuntimeAssembly.Assembled(dispatch)
            },
        )

        val created = construction as InstalledKastRuntimeConstruction.Created
        assertSame(dispatch, created.dispatch)
        assertEquals(root.toRealPath(), observed?.workspaceRoot?.path)
        assertEquals(state.toRealPath(), observed?.stateDirectory?.path)
    }

    @Test
    fun `invalid installed paths fail closed before assembly`(@TempDir temporary: Path) {
        val rootWithoutSettings = Files.createDirectories(temporary.resolve("repo"))
        val missingState = temporary.resolve("missing-state")
        var invoked = false

        val construction = InstalledKastRuntime.create(
            rootWithoutSettings,
            missingState,
            InstalledRuntimeAssembler {
                invoked = true
                error("invalid paths must not reach assembly")
            },
        )

        assertFalse(invoked)
        assertEquals(
            InstalledKastRuntimeConstruction.Rejected(
                setOf(
                    InstalledKastRuntimeFailure.WorkspaceRoot(
                        InstalledWorkspaceRootFailure.SETTINGS_MARKER_UNAVAILABLE,
                    ),
                    InstalledKastRuntimeFailure.StateDirectory(
                        InstalledRuntimeStateDirectoryFailure.UNAVAILABLE,
                    ),
                ),
            ),
            construction,
        )
    }

    @Test
    fun `workspace handler projects only exact ready root authority`(@TempDir temporary: Path) {
        val rawRoot = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        Files.writeString(rawRoot.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val installedRoot = InstalledWorkspaceRoot.admit(rawRoot).refined()
        val canonicalRoot = CanonicalWorkspaceRoot.fromCanonicalPath(rawRoot).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        val reconciled = ReconciledWorkspace.admit(
            WorkspaceCandidate(canonicalRoot, WorkspaceStateIdentity.parse("state-7").refined()),
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined()
        val ready = WorkspaceRuntimeState.Ready(PublishedWorkspace.publish(reconciled, generation))
        val handler = CanonicalWorkspaceInspectHandler.create(
            installedRoot,
            WorkspaceInspectionOperations { ready },
        ).refined()

        val outcome = runImmediate { handler.execute(WorkspaceInspectRequest) }

        assertEquals(
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.WORKSPACE_INSPECT.id,
                    generation,
                    WorkspaceInspectResult(
                        ProtocolText.parse(rawRoot.toString()).refined(),
                        WorkspaceStateDocument.READY,
                    ),
                ),
            ),
            outcome,
        )
    }

    @Test
    fun `symbol handlers preserve discovery selection and exact selector authority`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val authority = CanonicalProtocolAuthority()
        val discover = CanonicalSymbolDiscoverHandler(fixture.workspace, fixture.discovery, authority)
        val resolve = CanonicalSymbolResolveHandler(fixture.exact, authority)
        val describe = CanonicalSymbolDescribeHandler(fixture.exact, authority)

        val discovered = runImmediate {
            discover.execute(
                SymbolDiscoverRequest(
                    ProtocolText.parse("sample").refined(),
                    io.github.amichne.kast.protocol.contract.ProtocolCount.parse(4).refined(),
                ),
            )
        } as OperationOutcome.Complete
        val candidate = discovered.evidence.payload.candidateSelectors.values.single()
        val resolved = runImmediate { resolve.execute(SymbolResolveRequest(candidate)) } as
            OperationOutcome.Complete
        val described = runImmediate {
            describe.execute(SymbolDescribeRequest(resolved.evidence.payload.exactSelector))
        } as OperationOutcome.Complete

        assertEquals("sample", fixture.discoveryRequest?.pattern?.value)
        assertEquals(
            fixture.resolutionRequest?.selection?.candidate?.name,
            fixture.descriptionRequest?.selector?.name,
        )
        assertEquals(11, resolved.evidence.generation.value)
        assertEquals(11, described.evidence.generation.value)
        assertEquals(
            true,
            described.evidence.payload.declaration.value.contains("sample.Sample.sample"),
        )
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected rejection: $failure")
}

private fun <Value> runImmediate(block: suspend () -> Value): Value {
    var completed: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed) { "operation suspended unexpectedly" }.getOrThrow()
}
