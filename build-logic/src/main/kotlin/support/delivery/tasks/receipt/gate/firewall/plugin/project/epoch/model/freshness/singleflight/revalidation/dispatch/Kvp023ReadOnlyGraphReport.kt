package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp023ReadOnlyGraphDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authorities: List<Kvp023ReportAuthority>,
    val publicInterface: Kvp023PublicInterface,
    val operationBindings: List<Kvp023OperationBindingDocument>,
    val unsupportedOperations: List<Kvp023UnsupportedOperationDocument>,
    val projectDependencies: List<Kvp023ProjectDependency>,
    val forbiddenWork: List<Kvp023ForbiddenWorkDocument>,
    val predecessorReceipts: List<Kvp023PredecessorDocument>,
)

@Serializable
private data class Kvp023OperationBindingDocument(
    val operation: Kvp023Operation,
    val port: Kvp023OperationPort,
    val effect: Kvp023OperationEffect,
    val cost: Kvp023OperationCost,
)

@Serializable
private data class Kvp023UnsupportedOperationDocument(
    val operation: Kvp023UnsupportedOperation,
    val decision: Kvp023UnsupportedOperationDecision,
)

@Serializable
private data class Kvp023ForbiddenWorkDocument(
    val kind: Kvp023ForbiddenWork,
    val observedCount: Int,
)

@Serializable private enum class Kvp023ReportAuthority { OPERATION_REGISTRY, READ_RUNTIME }
@Serializable private enum class Kvp023PublicInterface { IdeReadRuntimeDispatch }
@Serializable private enum class Kvp023Operation {
    WORKSPACE_INSPECT,
    SYMBOL_DISCOVER,
    SYMBOL_RESOLVE,
    SYMBOL_DESCRIBE,
    RELATION_READ,
}

@Serializable private enum class Kvp023OperationEffect { NONE, INTELLIJ_READ }
@Serializable private enum class Kvp023OperationCost { HOST_NEUTRAL, BOUNDED_READ }
@Serializable private enum class Kvp023OperationPort {
    WorkspaceInspectReadPort,
    SymbolDiscoverReadPort,
    SymbolResolveReadPort,
    SymbolDescribeReadPort,
}

@Serializable private enum class Kvp023UnsupportedOperation {
    TOPOLOGY_BUILD,
    RELATION_READ,
    TRAVERSAL_RUN,
    DIAGNOSTIC_CHECK,
    CHANGE_PLAN,
    CHANGE_APPLY,
    CHANGE_VERIFY,
    CHANGE_RECOVER,
}

@Serializable private enum class Kvp023UnsupportedOperationDecision { REJECT_BEFORE_DISPATCH }
@Serializable private enum class Kvp023ProjectDependency {
    PROTOCOL_WIRE,
    WORKSPACE_CONTRACT,
    WORKSPACE_INTELLIJ_READ,
    RUNTIME_COMPOSITION,
}

@Serializable private enum class Kvp023ForbiddenWork {
    RUNTIME_COMPOSITION_DEPENDENCY,
    PERSISTENCE_CHANGE_OR_TOPOLOGY_DEPENDENCY,
    SERVICE_LOCATOR,
    OPERATION_OUTSIDE_CAPABILITY_SET,
}

internal enum class Kvp023ReadOnlyGraphReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    AUTHORITY_SET_MISMATCH,
    OPERATION_BINDING_SET_MISMATCH,
    UNSUPPORTED_OPERATION_SET_MISMATCH,
    PROJECT_DEPENDENCY_SET_MISMATCH,
    FORBIDDEN_WORK_MISMATCH,
    PREDECESSOR_SET_MISMATCH,
    PREDECESSOR_RECEIPT_REJECTED,
}

