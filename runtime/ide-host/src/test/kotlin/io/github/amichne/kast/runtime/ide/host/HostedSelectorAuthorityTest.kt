package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyPublicationFailure
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedSelectorAuthorityTest {
    @Test
    fun `read token becomes hosted authority only through exact eligible topology evidence`() = runTest {
        val fixture = fixture()
        val authority = HostedSelectorAuthority(
            descriptions = HostedSymbolDescriptionOperations { token ->
                HostedSymbolDescription.Described(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_DESCRIBE.id,
                        fixture.workspace.readLease.generation,
                        SymbolDescribeResult(fixture.document.withSelector(token)),
                    ),
                )
            },
            workspace = HostedWorkspaceOperations(fixture.workspace),
            snapshotReader = fixture.store,
            contentReader = fixture.store,
        )

        val lookup = authority.exact(fixture.token)

        val selector = assertInstanceOf(HostedExactLookup.Found::class.java, lookup).selector
        assertEquals(fixture.workspace.readLease, selector.lease)
        assertEquals(fixture.symbol.evidence.compilerIdentity, selector.compilerIdentity)
        assertEquals(fixture.symbol.evidence.qualifiedIdentity, selector.qualifiedIdentity)
    }

    @Test
    fun `read token cannot bypass missing topology publication`() = runTest {
        val fixture = fixture()
        val unavailable = object : TopologySnapshotStore by fixture.store {
            override fun eligible(identity: TopologyWorkspaceIdentity) =
                TopologySnapshotEligibility.Unavailable
        }
        val authority = HostedSelectorAuthority(
            descriptions = HostedSymbolDescriptionOperations { token ->
                HostedSymbolDescription.Described(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_DESCRIBE.id,
                        fixture.workspace.readLease.generation,
                        SymbolDescribeResult(fixture.document.withSelector(token)),
                    ),
                )
            },
            workspace = HostedWorkspaceOperations(fixture.workspace),
            snapshotReader = unavailable,
            contentReader = unavailable,
        )

        assertEquals(HostedExactLookup.TopologyUnavailable, authority.exact(fixture.token))
    }

    @Test
    fun `read token cannot replace topology compiler evidence with another coherent proof`() = runTest {
        val fixture = fixture()
        val alternateSignature = CompilerSignatureDocument.Function(
            qualifiedIdentity = ProtocolText.parse("sample.target").refined(),
            receiver = CompilerReceiverDocument.Absent,
            contextReceivers = BoundedProtocolList.create(emptyList<ProtocolText>()).refined(),
            valueParameters = BoundedProtocolList.create(
                listOf(ProtocolText.parse("kotlin.String").refined()),
            ).refined(),
            typeParameterCount = CompilerTypeParameterCountDocument.parse(0).refined(),
        )
        val alternateEvidence = CompilerSymbolEvidenceDocument.fromSignature(alternateSignature)
            .refined()
        val authority = HostedSelectorAuthority(
            descriptions = HostedSymbolDescriptionOperations { token ->
                HostedSymbolDescription.Described(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_DESCRIBE.id,
                        fixture.workspace.readLease.generation,
                        SymbolDescribeResult(
                            fixture.document.withEvidence(
                                selector = token,
                                compilerEvidence = alternateEvidence,
                            ),
                        ),
                    ),
                )
            },
            workspace = HostedWorkspaceOperations(fixture.workspace),
            snapshotReader = fixture.store,
            contentReader = fixture.store,
        )

        assertEquals(HostedExactLookup.Missing, authority.exact(fixture.token))
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
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val workspace = PublishedWorkspace.publish(
            ReconciledWorkspace.admit(
                WorkspaceCandidate(root, WorkspaceStateIdentity.parse("state-v1").refined()),
                WorkspaceEvidenceKind.entries.toSet(),
                listOf(sourceRoot),
            ).refined(),
            EvidenceGeneration.parse(1).refined(),
        )
        val source = TopologySourceFile.admit(
            workspace,
            sourceRoot,
            WorkspaceSourcePath.parse("app/src/main/kotlin/App.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        val absolute = Path.of(workspace.root.value).resolve(source.path.value)
        val file = SymbolDiscoveryFileIdentity.fromBoundary(
            workspace.root,
            absolute,
            absolute.toUri().toString(),
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file,
            10,
            20,
            "target",
            "sample.target",
            CompilerSymbolKind.FUNCTION,
            CanonicalCompilerSignature.function(
                "sample.target",
                null,
                emptyList(),
                emptyList(),
                0,
            ).refined(),
        ).refined()
        val symbol = TopologySymbol.admit(source, evidence).refined()
        val complete = CompleteTopologyFile.admit(source, listOf(symbol), emptyList()).refined()
        val generation = CompleteTopologyGeneration.admit(
            workspace,
            listOf(source),
            listOf(complete),
        ).refined()
        val snapshot = Snapshot(generation)
        val content = TopologySnapshotContent.admit(snapshot, listOf(complete)).refined()
        val store = object : TopologySnapshotStore {
            override fun eligible(identity: TopologyWorkspaceIdentity) =
                if (identity == snapshot.identity) {
                    TopologySnapshotEligibility.Eligible(snapshot)
                } else {
                    TopologySnapshotEligibility.Unavailable
                }

            override fun read(snapshot: PublishedTopologySnapshot) =
                TopologySnapshotContentRead.Loaded(content)

            override fun publish(generation: CompleteTopologyGeneration) =
                TopologyPublicationResult.Rejected(TopologyPublicationFailure.SNAPSHOT_CONFLICT)
        }
        val token = ProtocolText.parse("exact:v1:1:1").refined()
        val signatureDocument = CompilerSignatureDocument.Function(
            ProtocolText.parse("sample.target").refined(),
            CompilerReceiverDocument.Absent,
            BoundedProtocolList.create(emptyList<ProtocolText>()).refined(),
            BoundedProtocolList.create(emptyList<ProtocolText>()).refined(),
            CompilerTypeParameterCountDocument.parse(0).refined(),
        )
        val compilerEvidence = CompilerSymbolEvidenceDocument.restore(
            ProtocolText.parse(evidence.compilerIdentity.value).refined(),
            signatureDocument,
        ).refined()
        val document = SymbolDocument.create(
            selector = token,
            kind = SymbolKindDocument.FUNCTION,
            name = ProtocolText.parse("target").refined(),
            qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(
                ProtocolText.parse("sample.target").refined(),
            ),
            file = ProtocolText.parse(absolute.toString()).refined(),
            range = SourceRangeDocument.create(
                ProtocolOffset.parse(10).refined(),
                ProtocolOffset.parse(20).refined(),
            ).refined(),
            compilerEvidence = compilerEvidence,
        ).refined()
        return Fixture(workspace, symbol, store, token, document)
    }

    private data class Snapshot(
        override val identity: TopologyWorkspaceIdentity,
        override val manifest: TopologySnapshotManifest,
    ) : PublishedTopologySnapshot {
        constructor(generation: CompleteTopologyGeneration) : this(
            generation.identity,
            TopologySnapshotManifest.from(generation),
        )
    }

    private data class Fixture(
        val workspace: PublishedWorkspace,
        val symbol: TopologySymbol,
        val store: TopologySnapshotStore,
        val token: ProtocolText,
        val document: SymbolDocument,
    )

    private fun SymbolDocument.withSelector(selector: ProtocolText): SymbolDocument =
        withEvidence(selector, compilerEvidence)

    private fun SymbolDocument.withEvidence(
        selector: ProtocolText,
        compilerEvidence: CompilerSymbolEvidenceDocument,
    ): SymbolDocument = SymbolDocument.create(
        selector = selector,
        kind = kind,
        name = name,
        qualifiedIdentity = qualifiedIdentity,
        file = file,
        range = range,
        compilerEvidence = compilerEvidence,
    ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
