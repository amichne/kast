package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.change.verify.ResultingGenerationPublication
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchFailure
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchResult
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationSerialization
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.service.ResultingWorkspacePublication
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedTopologyLifecycleTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `live workspace publication advances generation and stales the prior lease`() {
        val fixture = fixture()
        val resulting = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(
                    fixture.workspace.root,
                    WorkspaceStateIdentity.parse("state-v2").refined(),
                ),
                WorkspaceEvidenceKind.entries.toSet(),
                fixture.workspace.sourceRoots,
            ).refined(),
            EvidenceGeneration.parse(2).refined(),
        )
        val workspaces = HostedWorkspaceOperations(
            fixture.workspace,
            HostedWorkspaceTransitionOperations { current, cause ->
                if (
                    current.readLease == fixture.workspace.readLease &&
                    cause == HostedWorkspaceTransitionCause.PROVEN_SOURCE_TRANSITION
                ) {
                    HostedWorkspaceTransition.Published(
                        ResultingWorkspacePublication.admit(
                            current.readLease,
                            resulting,
                        ).refined(),
                    )
                } else {
                    HostedWorkspaceTransition.Unchanged
                }
            },
        )

        assertEquals(
            ResultingGenerationPublication.Published(resulting),
            workspaces.publishAfter(fixture.workspace.readLease),
        )
        val observed = assertInstanceOf(
            WorkspaceRuntimeState.Ready::class.java,
            workspaces.inspect(),
        )

        assertEquals(resulting.readLease, observed.workspace.readLease)
        assertInstanceOf(
            SemanticReadLeaseUse.Moved::class.java,
            workspaces.whileCurrent(fixture.workspace.readLease) { "stale" },
        )
        assertEquals(
            SemanticReadLeaseUse.Completed("current"),
            workspaces.whileCurrent(resulting.readLease) { "current" },
        )
    }

    @Test
    fun `lease guarded effect excludes a source invalidation sharing its serialization`() {
        val fixture = fixture()
        val serialization = WorkspacePublicationSerialization()
        val workspaces = HostedWorkspaceOperations(
            fixture.workspace,
            serialization = serialization,
        )
        val effectEntered = CountDownLatch(1)
        val releaseEffect = CountDownLatch(1)
        val invalidationAttempted = CountDownLatch(1)
        val invalidationEntered = CountDownLatch(1)
        val guarded = AtomicReference<SemanticReadLeaseUse<String>>()
        val effectThread = thread(start = true) {
            guarded.set(workspaces.whileCurrent(fixture.workspace.readLease) {
                effectEntered.countDown()
                check(releaseEffect.await(5, TimeUnit.SECONDS))
                "completed"
            })
        }
        assertTrue(effectEntered.await(5, TimeUnit.SECONDS))
        val invalidationThread = thread(start = true) {
            invalidationAttempted.countDown()
            serialization.serialized { invalidationEntered.countDown() }
        }
        assertTrue(invalidationAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(invalidationEntered.await(100, TimeUnit.MILLISECONDS))

        releaseEffect.countDown()
        effectThread.join(5_000)
        invalidationThread.join(5_000)

        assertEquals(SemanticReadLeaseUse.Completed("completed"), guarded.get())
        assertTrue(invalidationEntered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `live read dispatch promotes only the runtime staged for the published generation`() =
        runTest {
            val fixture = fixture()
            val resulting = PublishedWorkspace.publish(
                ReconciledWorkspace.admit(
                    WorkspaceCandidate(
                        fixture.workspace.root,
                        WorkspaceStateIdentity.parse("state-v2").refined(),
                    ),
                    WorkspaceEvidenceKind.entries.toSet(),
                    fixture.workspace.sourceRoots,
                ).refined(),
                EvidenceGeneration.parse(2).refined(),
            )
            var current: WorkspaceRuntimeState = WorkspaceRuntimeState.Ready(fixture.workspace)
            val dispatch = HostedGenerationReadDispatch(
                fixture.workspace.readLease.generation,
                HostedReadDispatchOperations {
                    IdeReadRuntimeDispatchResult.Responded("generation-one")
                },
                WorkspaceInspectionOperations { current },
            )

            assertEquals(
                IdeReadRuntimeDispatchResult.Responded("generation-one"),
                dispatch.dispatch(workspaceInspectDocument()),
            )
            assertEquals(
                HostedReadRuntimeStaging.Staged,
                dispatch.stage(
                    fixture.workspace.readLease.generation,
                    resulting.readLease.generation,
                    HostedReadDispatchOperations {
                        IdeReadRuntimeDispatchResult.Responded("generation-two")
                    },
                ),
            )
            current = WorkspaceRuntimeState.Ready(resulting)

            assertEquals(
                IdeReadRuntimeDispatchResult.Responded("generation-two"),
                dispatch.dispatch(workspaceInspectDocument()),
            )
        }

    @Test
    fun `live read dispatch rejects a result when workspace moves during dispatch`() = runTest {
        val fixture = fixture()
        val resulting = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(
                    fixture.workspace.root,
                    WorkspaceStateIdentity.parse("state-v2").refined(),
                ),
                WorkspaceEvidenceKind.entries.toSet(),
                fixture.workspace.sourceRoots,
            ).refined(),
            EvidenceGeneration.parse(2).refined(),
        )
        var current: WorkspaceRuntimeState = WorkspaceRuntimeState.Ready(fixture.workspace)
        val dispatch = HostedGenerationReadDispatch(
            fixture.workspace.readLease.generation,
            HostedReadDispatchOperations {
                current = WorkspaceRuntimeState.Ready(resulting)
                IdeReadRuntimeDispatchResult.Responded("stale-generation-one")
            },
            WorkspaceInspectionOperations { current },
        )
        assertEquals(
            HostedReadRuntimeStaging.Staged,
            dispatch.stage(
                fixture.workspace.readLease.generation,
                resulting.readLease.generation,
                HostedReadDispatchOperations {
                    IdeReadRuntimeDispatchResult.Responded("generation-two")
                },
            ),
        )

        assertEquals(
            IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.RuntimeGenerationUnavailable,
            ),
            dispatch.dispatch(workspaceInspectDocument()),
        )
    }

    @Test
    fun `effect routing remains available when the read generation has not been staged`() =
        runTest {
            val fixture = fixture()
            val resulting = PublishedWorkspace.publish(
                ReconciledWorkspace.admit(
                    WorkspaceCandidate(
                        fixture.workspace.root,
                        WorkspaceStateIdentity.parse("state-v2").refined(),
                    ),
                    WorkspaceEvidenceKind.entries.toSet(),
                    fixture.workspace.sourceRoots,
                ).refined(),
                EvidenceGeneration.parse(2).refined(),
            )
            val dispatch = HostedGenerationReadDispatch(
                fixture.workspace.readLease.generation,
                HostedReadDispatchOperations {
                    error("an effect route must not require a generation-bound read runtime")
                },
                WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(resulting) },
            )
            val request = when (val encoded = CanonicalOperationWireBindings.changeApply.encodeRequest(
                ChangeApplyRequest(ProtocolText.parse("plan:${"a".repeat(64)}").refined()),
            )) {
                is WireEncoding.Encoded -> encoded.document
                is WireEncoding.Rejected -> error(encoded.failure.toString())
            }

            assertEquals(
                IdeReadRuntimeDispatchResult.Rejected(
                    IdeReadRuntimeDispatchFailure.UnsupportedOperation(
                        CanonicalOperation.CHANGE_APPLY,
                    ),
                ),
                dispatch.dispatch(request),
            )
        }

    @Test
    fun `topology build survives host restart and is reused without extraction`() = runTest {
        val fixture = fixture()
        val state = HostedWorkspaceStateLocation.locate(
            KastUserStateRoot.parse(temporary.toString()).refined(),
            fixture.workspace.root,
        ).refined()
        val extractionCalls = AtomicInteger()
        val candidates = TopologyCandidateEnumerator { fixture.enumeration }
        val extractor = TopologyFileExtractor {
            extractionCalls.incrementAndGet()
            TopologyFileExtraction.Complete(fixture.complete)
        }

        val first = HostedTopologyComposition.create(
            HostedWorkspaceOperations(fixture.workspace),
            HostedTopologyRuntimePorts(candidates, extractor, open(state)),
        ).build.build()
        assertInstanceOf(TopologyBuildResult.Published::class.java, first)
        assertEquals(1, extractionCalls.get())

        val reopened = HostedTopologyComposition.create(
            HostedWorkspaceOperations(fixture.workspace),
            HostedTopologyRuntimePorts(candidates, extractor, open(state)),
        ).build.build()
        assertInstanceOf(TopologyBuildResult.Reused::class.java, reopened)
        assertEquals(1, extractionCalls.get())
    }

    private fun open(state: HostedWorkspaceStateLocation): SqliteTopologySnapshotStore = when (
        val opened = SqliteTopologySnapshotStore.open(state.topologyDatabase)
    ) {
        is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
        is SqliteTopologySnapshotStoreOpening.Rejected -> error(opened.failure.toString())
    }

    private fun workspaceInspectDocument(): String = when (
        val encoded = CanonicalOperationWireBindings.workspaceInspect.encodeRequest(
            WorkspaceInspectRequest,
        )
    ) {
        is WireEncoding.Encoded -> encoded.document
        is WireEncoding.Rejected -> error(encoded.failure.toString())
    }

    private fun fixture(): Fixture {
        val sourceRoot = SourceRoot.admit(
            GradleSourceRootEvidence(
                ideaModuleName = "app",
                workspaceRelativeBuildRoot = ".",
                gradleProjectPath = ":app",
                sourceSetName = "main",
                workspaceRelativeSourceRoot = "app/src/main/kotlin",
                provenance = SourceRootProvenance.Authored,
            ),
        ).refined()
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(
            temporary.resolve("workspace"),
        ).refined()
        val reconciled = ReconciledWorkspace.admit(
            WorkspaceCandidate(root, WorkspaceStateIdentity.parse("state-v1").refined()),
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(sourceRoot),
        ).refined()
        val workspace = PublishedWorkspace.publish(
            reconciled,
            EvidenceGeneration.parse(1).refined(),
        )
        val file = TopologySourceFile.admit(
            workspace,
            sourceRoot,
            WorkspaceSourcePath.parse("app/src/main/kotlin/App.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        val candidates = TopologyCandidateSet.admit(workspace, listOf(file)).refined()
        val complete = CompleteTopologyFile.admit(file, emptyList(), emptyList()).refined()
        return Fixture(
            workspace,
            TopologyCandidateEnumeration.Complete(candidates),
            complete,
        )
    }

    private data class Fixture(
        val workspace: PublishedWorkspace,
        val enumeration: TopologyCandidateEnumeration.Complete,
        val complete: CompleteTopologyFile,
    )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
