package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

internal object CanonicalReadSerializers {
    val workspaceInspectRequest = jsonContractSerializer<WorkspaceInspectRequest>(
        "kast.workspace.inspect.request.v1",
        encode = { JsonObject(emptyMap()) },
        decode = { it.objectWithFields(); WorkspaceInspectRequest },
    )
    val workspaceInspectResult = jsonContractSerializer<WorkspaceInspectResult>(
        "kast.workspace.inspect.result.v1",
        encode = {
            buildJsonObject {
                put("canonicalRoot", it.canonicalRoot.asJson())
                put("state", it.state.name.lowercase())
            }
        },
        decode = {
            val value = it.objectWithFields("canonicalRoot", "state")
            WorkspaceInspectResult(
                value.protocolText("canonicalRoot"),
                enumValue<WorkspaceStateDocument>(value, "state"),
            )
        },
    )
    val workspaceInspectQualification =
        canonicalEnumSerializer<WorkspaceInspectQualification>("kast.workspace.inspect.qualification.v1")
    val workspaceInspectRejection =
        canonicalEnumSerializer<WorkspaceInspectRejection>("kast.workspace.inspect.rejection.v1")

    val symbolDiscoverRequest = CanonicalSymbolSerializers.discoverRequest
    val symbolDiscoverResult = CanonicalSymbolSerializers.discoverResult
    val symbolDiscoverQualification = jsonContractSerializer<SymbolDiscoverQualification>(
        "kast.symbol.discover.qualification.v1",
        encode = { qualification ->
            buildJsonObject {
                put(
                    "limitations",
                    JsonArray(qualification.limitations.map { JsonPrimitive(it.wireName()) }),
                )
            }
        },
        decode = { element ->
            val value = element.objectWithFields("limitations")
            val limitations = try {
                value.getValue("limitations").jsonArray
                    .map { symbolDiscoverLimitation(it.stringValue()) }
                    .toSet()
            } catch (_: IllegalArgumentException) {
                throw SerializationException("Invalid limitations")
            }
            when (val refined = SymbolDiscoverQualification.from(limitations)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> throw SerializationException("Invalid limitations")
            }
        },
    )
    val symbolDiscoverRejection =
        canonicalEnumSerializer<SymbolDiscoverRejection>("kast.symbol.discover.rejection.v1")

    val symbolResolveRequest = textRequestSerializer(
        "kast.symbol.resolve.request.v1",
        "candidateSelector",
        SymbolResolveRequest::candidateSelector,
        ::SymbolResolveRequest,
    )
    val symbolResolveResult = textResultSerializer(
        "kast.symbol.resolve.result.v1",
        "exactSelector",
        SymbolResolveResult::exactSelector,
        ::SymbolResolveResult,
    )
    val symbolResolveQualification =
        canonicalEnumSerializer<SymbolResolveQualification>("kast.symbol.resolve.qualification.v1")
    val symbolResolveRejection =
        canonicalEnumSerializer<SymbolResolveRejection>("kast.symbol.resolve.rejection.v1")

    val symbolDescribeRequest = textRequestSerializer(
        "kast.symbol.describe.request.v1",
        "exactSelector",
        SymbolDescribeRequest::exactSelector,
        ::SymbolDescribeRequest,
    )
    val symbolDescribeResult = CanonicalSymbolSerializers.describeResult
    val symbolDescribeQualification =
        canonicalEnumSerializer<SymbolDescribeQualification>("kast.symbol.describe.qualification.v1")
    val symbolDescribeRejection =
        canonicalEnumSerializer<SymbolDescribeRejection>("kast.symbol.describe.rejection.v1")

