package io.github.amichne.kast.change.contract

import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Compiler-refined add-declaration intent detached from its physical implementation. */
data class InstalledAddDeclarationIntent(
    val declaration: AddDeclarationSourceText,
    val expectedDelta: ExpectedAddDeclarationDelta,
)

enum class InstalledAddDeclarationIntentFailure {
    PROJECT_UNAVAILABLE,
    GENERATION_MOVED,
    TARGET_UNAVAILABLE,
    TARGET_NOT_KOTLIN,
    TARGET_MOVED,
    DECLARATION_REJECTED,
    COMPILER_IDENTITY_UNAVAILABLE,
}

sealed interface InstalledAddDeclarationIntentCompilation {
    data class Compiled(val intent: InstalledAddDeclarationIntent) :
        InstalledAddDeclarationIntentCompilation
    data class Rejected(val failure: InstalledAddDeclarationIntentFailure) :
        InstalledAddDeclarationIntentCompilation
}

fun interface InstalledAddDeclarationIntentCompiler {
    fun compile(
        selector: SymbolSelector,
        rawDeclaration: String,
    ): InstalledAddDeclarationIntentCompilation
}