internal class AdmittedKvp023ReadOnlyGraphReport private constructor(
    val canonicalDocument: String,
    val authorities: String,
    val publicInterface: String,
    val operationBindings: String,
    val operationCount: Int,
    val unsupportedOperationCount: Int,
    val unsupportedOperationDecision: String,
    val projectDependencies: String,
    val projectDependencyCount: Int,
    val observedForbiddenWorkCount: Int,
    val predecessorCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp023ReportPredecessors) ->
         * Kvp023ReadOnlyGraphReportAdmission`.
         *
         * Establishes the exact ordered four-operation registry projection, its canonical
         * effect/cost classifications, the direct read-only project graph, zero forbidden work,
         * and the exact KVP-009/KVP-016/KVP-022 completion digests. Expected failures remain
         * closed [Kvp023ReadOnlyGraphReportFailure] data. Raw JSON is extracted only at Gradle
         * report and receipt boundaries.
         */
        fun admit(
            raw: String,
            predecessors: Kvp023ReportPredecessors,
        ): Kvp023ReadOnlyGraphReportAdmission {
            val document = try {
                KVP023_REPORT_JSON.decodeFromString(Kvp023ReadOnlyGraphDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return rejected(Kvp023ReadOnlyGraphReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return rejected(Kvp023ReadOnlyGraphReportFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP023_REPORT_SCHEMA_VERSION -> return rejected(
                    Kvp023ReadOnlyGraphReportFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-023" ||
                    document.publicInterface != Kvp023PublicInterface.IdeReadRuntimeDispatch ->
                    return rejected(Kvp023ReadOnlyGraphReportFailure.IDENTITY_MISMATCH)
                document.authorities != Kvp023ReportAuthority.entries -> return rejected(
                    Kvp023ReadOnlyGraphReportFailure.AUTHORITY_SET_MISMATCH,
                )
                document.operationBindings != canonicalKvp023OperationBindings() ->
                    return rejected(
                        Kvp023ReadOnlyGraphReportFailure.OPERATION_BINDING_SET_MISMATCH,
                    )
                document.unsupportedOperations != canonicalKvp023UnsupportedOperations() ->
                    return rejected(
                        Kvp023ReadOnlyGraphReportFailure.UNSUPPORTED_OPERATION_SET_MISMATCH,
                    )
                document.projectDependencies != canonicalKvp023ProjectDependencies() ->
                    return rejected(
                        Kvp023ReadOnlyGraphReportFailure.PROJECT_DEPENDENCY_SET_MISMATCH,
                    )
                document.forbiddenWork != canonicalKvp023ForbiddenWork() -> return rejected(
                    Kvp023ReadOnlyGraphReportFailure.FORBIDDEN_WORK_MISMATCH,
                )
                document.predecessorReceipts != predecessors.documents() -> return rejected(
                    Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_SET_MISMATCH,
                )
            }
            val canonical = encodeKvp023Report(document)
            if (raw != canonical) return rejected(
                Kvp023ReadOnlyGraphReportFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp023ReadOnlyGraphReportAdmission.Admitted(
                AdmittedKvp023ReadOnlyGraphReport(
                    canonical,
                    document.authorities.joinToString(",") { it.name },
                    document.publicInterface.name,
                    document.operationBindings.joinToString(",") {
                        "${it.operation.name}:${it.port.name}:${it.effect.name}/${it.cost.name}"
                    },
                    document.operationBindings.size,
                    document.unsupportedOperations.size,
                    Kvp023UnsupportedOperationDecision.REJECT_BEFORE_DISPATCH.name,
                    document.projectDependencies.joinToString(",") { it.name },
                    document.projectDependencies.size,
                    document.forbiddenWork.sumOf(Kvp023ForbiddenWorkDocument::observedCount),
                    document.predecessorReceipts.size,
                ),
            )
        }
    }
}

internal sealed interface Kvp023ReadOnlyGraphReportAdmission {
    data class Admitted(val report: AdmittedKvp023ReadOnlyGraphReport) :
        Kvp023ReadOnlyGraphReportAdmission

    data class Rejected(val failure: Kvp023ReadOnlyGraphReportFailure) :
        Kvp023ReadOnlyGraphReportAdmission
}

internal fun canonicalKvp023ReadOnlyGraphReport(
    predecessors: Kvp023ReportPredecessors,
): String = encodeKvp023Report(
    Kvp023ReadOnlyGraphDocument(
        schemaVersion = KVP023_REPORT_SCHEMA_VERSION,
        taskId = "KVP-023",
        authorities = Kvp023ReportAuthority.entries,
        publicInterface = Kvp023PublicInterface.IdeReadRuntimeDispatch,
        operationBindings = canonicalKvp023OperationBindings(),
        unsupportedOperations = canonicalKvp023UnsupportedOperations(),
        projectDependencies = canonicalKvp023ProjectDependencies(),
        forbiddenWork = canonicalKvp023ForbiddenWork(),
        predecessorReceipts = predecessors.documents(),
    ),
)

private fun canonicalKvp023OperationBindings() = listOf(
    Kvp023OperationBindingDocument(
        Kvp023Operation.WORKSPACE_INSPECT,
        Kvp023OperationPort.WorkspaceInspectReadPort,
        Kvp023OperationEffect.NONE,
        Kvp023OperationCost.HOST_NEUTRAL,
    ),
    Kvp023OperationBindingDocument(
        Kvp023Operation.SYMBOL_DISCOVER,
        Kvp023OperationPort.SymbolDiscoverReadPort,
        Kvp023OperationEffect.INTELLIJ_READ,
        Kvp023OperationCost.BOUNDED_READ,
    ),
    Kvp023OperationBindingDocument(
        Kvp023Operation.SYMBOL_RESOLVE,
        Kvp023OperationPort.SymbolResolveReadPort,
        Kvp023OperationEffect.INTELLIJ_READ,
        Kvp023OperationCost.BOUNDED_READ,
    ),
    Kvp023OperationBindingDocument(
        Kvp023Operation.SYMBOL_DESCRIBE,
        Kvp023OperationPort.SymbolDescribeReadPort,
        Kvp023OperationEffect.INTELLIJ_READ,
        Kvp023OperationCost.BOUNDED_READ,
    ),
)

private fun canonicalKvp023UnsupportedOperations() = Kvp023UnsupportedOperation.entries.map {
    Kvp023UnsupportedOperationDocument(
        it,
        Kvp023UnsupportedOperationDecision.REJECT_BEFORE_DISPATCH,
    )
}

private fun canonicalKvp023ProjectDependencies() = listOf(
    Kvp023ProjectDependency.PROTOCOL_WIRE,
    Kvp023ProjectDependency.WORKSPACE_CONTRACT,
    Kvp023ProjectDependency.WORKSPACE_INTELLIJ_READ,
)

private fun canonicalKvp023ForbiddenWork() = Kvp023ForbiddenWork.entries.map {
    Kvp023ForbiddenWorkDocument(it, 0)
}

private fun encodeKvp023Report(document: Kvp023ReadOnlyGraphDocument) =
    KVP023_REPORT_JSON.encodeToString(Kvp023ReadOnlyGraphDocument.serializer(), document) + "\n"

private fun rejected(failure: Kvp023ReadOnlyGraphReportFailure) =
    Kvp023ReadOnlyGraphReportAdmission.Rejected(failure)

private const val KVP023_REPORT_SCHEMA_VERSION = 1
private val KVP023_REPORT_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