    val relationReadRequest = jsonContractSerializer<RelationReadRequest>(
        "kast.relation.read.request.v1",
        encode = {
            buildJsonObject {
                put("exactSelector", it.exactSelector.asJson())
                put("relation", it.relation.name.lowercase())
                put("limit", it.limit.asJson())
            }
        },
        decode = {
            val value = it.objectWithFields("exactSelector", "relation", "limit")
            RelationReadRequest(
                value.protocolText("exactSelector"),
                enumValue(value, "relation"),
                value.protocolCount("limit"),
            )
        },
    )
    val relationReadResult = CanonicalSymbolSerializers.relationResult
    val relationReadQualification =
        canonicalEnumSerializer<RelationReadQualification>("kast.relation.read.qualification.v1")
    val relationReadRejection =
        canonicalEnumSerializer<RelationReadRejection>("kast.relation.read.rejection.v1")

    val traversalRunRequest = jsonContractSerializer<TraversalRunRequest>(
        "kast.traversal.run.request.v1",
        encode = {
            buildJsonObject {
                put("exactSelector", it.exactSelector.asJson())
                put("relation", it.relation.name.lowercase())
                put("maximumDepth", it.maximumDepth.asJson())
                put("maximumResults", it.maximumResults.asJson())
            }
        },
        decode = {
            val value = it.objectWithFields(
                "exactSelector",
                "relation",
                "maximumDepth",
                "maximumResults",
            )
            TraversalRunRequest(
                value.protocolText("exactSelector"),
                enumValue(value, "relation"),
                value.protocolCount("maximumDepth"),
                value.protocolCount("maximumResults"),
            )
        },
    )
    val traversalRunResult = CanonicalSymbolSerializers.traversalResult
    val traversalRunQualification =
        canonicalEnumSerializer<TraversalRunQualification>("kast.traversal.run.qualification.v1")
    val traversalRunRejection =
        canonicalEnumSerializer<TraversalRunRejection>("kast.traversal.run.rejection.v1")

    val diagnosticCheckRequest = jsonContractSerializer<DiagnosticCheckRequest>(
        "kast.diagnostic.check.request.v1",
        encode = {
            buildJsonObject {
                put("scope", it.scope.asJson())
                put("limit", it.limit.asJson())
            }
        },
        decode = {
            val value = it.objectWithFields("scope", "limit")
            DiagnosticCheckRequest(value.protocolText("scope"), value.protocolCount("limit"))
        },
    )
    val diagnosticCheckResult = textListResultSerializer(
        "kast.diagnostic.check.result.v1",
        "diagnostics",
        DiagnosticCheckResult::diagnostics,
        ::DiagnosticCheckResult,
    )
    val diagnosticCheckQualification =
        canonicalEnumSerializer<DiagnosticCheckQualification>("kast.diagnostic.check.qualification.v1")
    val diagnosticCheckRejection =
        canonicalEnumSerializer<DiagnosticCheckRejection>("kast.diagnostic.check.rejection.v1")
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

private fun <Result : OperationResult> textListResultSerializer(
    serialName: String,
    field: String,
    extract: (Result) -> BoundedProtocolList<ProtocolText>,
    construct: (BoundedProtocolList<ProtocolText>) -> Result,
) = jsonContractSerializer(
    serialName,
    encode = { value -> JsonObject(mapOf(field to extract(value).asJson())) },
    decode = { element -> construct(element.objectWithFields(field).protocolTextList(field)) },
)

private inline fun <reified Value : Enum<Value>> enumValue(
    values: JsonObject,
    field: String,
): Value = try {
    enumValueOf(values.getValue(field).stringValue().uppercase())
} catch (_: IllegalArgumentException) {
    throw kotlinx.serialization.SerializationException("Invalid $field")
}

private fun SymbolDiscoverLimitation.wireName(): String =
    name.lowercase().replace('_', '-')

private fun symbolDiscoverLimitation(wireName: String): SymbolDiscoverLimitation = try {
    enumValueOf(wireName.replace('-', '_').uppercase())
} catch (_: IllegalArgumentException) {
    throw SerializationException("Invalid limitations")
}
