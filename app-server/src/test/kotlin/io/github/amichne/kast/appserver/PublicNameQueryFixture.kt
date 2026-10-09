package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolDeclarationKinds
import io.github.amichne.kast.appserver.query.PublicToolNameMatch
import io.github.amichne.kast.appserver.query.PublicToolOutput
import io.github.amichne.kast.appserver.query.PublicToolQuerySymbols
import io.github.amichne.kast.appserver.query.PublicToolRunAction
import io.github.amichne.kast.appserver.query.PublicToolScope
import io.github.amichne.kast.appserver.query.PublicToolSearchSource
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal fun publicNameQuery(
    name: String = "Order",
    kinds: List<PublicToolDeclarationKinds>? = null,
    scope: PublicToolScope? = null,
    match: PublicToolNameMatch? = null,
    output: PublicToolOutput? = null,
): JsonElement =
    Json.encodeToJsonElement(
        PublicToolQuerySymbols.serializer(),
        PublicToolQuerySymbols(
            PublicToolRunAction(
                PublicToolSearchSource(
                    declarationName = (ProtocolText.parse(name) as Refinement.Refined).value,
                    nameMatch = match,
                    declarationKinds = kinds?.let { (BoundedProtocolList.create(it) as Refinement.Refined).value },
                    scope = scope,
                ),
                null,
                output,
            )
        ),
    )
