package io.github.amichne.kast.change.protocol

import io.github.amichne.kast.change.contract.*

/** The four closed intent planners consumed by the single public `change.plan` operation. */
class ChangePlanningOperations(
    val addFile: AddFilePlanOperations,
    val addDeclaration: AddDeclarationPlanOperations,
    val replaceDeclaration: ReplaceDeclarationPlanOperations,
    val renameSymbol: RenameSymbolPlanOperations,
)
