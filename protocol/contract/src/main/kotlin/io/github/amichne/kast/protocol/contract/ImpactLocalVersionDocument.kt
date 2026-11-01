package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ImpactLocalVersionDocument {
    @Serializable @SerialName("UNVERSIONED") data object Unversioned : ImpactLocalVersionDocument

    @Serializable
    @SerialName("DEFINED")
    data class Defined(
        val declaration: ImpactSourceRangeDocument,
        val block: ImpactSourceRangeDocument,
        val definition: ImpactSourceRangeDocument,
        val rhs: ImpactSourceRangeDocument,
        val end: ImpactLocalLifetimeEndDocument,
    ) : ImpactLocalVersionDocument
}

@Serializable
sealed interface ImpactLocalLifetimeEndDocument {
    @Serializable
    @SerialName("NEXT_WRITE")
    data class NextWrite(val range: ImpactSourceRangeDocument, val rhs: ImpactSourceRangeDocument) :
        ImpactLocalLifetimeEndDocument

    @Serializable
    @SerialName("BLOCK_END")
    data class BlockEnd(val range: ImpactSourceRangeDocument) : ImpactLocalLifetimeEndDocument
}

internal fun ImpactValueSiteReferenceDocument.admitsLocalVersion(): Boolean =
    when (val version = localVersion) {
        ImpactLocalVersionDocument.Unversioned -> true
        is ImpactLocalVersionDocument.Defined ->
            role == ImpactValueRoleDocument.LocalBinding &&
                executionOwner == ImpactExecutionOwnerDocument.Declaration &&
                range == version.definition &&
                enclosing.range.containsExecutionRange(version.block) &&
                version.block.containsExecutionRange(version.declaration) &&
                version.block.containsExecutionRange(version.definition) &&
                version.definition.containsExecutionRange(version.rhs) &&
                (version.definition == version.declaration ||
                    version.declaration.end.value <= version.definition.start.value) &&
                version.end.admitsDefinition(version.block, version.definition)
    }

private fun ImpactLocalLifetimeEndDocument.admitsDefinition(
    block: ImpactSourceRangeDocument,
    definition: ImpactSourceRangeDocument,
): Boolean =
    when (this) {
        is ImpactLocalLifetimeEndDocument.BlockEnd -> range == block
        is ImpactLocalLifetimeEndDocument.NextWrite ->
            block.containsExecutionRange(range) &&
                definition.end.value <= range.start.value &&
                range.containsExecutionRange(rhs)
    }

internal fun ImpactCompilerTransferDocument.admitsLocalVersions(): Boolean {
    if (!source.admitsLocalVersion() || !target.admitsLocalVersion()) return false
    if (
        source.localVersion == ImpactLocalVersionDocument.Unversioned &&
            target.localVersion == ImpactLocalVersionDocument.Unversioned
    )
        return true
    if (source.enclosing != target.enclosing || source.executionOwner != target.executionOwner) return false
    val definition = target.localVersion
    return if (definition is ImpactLocalVersionDocument.Defined)
        kind == ImpactTransferKindDocument.LOCAL_BINDING && definition.rhs.containsExecutionRange(source.range)
    else kind != ImpactTransferKindDocument.LOCAL_READ || source.localVersion.admitsLocalRead(target.range)
}

internal fun ImpactLocalVersionDocument.admitsLocalRead(range: ImpactSourceRangeDocument): Boolean =
    when (this) {
        ImpactLocalVersionDocument.Unversioned -> true
        is ImpactLocalVersionDocument.Defined ->
            range.start.value >= definition.end.value &&
                when (val limit = end) {
                    is ImpactLocalLifetimeEndDocument.BlockEnd -> range.end.value <= limit.range.end.value
                    is ImpactLocalLifetimeEndDocument.NextWrite ->
                        range.end.value <= limit.range.start.value || limit.rhs.containsExecutionRange(range)
                }
    }
