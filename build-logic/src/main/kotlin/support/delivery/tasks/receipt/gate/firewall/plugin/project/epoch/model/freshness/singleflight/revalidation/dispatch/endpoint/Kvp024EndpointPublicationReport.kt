package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp024EndpointPublicationDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authority: Kvp024ReportAuthority,
    val publicInterface: Kvp024PublicInterface,
    val serviceScope: Kvp024ServiceScope,
    val transport: Kvp024Transport,
    val descriptorSchema: Kvp024DescriptorSchema,
    val framing: Kvp024Framing,
    val preparationInputs: List<Kvp024PreparationInput>,
    val serviceStates: List<Kvp024ServiceState>,
    val transitions: List<Kvp024TransitionDocument>,
    val descriptorBindings: List<Kvp024DescriptorBindingDocument>,
    val descriptorRules: List<Kvp024DescriptorRule>,
    val endpointLimitPerProject: Int,
    val socketBindLimitPerEndpoint: Int,
    val descriptorPublicationLimitPerEndpoint: Int,
    val rejectionCases: List<Kvp024RejectionCaseDocument>,
    val rollbackArtifacts: List<Kvp024RollbackArtifact>,
    val forbiddenWork: List<Kvp024ForbiddenWorkDocument>,
    val predecessorReceipts: List<Kvp024PredecessorDocument>,
)

@Serializable
private data class Kvp024TransitionDocument(
    val from: Kvp024ServiceState,
    val to: Kvp024ServiceState,
    val effect: Kvp024TransitionEffect,
)

@Serializable
private data class Kvp024RejectionCaseDocument(
    val case: Kvp024RejectionCase,
    val decision: Kvp024RejectionDecision,
)

@Serializable
private data class Kvp024ForbiddenWorkDocument(
    val kind: Kvp024ForbiddenWork,
    val observedCount: Int,
)

@Serializable private enum class Kvp024ReportAuthority { IDE_ENDPOINT }
@Serializable private enum class Kvp024PublicInterface { ReadyIdeEndpoint }
@Serializable private enum class Kvp024ServiceScope { PROJECT }
@Serializable private enum class Kvp024Transport { UNIX_DOMAIN_SOCKET }
@Serializable private enum class Kvp024DescriptorSchema {
    @SerialName("kast.ide.endpoint.v2")
    V2,
}

@Serializable private enum class Kvp024Framing {
    @SerialName("length-prefixed-json-v1")
    LENGTH_PREFIXED_JSON_V1,
}

@Serializable private enum class Kvp024PreparationInput {
    ADMITTED_IDE_PROJECT_EXACT_ROOT,
    IDE_READ_RUNTIME_DISPATCH,
    ENDPOINT_V2_DESCRIPTOR_INPUTS,
}

@Serializable private enum class Kvp024ServiceState { PREPARED, SOCKET_BOUND, READY }
@Serializable private enum class Kvp024TransitionEffect {
    UDS_BIND,
    ENDPOINT_DESCRIPTOR_WRITE,
}

@Serializable private enum class Kvp024DescriptorRule {
    SOCKET_SUFFIX_ENDPOINT_JSON,
    SAME_PARENT_TEMPORARY,
    ATOMIC_MOVE_REQUIRED,
    NO_MOVE_FALLBACK,
}

@Serializable private enum class Kvp024RejectionCase {
    WRONG_ROOT,
    PARTIAL_RUNTIME,
    DUPLICATE_ENDPOINT,
    OCCUPIED_NON_SOCKET_PATH,
    REACHABLE_OR_OCCUPIED_SOCKET,
    SOCKET_BIND_FAILED,
    DESCRIPTOR_PUBLICATION_FAILED,
}

