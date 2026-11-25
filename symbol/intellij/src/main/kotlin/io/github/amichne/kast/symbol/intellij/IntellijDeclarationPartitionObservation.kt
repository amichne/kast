package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiJavaFile
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import org.jetbrains.kotlin.psi.KtFile

/** Root planning either proves its whole initial frontier or publishes none of it. */
internal sealed interface IntellijDeclarationInitialInventory {
    data class Complete(val partitions: List<SemanticFilePartition>) : IntellijDeclarationInitialInventory

    data class Rejected(val cause: SymbolDiscoveryBlockCause) : IntellijDeclarationInitialInventory

    data object TimeLimit : IntellijDeclarationInitialInventory
}

/** Native observations remain attempt-local; only detached partitions cross request boundaries. */
internal sealed interface IntellijDeclarationPartitionObservation {
    data class Directory(val children: List<SemanticFilePartition>) : IntellijDeclarationPartitionObservation

    /** One authored source file; its language decides which declarations it can supply. */
    sealed interface Source : IntellijDeclarationPartitionObservation {
        val file: PsiClassOwner

        data class Kotlin(override val file: KtFile) : Source

        /** Only an inventory without a name reaches Java files; exact names use the Java indexes. */
        data class Java(override val file: PsiJavaFile) : Source
    }

    data object OutsideUniverse : IntellijDeclarationPartitionObservation

    data object TimeLimit : IntellijDeclarationPartitionObservation

    data class Rejected(val cause: SymbolDiscoveryBlockCause) : IntellijDeclarationPartitionObservation
}
