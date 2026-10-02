package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import org.jetbrains.kotlin.psi.KtFile

/** Root planning either proves its whole initial frontier or publishes none of it. */
internal sealed interface IntellijDeclarationInitialInventory {
    data class Complete(val partitions: List<SemanticFilePartition>) : IntellijDeclarationInitialInventory

    data object TimeLimit : IntellijDeclarationInitialInventory
}

/** Native observations remain attempt-local; only detached partitions cross request boundaries. */
internal sealed interface IntellijDeclarationPartitionObservation {
    data class Directory(val children: List<SemanticFilePartition>) : IntellijDeclarationPartitionObservation

    data class Source(val file: KtFile) : IntellijDeclarationPartitionObservation

    data object OutsideUniverse : IntellijDeclarationPartitionObservation

    data object TimeLimit : IntellijDeclarationPartitionObservation

    data class Rejected(val cause: SymbolDiscoveryBlockCause) : IntellijDeclarationPartitionObservation
}
