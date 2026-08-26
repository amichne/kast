package support.architecture

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class IdeReadFirewallReportFailure {
    MALFORMED_DOCUMENT,
    SCHEMA_VERSION_MISMATCH,
    TASK_ID_MISMATCH,
    ROLE_MISMATCH,
    STAGE_MISMATCH,
    MODULE_POLICIES_MISMATCH,
    FORBIDDEN_AUTHORITIES_MISMATCH,
    POLICY_REJECTED,
    FIREWALL_REJECTED,
}

internal sealed interface IdeReadFirewallReportResult {
    data class Complete(val proof: IdeReadFirewallProof) : IdeReadFirewallReportResult
    data class Rejected(val failure: IdeReadFirewallReportFailure) : IdeReadFirewallReportResult
}

@Serializable
private data class IdeReadModulePolicyDocument(
    val module: String,
    val lifecycle: ModuleLifecycle,
    val allowedDependencies: List<String>,
    val allowedEffects: List<String>,
)

@Serializable
private data class IdeReadForbiddenAuthorityDocument(
    val authority: String,
    val effects: List<String>,
)

@Serializable
private data class IdeReadFirewallReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val role: String,
    val stage: IdeReadFirewallStage,
    val modulePolicies: List<IdeReadModulePolicyDocument>,
    val forbiddenAuthorities: List<IdeReadForbiddenAuthorityDocument>,
)

private val ideReadFirewallJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    prettyPrint = true
}

/**
 * Proof transition: `IdeReadFirewallProof -> String`.
 * Preserves the materialization stage, exact three module policies, and all finite forbidden
 * authorities in a generated, closed JSON document. Raw JSON leaves only at the Gradle report
 * boundary.
 */
internal fun encodeIdeReadFirewallReport(proof: IdeReadFirewallProof): String =
    ideReadFirewallJson.encodeToString(IdeReadFirewallReportDocument.serializer(), proof.document()) +
        "\n"

/**
 * Proof transition: report JSON `String -> IdeReadFirewallReportResult`.
 * Establishes exact schema, task, role, materialization stage, module policies, and authority
 * classifications against an independently derived canonical firewall. Expected malformed or
 * mismatched evidence is finite [IdeReadFirewallReportFailure]; raw JSON remains at this boundary.
 */
internal fun decodeIdeReadFirewallReport(raw: String): IdeReadFirewallReportResult {
    val document = try {
        ideReadFirewallJson.decodeFromString(IdeReadFirewallReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return IdeReadFirewallReportResult.Rejected(
            IdeReadFirewallReportFailure.MALFORMED_DOCUMENT,
        )
    }
    val proof = when (val policy = KastArchitecturePolicy.validate()) {
        is ArchitecturePolicyValidation.Invalid -> return IdeReadFirewallReportResult.Rejected(
            IdeReadFirewallReportFailure.POLICY_REJECTED,
        )
        is ArchitecturePolicyValidation.Valid -> when (val result = IdeReadFirewall.derive(
            policy.architecture,
        )) {
            is IdeReadFirewallResult.Complete -> result.proof
            is IdeReadFirewallResult.Rejected -> return IdeReadFirewallReportResult.Rejected(
                IdeReadFirewallReportFailure.FIREWALL_REJECTED,
            )
        }
    }
    val expected = proof.document()
    val failure = when {
        document.schemaVersion != expected.schemaVersion ->
            IdeReadFirewallReportFailure.SCHEMA_VERSION_MISMATCH
        document.taskId != expected.taskId -> IdeReadFirewallReportFailure.TASK_ID_MISMATCH
        document.role != expected.role -> IdeReadFirewallReportFailure.ROLE_MISMATCH
        document.stage != expected.stage -> IdeReadFirewallReportFailure.STAGE_MISMATCH
        document.modulePolicies != expected.modulePolicies ->
            IdeReadFirewallReportFailure.MODULE_POLICIES_MISMATCH
        document.forbiddenAuthorities != expected.forbiddenAuthorities ->
            IdeReadFirewallReportFailure.FORBIDDEN_AUTHORITIES_MISMATCH
        else -> return IdeReadFirewallReportResult.Complete(proof)
    }
    return IdeReadFirewallReportResult.Rejected(failure)
}

private fun IdeReadFirewallProof.document() = IdeReadFirewallReportDocument(
    schemaVersion = 2,
    taskId = "KVP-009",
    role = ModuleRole.IDE_READ_ONLY.name,
    stage = stage,
    modulePolicies = modules.map { module ->
        IdeReadModulePolicyDocument(
            module = module.id.projectPath,
            lifecycle = module.lifecycle,
            allowedDependencies = module.allowedProjectDependencies.map { it.projectPath }.sorted(),
            allowedEffects = module.allowedEffects.map(Enum<*>::name).sorted(),
        )
    }.sortedBy { it.module },
    forbiddenAuthorities = forbiddenAuthorities.entries.map { (authority, effects) ->
        IdeReadForbiddenAuthorityDocument(
            authority = authority.name,
            effects = effects.map(Enum<*>::name).sorted(),
        )
    }.sortedBy { it.authority },
)
