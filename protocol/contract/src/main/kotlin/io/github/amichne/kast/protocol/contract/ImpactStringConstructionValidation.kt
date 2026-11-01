package io.github.amichne.kast.protocol.contract

internal fun ImpactStringConstructionDocument.admitsConstruction(): Boolean =
    source.admitsExecutionOwner() &&
        source.valuePath.values.isEmpty() &&
        source.executionOwner.admits(source.enclosing, templateRange) &&
        templateRange.containsExecutionRange(entryRange) &&
        entryRange.containsExecutionRange(inputRange) &&
        inputRange.containsExecutionRange(source.range) &&
        templateRange != entryRange &&
        entryRange != inputRange

internal fun BoundedProtocolList<ImpactStringConstructionDocument>.admitsConstructions(
    source: ImpactValueSiteReferenceDocument
): Boolean = values.all { it.source.hasSameValueIdentity(source) && it.admitsConstruction() }
