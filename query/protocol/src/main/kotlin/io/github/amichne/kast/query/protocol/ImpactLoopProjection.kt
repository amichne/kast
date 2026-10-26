package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactLoopIterationDocument
import io.github.amichne.kast.protocol.contract.ImpactLoopProtocolDocument
import io.github.amichne.kast.protocol.contract.ImpactLoopResultDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.relation.contract.ValueLoopIteration
import io.github.amichne.kast.relation.contract.ValueLoopProtocol
import io.github.amichne.kast.relation.contract.ValueLoopResult
import io.github.amichne.kast.relation.contract.ValueLoopTransfer

internal fun ValueLoopProtocol.impactDocument(): ImpactProjected<ImpactLoopProtocolDocument> =
    iterator.enclosing
        .impactDeclaration()
        .impactZip(iterator.impactDocument())
        .impactZip(hasNext.impactDocument())
        .impactZip(next.impactDocument())
        .impactMap { (calls, next) ->
            ImpactLoopProtocolDocument(calls.first.first, calls.first.second, calls.second, next)
        }

internal fun ValueLoopIteration.impactDocument(): ImpactProjected<ImpactLoopIterationDocument> =
    protocol.impactDocument().impactMap { ImpactLoopIterationDocument(it) }

internal fun ValueLoopResult.impactDocument(): ImpactProjected<ImpactLoopResultDocument> =
    when (this) {
        is ValueLoopResult.Iterator -> protocol.impactDocument().impactMap(ImpactLoopResultDocument::Iterator)
        is ValueLoopResult.Element -> iteration.impactDocument().impactMap(ImpactLoopResultDocument::Element)
    }

internal fun ValueLoopTransfer.impactDocument(): ImpactProjected<ImpactTransferEvidenceDocument> =
    when (this) {
        is ValueLoopTransfer.Advance ->
            iteration.impactDocument().impactMap {
                ImpactTransferEvidenceDocument.LoopAdvance(it, receiverKind.impactDocument())
            }
    }
