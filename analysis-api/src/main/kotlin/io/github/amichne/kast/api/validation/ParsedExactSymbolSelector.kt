package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.protocol.ValidationException

data class ParsedExactSymbolSelector(
    val fqName: NonBlankString,
    val declarationFile: NormalizedPath,
    val declarationStartOffset: NonNegativeInt,
    val kind: SymbolKind?,
    val containingType: NonBlankString?,
)

fun KastExactSymbolSelector.parsed(): ParsedExactSymbolSelector {
    try {
        return ParsedExactSymbolSelector(
            fqName = NonBlankString(fqName),
            declarationFile = NormalizedPath.parse(declarationFile),
            declarationStartOffset = NonNegativeInt(declarationStartOffset),
            kind = kind,
            containingType = containingType?.let(::NonBlankString),
        )
    } catch (exception: ValidationException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw ValidationException(exception.message ?: "Invalid exact symbol selector")
    }
}
