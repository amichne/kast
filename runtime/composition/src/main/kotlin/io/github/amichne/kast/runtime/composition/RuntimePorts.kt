package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.contract.InstalledAddDeclarationIntentCompiler
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.verify.ChangeVerificationObserver
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import java.util.concurrent.Executor

/** Narrow workspace effects from which composition constructs the publication coordinator. */
data class WorkspaceRuntimePorts(
    val reconciliation: WorkspaceReconciliationPort,
    val publication: WorkspacePublicationTransaction,
)

/** Request-local semantic compiler effects consumed by host-neutral target services. */
data class SemanticRuntimePorts(
    val symbolDiscovery: SymbolCompilerPort,
    val symbolExact: SymbolExactCompilerPort,
    val sourceRead: SourceReadPort,
    val relation: RelationCompilerPort,
    val diagnostic: DiagnosticCompilerPort,
)

/** Explicit topology build and durable read effects owned by runtime composition. */
data class TopologyRuntimePorts(
    val candidates: TopologyCandidateEnumerator,
    val extractor: TopologyFileExtractor,
    val snapshots: TopologySnapshotStore,
)

/** Physical index refresh and asynchronous execution effects owned by runtime composition. */
data class IndexRuntimePorts(
    val refresh: WorkspaceIndexRefreshOperations,
    val asynchronousExecutor: Executor,
)

/** Narrow durable, physical, and resulting-proof effects for the closed change workflow. */
data class ChangeRuntimePorts(
    val recoveryEvidence: MutationRecoveryEvidenceStore,
    val sourceObserver: AddDeclarationSourceObserver,
    val sourceWriter: AddDeclarationSourceWriter,
    val sourceRollback: AddDeclarationSourceRollback,
    val recoveryRollback: AddDeclarationRollbackPort,
    val verificationObserver: ChangeVerificationObserver,
)

/** Physical change effects retained while composition owns durable recovery evidence. */
internal data class InstalledChangePhysicalPorts(
    val sourceObserver: AddDeclarationSourceObserver,
    val sourceWriter: AddDeclarationSourceWriter,
    val sourceRollback: AddDeclarationSourceRollback,
    val recoveryRollback: AddDeclarationRollbackPort,
    val intentCompiler: InstalledAddDeclarationIntentCompiler,
)
