package io.github.amichne.kast.idea.backend.relationships

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolIdentity

internal fun Symbol.relationshipIdentity(): SymbolIdentity =
    SymbolIdentity(
        fqName = fqName,
        kind = kind,
        declarationFile = NormalizedPath.parse(location.filePath),
        declarationStartOffset = NonNegativeInt(location.startOffset),
        containingType = containingDeclaration,
    )
