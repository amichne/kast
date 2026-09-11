package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun nativePlanArguments(reference: String, declaration: String): JsonObject =
    Json.encodeToJsonElement(
            ChangePlanRequest.serializer(),
            ChangePlanRequest(
                ChangeIntentDocument.AddDeclaration(
                    ProtocolText.parse(reference).nativeValue(),
                    ProtocolText.parse(declaration).nativeValue(),
                )
            ),
        )
        .jsonObject
