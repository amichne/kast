package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.query.contract.QueryImpactPeerBoundary
import io.github.amichne.kast.query.contract.QueryImpactTerminal

internal fun QueryImpactTerminal.Unresolved.PeerContinuation.projectPeerTerminal():
    ImpactProjected<ImpactPathTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(site.impactDocument())
        .impactZip(boundary.target.impactDocument())
        .impactMap { (terminal, admission) ->
            ImpactPathTerminalDocument.UnresolvedPeerContinuation(
                terminal.first,
                terminal.second,
                admission,
                reason.impactDocument(),
            )
        }

internal fun QueryImpactTerminal.Unresolved.PeerContinuation.projectPeerFindingTerminal():
    ImpactProjected<ImpactFindingTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(site.impactDocument())
        .impactZip(boundary.target.impactDocument())
        .impactMap { (terminal, admission) ->
            ImpactFindingTerminalDocument.UnresolvedPeerContinuation(
                terminal.first,
                terminal.second,
                admission,
                reason.impactDocument(),
            )
        }

internal fun QueryImpactPeerBoundary.projectPeerModelWitness(): ImpactProjected<ImpactWitnessDocument> =
    model.reference.impactDocument().impactZip(model.impactDocument()).impactZip(target.impactDocument()).impactMap {
        (reviewed, admission) ->
        ImpactWitnessDocument.PeerBoundaryModel(
            reviewed.first,
            reviewed.second as ImpactBoundaryRuleDocument.Continuation,
            admission,
        )
    }