@Serializable private enum class Kvp024RejectionDecision {
    REJECT_BEFORE_BIND,
    PRESERVE_AND_REJECT,
    REJECT_WITHOUT_PUBLISH,
    RETIRE_OWNED_AND_REJECT,
    REJECT_BEFORE_SECOND_BIND,
}
@Serializable private enum class Kvp024RollbackArtifact {
    OWNED_BOUND_SOCKET,
    OWNED_TEMPORARY_DESCRIPTOR,
}

@Serializable private enum class Kvp024ForbiddenWork {
    PUBLISH_BEFORE_RUNTIME_CONSTRUCTION,
    DELETE_UNOWNED_PATH,
    BIND_WRONG_ROOT,
    MULTIPLE_ENDPOINTS_PER_PROJECT,
    NON_ATOMIC_DESCRIPTOR_MOVE,
}

internal enum class Kvp024EndpointPublicationReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    PREPARATION_INPUT_SET_MISMATCH,
    SERVICE_STATE_SET_MISMATCH,
    TRANSITION_SET_MISMATCH,
    DESCRIPTOR_BINDING_SET_MISMATCH,
    DESCRIPTOR_RULE_SET_MISMATCH,
    PUBLICATION_LIMITS_MISMATCH,
    REJECTION_CASE_SET_MISMATCH,
    ROLLBACK_ARTIFACT_SET_MISMATCH,
    FORBIDDEN_WORK_MISMATCH,
    PREDECESSOR_SET_MISMATCH,
    PREDECESSOR_RECEIPT_REJECTED,
}

