package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.verify.ChangeVerificationObserver
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort

/** Narrow workspace effects from which composition constructs the publication coordinator. */
data class WorkspaceRuntimePorts(
    val reconciliation: WorkspaceReconciliationPort,
    val publication: WorkspacePublicationTransaction,
)

/** Request-local semantic compiler effects consumed by host-neutral target services. */
data class SemanticRuntimePorts(
    val symbolDiscovery: SymbolCompilerPort,
    val symbolExact: SymbolExactCompilerPort,
    val relation: RelationCompilerPort,
    val diagnostic: DiagnosticCompilerPort,
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
    val verificationObserver: ChangeVerificationObserver,
)
