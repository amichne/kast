package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactLocalLifetimeEndDocument
import io.github.amichne.kast.protocol.contract.ImpactLocalVersionDocument
import io.github.amichne.kast.relation.contract.ValueLocalVersion

internal fun ValueLocalVersion.impactDocument(): ImpactProjected<ImpactLocalVersionDocument> =
    when (this) {
        ValueLocalVersion.Unversioned -> Refinement.Refined(ImpactLocalVersionDocument.Unversioned)
        is ValueLocalVersion.Defined ->
            declaration
                .impactDocument()
                .impactZip(block.impactDocument())
                .impactZip(definition.impactDocument())
                .impactZip(rhs.impactDocument())
                .impactZip(end.impactDocument())
                .impactMap { (definitionState, end) ->
                    val (position, rhs) = definitionState
                    val (owner, definition) = position
                    ImpactLocalVersionDocument.Defined(owner.first, owner.second, definition, rhs, end)
                }
    }

private fun ValueLocalVersion.End.impactDocument(): ImpactProjected<ImpactLocalLifetimeEndDocument> =
    when (this) {
        is ValueLocalVersion.End.BlockEnd -> range.impactDocument().impactMap(ImpactLocalLifetimeEndDocument::BlockEnd)
        is ValueLocalVersion.End.NextWrite ->
            range.impactDocument().impactZip(rhs.impactDocument()).impactMap { (range, rhs) ->
                ImpactLocalLifetimeEndDocument.NextWrite(range, rhs)
            }
    }