internal class AdmittedKvp024EndpointPublicationReport private constructor(
    val canonicalDocument: String,
    val authority: String,
    val publicInterface: String,
    val serviceScope: String,
    val transport: String,
    val descriptorSchema: String,
    val framing: String,
    val preparationInputs: String,
    val serviceStates: String,
    val transitions: String,
    val descriptorBindings: String,
    val descriptorRules: String,
    val endpointLimitPerProject: Int,
    val socketBindLimitPerEndpoint: Int,
    val descriptorPublicationLimitPerEndpoint: Int,
    val rejectionCases: String,
    val rejectionCaseCount: Int,
    val rollbackArtifacts: String,
    val observedForbiddenWorkCount: Int,
    val predecessorCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp024ReportPredecessors) ->
         * Kvp024EndpointPublicationReportAdmission`.
         *
         * Establishes exact-root prepared runtime input, the Unpublished-to-Bound-to-Ready state
         * order, descriptor-v2 atomic publication without fallback, one endpoint per Project,
         * finite fail-closed rejection, owned-artifact rollback only, zero forbidden work, and
         * exact direct KVP-013/KVP-023 digests. Expected failures remain closed
         * [Kvp024EndpointPublicationReportFailure] data. Raw JSON is extracted only at Gradle
         * report and receipt boundaries.
         */
        fun admit(
            raw: String,
            predecessors: Kvp024ReportPredecessors,
        ): Kvp024EndpointPublicationReportAdmission {
            val document = try {
                KVP024_REPORT_JSON.decodeFromString(
                    Kvp024EndpointPublicationDocument.serializer(),
                    raw,
                )
            } catch (_: SerializationException) {
                return rejected(Kvp024EndpointPublicationReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return rejected(Kvp024EndpointPublicationReportFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP024_REPORT_SCHEMA_VERSION -> return rejected(
                    Kvp024EndpointPublicationReportFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-024" ||
                    document.authority != Kvp024ReportAuthority.IDE_ENDPOINT ||
                    document.publicInterface != Kvp024PublicInterface.ReadyIdeEndpoint ||
                    document.serviceScope != Kvp024ServiceScope.PROJECT ||
                    document.transport != Kvp024Transport.UNIX_DOMAIN_SOCKET ||
                    document.descriptorSchema != Kvp024DescriptorSchema.V2 ||
                    document.framing != Kvp024Framing.LENGTH_PREFIXED_JSON_V1 ->
                    return rejected(Kvp024EndpointPublicationReportFailure.IDENTITY_MISMATCH)
                document.preparationInputs != Kvp024PreparationInput.entries -> return rejected(
                    Kvp024EndpointPublicationReportFailure.PREPARATION_INPUT_SET_MISMATCH,
                )
                document.serviceStates != Kvp024ServiceState.entries -> return rejected(
                    Kvp024EndpointPublicationReportFailure.SERVICE_STATE_SET_MISMATCH,
                )
                document.transitions != canonicalKvp024Transitions() -> return rejected(
                    Kvp024EndpointPublicationReportFailure.TRANSITION_SET_MISMATCH,
                )
                document.descriptorBindings != canonicalKvp024DescriptorBindings() ->
                    return rejected(
                        Kvp024EndpointPublicationReportFailure.DESCRIPTOR_BINDING_SET_MISMATCH,
                    )
                document.descriptorRules != Kvp024DescriptorRule.entries -> return rejected(
                    Kvp024EndpointPublicationReportFailure.DESCRIPTOR_RULE_SET_MISMATCH,
                )
                document.endpointLimitPerProject != 1 ||
                    document.socketBindLimitPerEndpoint != 1 ||
                    document.descriptorPublicationLimitPerEndpoint != 1 -> return rejected(
                        Kvp024EndpointPublicationReportFailure.PUBLICATION_LIMITS_MISMATCH,
                    )
                document.rejectionCases != canonicalKvp024RejectionCases() -> return rejected(
                    Kvp024EndpointPublicationReportFailure.REJECTION_CASE_SET_MISMATCH,
                )
                document.rollbackArtifacts != Kvp024RollbackArtifact.entries -> return rejected(
                    Kvp024EndpointPublicationReportFailure.ROLLBACK_ARTIFACT_SET_MISMATCH,
                )
                document.forbiddenWork != canonicalKvp024ForbiddenWork() -> return rejected(
                    Kvp024EndpointPublicationReportFailure.FORBIDDEN_WORK_MISMATCH,
                )
                document.predecessorReceipts != predecessors.documents() -> return rejected(
                    Kvp024EndpointPublicationReportFailure.PREDECESSOR_SET_MISMATCH,
                )
            }
            val canonical = encodeKvp024Report(document)
            if (raw != canonical) return rejected(
                Kvp024EndpointPublicationReportFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp024EndpointPublicationReportAdmission.Admitted(
                AdmittedKvp024EndpointPublicationReport(
                    canonical,
                    document.authority.name,
                    document.publicInterface.name,
                    document.serviceScope.name,
                    document.transport.name,
                    "kast.ide.endpoint.v2",
                    "length-prefixed-json-v1",
                    document.preparationInputs.joinToString(",") { it.name },
                    document.serviceStates.joinToString(",") { it.name },
                    document.transitions.joinToString(",") {
                        "${it.from.name}->${it.to.name}:${it.effect.name}"
                    },
                    document.descriptorBindings.joinToString(",") {
                        "${it.field.name}:${it.source.name}"
                    },
                    document.descriptorRules.joinToString(",") { it.name },
                    document.endpointLimitPerProject,
                    document.socketBindLimitPerEndpoint,
                    document.descriptorPublicationLimitPerEndpoint,
                    document.rejectionCases.joinToString(",") {
                        "${it.case.name}:${it.decision.name}"
                    },
                    document.rejectionCases.size,
                    document.rollbackArtifacts.joinToString(",") { it.name },
                    document.forbiddenWork.sumOf(Kvp024ForbiddenWorkDocument::observedCount),
                    document.predecessorReceipts.size,
                ),
            )
        }
    }
}

internal sealed interface Kvp024EndpointPublicationReportAdmission {
    data class Admitted(val report: AdmittedKvp024EndpointPublicationReport) :
        Kvp024EndpointPublicationReportAdmission
    data class Rejected(val failure: Kvp024EndpointPublicationReportFailure) :
        Kvp024EndpointPublicationReportAdmission
}

internal fun canonicalKvp024EndpointPublicationReport(
    predecessors: Kvp024ReportPredecessors,
): String = encodeKvp024Report(
    Kvp024EndpointPublicationDocument(
        schemaVersion = KVP024_REPORT_SCHEMA_VERSION,
        taskId = "KVP-024",
        authority = Kvp024ReportAuthority.IDE_ENDPOINT,
        publicInterface = Kvp024PublicInterface.ReadyIdeEndpoint,
        serviceScope = Kvp024ServiceScope.PROJECT,
        transport = Kvp024Transport.UNIX_DOMAIN_SOCKET,
        descriptorSchema = Kvp024DescriptorSchema.V2,
        framing = Kvp024Framing.LENGTH_PREFIXED_JSON_V1,
        preparationInputs = Kvp024PreparationInput.entries,
        serviceStates = Kvp024ServiceState.entries,
        transitions = canonicalKvp024Transitions(),
        descriptorBindings = canonicalKvp024DescriptorBindings(),
        descriptorRules = Kvp024DescriptorRule.entries,
        endpointLimitPerProject = 1,
        socketBindLimitPerEndpoint = 1,
        descriptorPublicationLimitPerEndpoint = 1,
        rejectionCases = canonicalKvp024RejectionCases(),
        rollbackArtifacts = Kvp024RollbackArtifact.entries,
        forbiddenWork = canonicalKvp024ForbiddenWork(),
        predecessorReceipts = predecessors.documents(),
    ),
)

private fun canonicalKvp024Transitions() = listOf(
    Kvp024TransitionDocument(
        Kvp024ServiceState.PREPARED,
        Kvp024ServiceState.SOCKET_BOUND,
        Kvp024TransitionEffect.UDS_BIND,
    ),
    Kvp024TransitionDocument(
        Kvp024ServiceState.SOCKET_BOUND,
        Kvp024ServiceState.READY,
        Kvp024TransitionEffect.ENDPOINT_DESCRIPTOR_WRITE,
    ),
)

private fun canonicalKvp024RejectionCases() = Kvp024RejectionCase.entries.map {
    Kvp024RejectionCaseDocument(it, it.decision())
}

private fun Kvp024RejectionCase.decision() = when (this) {
    Kvp024RejectionCase.WRONG_ROOT,
    Kvp024RejectionCase.PARTIAL_RUNTIME,
    -> Kvp024RejectionDecision.REJECT_BEFORE_BIND
    Kvp024RejectionCase.DUPLICATE_ENDPOINT ->
        Kvp024RejectionDecision.REJECT_BEFORE_SECOND_BIND
    Kvp024RejectionCase.OCCUPIED_NON_SOCKET_PATH,
    Kvp024RejectionCase.REACHABLE_OR_OCCUPIED_SOCKET,
    -> Kvp024RejectionDecision.PRESERVE_AND_REJECT
    Kvp024RejectionCase.SOCKET_BIND_FAILED ->
        Kvp024RejectionDecision.REJECT_WITHOUT_PUBLISH
    Kvp024RejectionCase.DESCRIPTOR_PUBLICATION_FAILED ->
        Kvp024RejectionDecision.RETIRE_OWNED_AND_REJECT
}

private fun canonicalKvp024ForbiddenWork() = Kvp024ForbiddenWork.entries.map {
    Kvp024ForbiddenWorkDocument(it, 0)
}

private fun encodeKvp024Report(document: Kvp024EndpointPublicationDocument) =
    KVP024_REPORT_JSON.encodeToString(Kvp024EndpointPublicationDocument.serializer(), document) +
        "\n"

private fun rejected(failure: Kvp024EndpointPublicationReportFailure) =
    Kvp024EndpointPublicationReportAdmission.Rejected(failure)

private const val KVP024_REPORT_SCHEMA_VERSION = 1
private val KVP024_REPORT_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
