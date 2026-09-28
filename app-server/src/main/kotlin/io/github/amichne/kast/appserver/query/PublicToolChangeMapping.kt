package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangeRequest

/** Lower public mutation inputs into the canonical live change request. */
internal fun PublicToolAddDeclaration.lowerChange(): PublicToolCanonical.Change =
    PublicToolCanonical.Change(ChangeRequest(ChangeIntentDocument.AddDeclaration(exactTarget, declaration)))

internal fun PublicToolReplaceBody.lowerChange(): PublicToolCanonical.Change =
    PublicToolCanonical.Change(ChangeRequest(ChangeIntentDocument.ReplaceBody(exactTarget, body)))
