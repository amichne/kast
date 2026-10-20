package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.query.contract.QueryImpactPeerBoundary
import io.github.amichne.kast.query.contract.QueryImpactTerminal

internal fun QueryImpactTerminal.Unresolved.PeerContinuation.projectPeerTerminal(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(site.impactDocument(references))
        .impactZip(boundary.target.impactDocument())
        .impactMap { (terminal, admission) ->
            ImpactPathTerminalDocument.UnresolvedPeerContinuation(
                terminal.first,
                terminal.second,
                admission,
                reason.impactDocument(),
            )
        }

internal fun QueryImpactTerminal.Unresolved.PeerContinuation.projectPeerFindingTerminal(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactFindingTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(site.impactDocument(references))
        .impactZip(boundary.target.impactDocument())
        .impactMap { (terminal, admission) ->
            ImpactFindingTerminalDocument.UnresolvedPeerContinuation(
                terminal.first,
                terminal.second,
                admission,
                reason.impactDocument(),
            )
        }

internal fun QueryImpactPeerBoundary.projectPeerModelWitness(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactWitnessDocument> =
    model.reference
        .impactDocument()
        .impactZip(model.impactDocument(references))
        .impactZip(target.impactDocument())
        .impactMap { (reviewed, admission) ->
            ImpactWitnessDocument.PeerBoundaryModel(
                reviewed.first,
                reviewed.second as ImpactBoundaryRuleDocument.Continuation,
                admission,
            )
        }
