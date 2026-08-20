package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyFailure
import io.github.amichne.kast.change.apply.AddDeclarationApplyResult
import io.github.amichne.kast.change.apply.MutationAdmissionFailure
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeApplyHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangePlanHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeRecoverHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeVerifyHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalDiagnosticCheckHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalRelationReadHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDescribeHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDiscoverHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolResolveHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalTraversalRunHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalWorkspaceInspectHandler
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmission
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
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
import io.github.amichne.kast.change.apply.ChangeApplyRequest as DomainChangeApplyRequest

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
                symbolDiscoverRequest("sample", 4),
            )
        } as OperationOutcome.Complete
        val candidate = (
            discovered.evidence.payload.items.values.single() as
                SymbolDiscoveryDocument.Declaration
            ).candidateSelector
        val resolved = runImmediate { resolve.execute(SymbolResolveRequest(candidate)) } as
            OperationOutcome.Complete
        val described = runImmediate {
            describe.execute(SymbolDescribeRequest(resolved.evidence.payload.exactSelector))
        } as OperationOutcome.Complete

        assertEquals(
            "sample",
            (fixture.discoveryRequest?.target as? SymbolDiscoveryTarget.Name)?.pattern?.value,
        )
        assertEquals(
            fixture.resolutionRequest?.selection?.candidate?.name,
            fixture.descriptionRequest?.selector?.name,
        )
        assertEquals(11, resolved.evidence.generation.value)
        assertEquals(11, described.evidence.generation.value)
        assertEquals(
            "sample.Sample.sample",
            (described.evidence.payload.symbol.qualifiedIdentity as
                SymbolQualifiedIdentityDocument.Available).value.value,
        )
    }

    @Test
    fun `relation and traversal retain compiler grounded endpoint authority`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val authority = CanonicalProtocolAuthority()
        val discover = CanonicalSymbolDiscoverHandler(fixture.workspace, fixture.discovery, authority)
        val resolve = CanonicalSymbolResolveHandler(fixture.exact, authority)
        val relation = CanonicalRelationReadHandler(fixture.relation, authority)
        val traversal = CanonicalTraversalRunHandler(fixture.traversal, authority)
        val candidate = (
            runImmediate {
                discover.execute(
                    symbolDiscoverRequest("sample", 4),
                )
            } as OperationOutcome.Complete
                        ).evidence.payload.items.values.single().let { item ->
                            (item as SymbolDiscoveryDocument.Declaration).candidateSelector
                        }
        val exact = (
            runImmediate { resolve.execute(SymbolResolveRequest(candidate)) } as
                OperationOutcome.Complete
                    ).evidence.payload.exactSelector

        val related = runImmediate {
            relation.execute(
                RelationReadRequest(
                    exact,
                    RelationKindDocument.REFERENCES,
                    io.github.amichne.kast.protocol.contract.ProtocolCount.parse(4).refined(),
                ),
            )
        } as OperationOutcome.Complete
        val traversed = runImmediate {
            traversal.execute(
                TraversalRunRequest(
                    exact,
                    RelationKindDocument.REFERENCES,
                    io.github.amichne.kast.protocol.contract.ProtocolCount.parse(1).refined(),
                    io.github.amichne.kast.protocol.contract.ProtocolCount.parse(4).refined(),
                ),
            )
        } as OperationOutcome.Qualified

        assertEquals(1, related.evidence.payload.targets.values.size)
        assertEquals(1, traversed.evidence.payload.reached.values.size)
        assertEquals(TraversalRunQualification.DEPTH_LIMIT, traversed.qualification)
    }

    @Test
    fun `diagnostic handler binds exact file scope to current generation`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val handler = CanonicalDiagnosticCheckHandler(fixture.workspace, fixture.diagnostic)

        val outcome = runImmediate {
            handler.execute(
                DiagnosticCheckRequest(
                    ProtocolText.parse("src/main/kotlin/Sample.kt").refined(),
                    io.github.amichne.kast.protocol.contract.ProtocolCount.parse(4).refined(),
                ),
            )
        } as OperationOutcome.Complete

        assertEquals(11, outcome.evidence.generation.value)
        assertEquals(
            true,
            outcome.evidence.payload.diagnostics.values.single().value.contains("KAST001"),
        )
    }

    @Test
    fun `change execution rejects identities that were never issued`() {
        val authority = CanonicalChangeAuthority()
        val missing = ProtocolText.parse("missing").refined()
        val apply = CanonicalChangeApplyHandler(
            WorkspaceInspectionOperations { error("missing plans must not inspect workspace") },
            { error("missing plans must not reach apply") },
            authority,
        )
        val verify = CanonicalChangeVerifyHandler(
            { error("missing applications must not reach verify") },
            authority,
        )
        val recover = CanonicalChangeRecoverHandler(
            { error("missing plans must not reach recovery") },
            authority,
        )

        assertEquals(
            OperationOutcome.Rejected(ChangeApplyRejection.PLAN_NOT_FOUND),
            runImmediate { apply.execute(ChangeApplyRequest(missing)) },
        )
        assertEquals(
            OperationOutcome.Rejected(ChangeVerifyRejection.APPLICATION_NOT_FOUND),
            runImmediate { verify.execute(ChangeVerifyRequest(missing)) },
        )
        assertEquals(
            OperationOutcome.Rejected(ChangeRecoverRejection.PLAN_NOT_FOUND),
            runImmediate { recover.execute(ChangeRecoverRequest(missing)) },
        )
    }

    @Test
    fun `change plan identity retains the exact typed plan for apply`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledChangeProtocolFixture.create(root)
        val authority = CanonicalChangeAuthority()
        val planning = ChangePlanningOperations(
            PureAddFilePlanningService(),
            PureAddDeclarationPlanningService(),
            PureReplaceDeclarationPlanningService(),
            PureRenameSymbolPlanningService(),
        )
        val plan = CanonicalChangePlanHandler(
            planning,
            ChangePlanAdmissionOperations { ChangePlanAdmission.AddFile(fixture.addFile) },
            CanonicalProtocolAuthority(),
            authority,
        )
        var observed: DomainChangeApplyRequest? = null
        val apply = CanonicalChangeApplyHandler(
            fixture.workspace,
            { request ->
                observed = request
                AddDeclarationApplyResult.Rejected(
                    AddDeclarationApplyFailure.Admission(MutationAdmissionFailure.WRONG_ROOT),
                )
            },
            authority,
        )
        val planned = runImmediate {
            plan.execute(
                ChangePlanRequest(
                    ChangeIntentDocument.AddFile(
                        ProtocolText.parse("src/main/kotlin/sample/Added.kt").refined(),
                        ProtocolText.parse("package sample\n\nclass Added\n").refined(),
                    ),
                ),
            )
        } as OperationOutcome.Complete

        assertEquals(
            OperationOutcome.Rejected(ChangeApplyRejection.ROOT_MISMATCH),
            runImmediate {
                apply.execute(ChangeApplyRequest(planned.evidence.payload.planIdentity))
            },
        )
        assertEquals(fixture.addFile.target.lease, observed?.plan?.priorLease)
        assertEquals(fixture.addFile.target.file, observed?.plan?.writes?.entries?.single()?.source)
    }
}

private fun symbolDiscoverRequest(raw: String, limit: Int): SymbolDiscoverRequest =
    SymbolDiscoverRequest(
        SymbolDiscoverTargetDocument.Name(
            ProtocolText.parse(raw).refined(),
            SymbolNameKindDocument.SYMBOL,
            SymbolDiscoveryMatchDocument.FUZZY,
        ),
        io.github.amichne.kast.protocol.contract.ProtocolCount.parse(limit).refined(),
    )

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
