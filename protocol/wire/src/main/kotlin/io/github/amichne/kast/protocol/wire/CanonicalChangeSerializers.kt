package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object CanonicalChangeSerializers {
    val changePlanRequest = jsonContractSerializer<ChangePlanRequest>(
        "kast.change.plan.request.v1",
        encode = { JsonObject(mapOf("intent" to encodeIntent(it.intent))) },
        decode = {
            val value = it.objectWithFields("intent")
            ChangePlanRequest(decodeIntent(value.getValue("intent")))
        },
    )
    val changePlanResult = textResultSerializer(
        "kast.change.plan.result.v1",
        "planIdentity",
        ChangePlanResult::planIdentity,
        ::ChangePlanResult,
    )
    val changePlanQualification =
        canonicalEnumSerializer<ChangePlanQualification>("kast.change.plan.qualification.v1")
    val changePlanRejection =
        canonicalEnumSerializer<ChangePlanRejection>("kast.change.plan.rejection.v1")

    val changeApplyRequest = textRequestSerializer(
        "kast.change.apply.request.v1",
        "planIdentity",
        ChangeApplyRequest::planIdentity,
        ::ChangeApplyRequest,
    )
    val changeApplyResult = textResultSerializer(
        "kast.change.apply.result.v1",
        "applicationIdentity",
        ChangeApplyResult::applicationIdentity,
        ::ChangeApplyResult,
    )
    val changeApplyQualification =
        canonicalEnumSerializer<ChangeApplyQualification>("kast.change.apply.qualification.v1")
    val changeApplyRejection =
        canonicalEnumSerializer<ChangeApplyRejection>("kast.change.apply.rejection.v1")

    val changeVerifyRequest = textRequestSerializer(
        "kast.change.verify.request.v1",
        "applicationIdentity",
        ChangeVerifyRequest::applicationIdentity,
        ::ChangeVerifyRequest,
    )
    val changeVerifyResult = textResultSerializer(
        "kast.change.verify.result.v1",
        "receiptIdentity",
        ChangeVerifyResult::receiptIdentity,
        ::ChangeVerifyResult,
    )
    val changeVerifyQualification =
        canonicalEnumSerializer<ChangeVerifyQualification>("kast.change.verify.qualification.v1")
    val changeVerifyRejection =
        canonicalEnumSerializer<ChangeVerifyRejection>("kast.change.verify.rejection.v1")

    val changeRecoverRequest = textRequestSerializer(
        "kast.change.recover.request.v1",
        "planIdentity",
        ChangeRecoverRequest::planIdentity,
        ::ChangeRecoverRequest,
    )
    val changeRecoverResult = jsonContractSerializer<ChangeRecoverResult>(
        "kast.change.recover.result.v1",
        encode = { JsonObject(mapOf("state" to kotlinx.serialization.json.JsonPrimitive(it.state.name.lowercase()))) },
        decode = {
            val value = it.objectWithFields("state").getValue("state").stringValue()
            try {
                ChangeRecoverResult(enumValueOf(value.uppercase()))
            } catch (_: IllegalArgumentException) {
                throw SerializationException("Invalid recovery state")
            }
        },
    )
    val changeRecoverQualification =
        canonicalEnumSerializer<ChangeRecoverQualification>("kast.change.recover.qualification.v1")
    val changeRecoverRejection =
        canonicalEnumSerializer<ChangeRecoverRejection>("kast.change.recover.rejection.v1")
}

private fun encodeIntent(intent: ChangeIntentDocument) = buildJsonObject {
    when (intent) {
        is ChangeIntentDocument.AddFile -> {
            put("kind", "add-file")
            put("relativePath", intent.relativePath.asJson())
            put("content", intent.content.asJson())
        }
        is ChangeIntentDocument.AddDeclaration -> {
            put("kind", "add-declaration")
            put("exactTarget", intent.exactTarget.asJson())
            put("declaration", intent.declaration.asJson())
        }
        is ChangeIntentDocument.ReplaceDeclaration -> {
            put("kind", "replace-declaration")
            put("exactTarget", intent.exactTarget.asJson())
            put("replacement", intent.replacement.asJson())
        }
        is ChangeIntentDocument.RenameSymbol -> {
            put("kind", "rename-symbol")
            put("exactTarget", intent.exactTarget.asJson())
            put("newName", intent.newName.asJson())
        }
    }
}

/**
 * Proof transition: `JsonElement -> ChangeIntentDocument`.
 *
 * Establishes exactly one of the four closed intent variants and its refined fields. Invalid
 * input maps through the serializer to closed [WireFailure.InvalidPayload]. Raw intent fields may
 * be extracted only here.
 */
private fun decodeIntent(element: kotlinx.serialization.json.JsonElement): ChangeIntentDocument {
    val kindObject = try {
        element.jsonObject
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid change intent")
    }
    val kind = kindObject["kind"]?.stringValue()
        ?: throw SerializationException("Missing change intent kind")
    return when (kind) {
        "add-file" -> kindObject.objectWithFields("kind", "relativePath", "content").let {
            ChangeIntentDocument.AddFile(it.protocolText("relativePath"), it.protocolText("content"))
        }
        "add-declaration" ->
            kindObject.objectWithFields("kind", "exactTarget", "declaration").let {
                ChangeIntentDocument.AddDeclaration(
                    it.protocolText("exactTarget"),
                    it.protocolText("declaration"),
                )
            }
        "replace-declaration" ->
            kindObject.objectWithFields("kind", "exactTarget", "replacement").let {
                ChangeIntentDocument.ReplaceDeclaration(
                    it.protocolText("exactTarget"),
                    it.protocolText("replacement"),
                )
            }
        "rename-symbol" -> kindObject.objectWithFields("kind", "exactTarget", "newName").let {
            ChangeIntentDocument.RenameSymbol(
                it.protocolText("exactTarget"),
                it.protocolText("newName"),
            )
        }
        else -> throw SerializationException("Unknown change intent kind")
    }
}

private fun <Request : OperationRequest> textRequestSerializer(
    serialName: String,
    field: String,
    extract: (Request) -> ProtocolText,
    construct: (ProtocolText) -> Request,
) = jsonContractSerializer(
    serialName,
    encode = { value -> JsonObject(mapOf(field to extract(value).asJson())) },
    decode = { element -> construct(element.objectWithFields(field).protocolText(field)) },
)

private fun <Result : OperationResult> textResultSerializer(
    serialName: String,
    field: String,
    extract: (Result) -> ProtocolText,
    construct: (ProtocolText) -> Result,
) = jsonContractSerializer(
    serialName,
    encode = { value -> JsonObject(mapOf(field to extract(value).asJson())) },
    decode = { element -> construct(element.objectWithFields(field).protocolText(field)) },
)
