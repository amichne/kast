package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliOutcomeProjector
import io.github.amichne.kast.cli.ProjectedCliOutcome
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val workspaceInspectCliProjector = CliOutcomeProjector<
    WorkspaceInspectResult,
    WorkspaceInspectQualification,
    WorkspaceInspectRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.WORKSPACE_INSPECT, outcome) { result ->
        fields(
            "canonicalRoot" to JsonPrimitive(result.canonicalRoot.value),
            "state" to JsonPrimitive(result.state.documentValue()),
        )
    }
}

internal val topologyBuildCliProjector = CliOutcomeProjector<
    TopologyBuildResult,
    TopologyBuildQualification,
    TopologyBuildRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.TOPOLOGY_BUILD, outcome) { result ->
        fields(
            "snapshotStatus" to JsonPrimitive(result.status.documentValue()),
            "digest" to JsonPrimitive(result.digest.value),
        )
    }
}

internal val symbolDiscoverCliProjector = CliOutcomeProjector<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.SYMBOL_DISCOVER, outcome) { result ->
        fields("items" to JsonArray(result.items.values.map { it.cliJson() }))
    }
}

internal val symbolResolveCliProjector = CliOutcomeProjector<
    SymbolResolveResult,
    SymbolResolveQualification,
    SymbolResolveRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.SYMBOL_RESOLVE, outcome) { result ->
        fields("exactSelector" to JsonPrimitive(result.exactSelector.value))
    }
}

internal val symbolDescribeCliProjector = CliOutcomeProjector<
    SymbolDescribeResult,
    SymbolDescribeQualification,
    SymbolDescribeRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.SYMBOL_DESCRIBE, outcome) { result ->
        fields("symbol" to result.symbol.cliJson())
    }
}

internal val relationReadCliProjector = CliOutcomeProjector<
    RelationReadResult,
    RelationReadQualification,
    RelationReadRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.RELATION_READ, outcome) { result ->
        fields("targets" to JsonArray(result.targets.values.map { it.cliJson() }))
    }
}

internal val traversalRunCliProjector = CliOutcomeProjector<
    TraversalRunResult,
    TraversalRunQualification,
    TraversalRunRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.TRAVERSAL_RUN, outcome) { result ->
        fields("reached" to JsonArray(result.reached.values.map { it.cliJson() }))
    }
}

internal val diagnosticCheckCliProjector = CliOutcomeProjector<
    DiagnosticCheckResult,
    DiagnosticCheckQualification,
    DiagnosticCheckRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.DIAGNOSTIC_CHECK, outcome) { result ->
        fields("diagnostics" to result.diagnostics.values.jsonTexts())
    }
}

internal val changePlanCliProjector = CliOutcomeProjector<
    ChangePlanResult,
    ChangePlanQualification,
    ChangePlanRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.CHANGE_PLAN, outcome) { result ->
        fields("planIdentity" to JsonPrimitive(result.planIdentity.value))
    }
}

internal val changeApplyCliProjector = CliOutcomeProjector<
    ChangeApplyResult,
    ChangeApplyQualification,
    ChangeApplyRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.CHANGE_APPLY, outcome) { result ->
        fields("applicationIdentity" to JsonPrimitive(result.applicationIdentity.value))
    }
}

internal val changeVerifyCliProjector = CliOutcomeProjector<
    ChangeVerifyResult,
    ChangeVerifyQualification,
    ChangeVerifyRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.CHANGE_VERIFY, outcome) { result ->
        fields("receiptIdentity" to JsonPrimitive(result.receiptIdentity.value))
    }
}

internal val changeRecoverCliProjector = CliOutcomeProjector<
    ChangeRecoverResult,
    ChangeRecoverQualification,
    ChangeRecoverRejection,
    > { outcome ->
    projectOutcome(CanonicalOperation.CHANGE_RECOVER, outcome) { result ->
        fields("state" to JsonPrimitive(result.state.documentValue()))
    }
}

private fun <
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > projectOutcome(
    operation: CanonicalOperation,
    outcome: OperationOutcome<Result, Qualification, Rejection>,
    payload: (Result) -> JsonObject,
): ProjectedCliOutcome {
    val status = when (outcome) {
        is OperationOutcome.Complete -> "complete"
        is OperationOutcome.Qualified -> "qualified"
        is OperationOutcome.Rejected -> "rejected"
    }
    val document = CliJsonDocument.from(
        buildJsonObject {
            put("operation", operation.id.value)
            put("status", status)
            when (outcome) {
                is OperationOutcome.Complete -> payload(outcome.evidence.payload).copyInto(this)
                is OperationOutcome.Qualified -> {
                    payload(outcome.evidence.payload).copyInto(this)
                    put("qualification", outcome.qualification.toString().documentValue())
                }
                is OperationOutcome.Rejected -> put(
                    "reason",
                    outcome.reason.toString().documentValue(),
                )
            }
        },
    )
    return when (outcome) {
        is OperationOutcome.Complete -> ProjectedCliOutcome.Complete(document)
        is OperationOutcome.Qualified -> ProjectedCliOutcome.Qualified(document)
        is OperationOutcome.Rejected -> ProjectedCliOutcome.Rejected(document)
    }
}

private fun fields(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(values.toMap())

private fun List<io.github.amichne.kast.protocol.contract.ProtocolText>.jsonTexts(): JsonArray =
    JsonArray(map { JsonPrimitive(it.value) })

private fun SymbolDiscoveryDocument.cliJson(): JsonObject = when (this) {
    is SymbolDiscoveryDocument.File -> buildJsonObject {
        put("type", "file")
        put("name", name.value)
        put("file", file.value)
    }
    is SymbolDiscoveryDocument.Declaration -> buildJsonObject {
        put("type", "declaration")
        put("candidateSelector", candidateSelector.value)
        put("kind", kind.documentValue())
        put("name", name.value)
        put("file", file.value)
        put("offset", offset.value)
    }
    is SymbolDiscoveryDocument.TextMatch -> buildJsonObject {
        put("type", "text-match")
        put("query", query.value)
        put("file", file.value)
        put("range", range.cliJson())
    }
}

private fun SymbolDocument.cliJson(): JsonObject = buildJsonObject {
    put("selector", selector.value)
    put("kind", kind.documentValue())
    put("name", name.value)
    put(
        "qualifiedIdentity",
        when (val identity = qualifiedIdentity) {
            is SymbolQualifiedIdentityDocument.Available -> JsonPrimitive(identity.value.value)
            SymbolQualifiedIdentityDocument.Unavailable -> JsonNull
        },
    )
    put("file", file.value)
    put("range", range.cliJson())
}

private fun SourceRangeDocument.cliJson(): JsonObject = buildJsonObject {
    put("startInclusive", startInclusive.value)
    put("endExclusive", endExclusive.value)
}

private fun JsonObject.copyInto(target: kotlinx.serialization.json.JsonObjectBuilder) {
    forEach(target::put)
}

private fun Any.documentValue(): String = toString().lowercase().replace('_', '-')
