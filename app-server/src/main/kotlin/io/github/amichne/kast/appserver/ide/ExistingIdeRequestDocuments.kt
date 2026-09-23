package io.github.amichne.kast.appserver.ide

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val controlJson = Json { encodeDefaults = true }

@Serializable private data class DescribeRequest(val root: String, val type: String = "DESCRIBE")

@Serializable
private data class ClassLookupRequest(val root: String, val name: String, val type: String = "CLASS_LOOKUP")

@Serializable
private data class SupertypeRequest(val root: String, val qualifiedName: String, val type: String = "DIRECT_SUPERTYPE")

@Serializable private data class PlanRequest(val root: String, val document: String, val type: String = "CHANGE_PLAN")

@Serializable
private data class ApprovalPreparationRequest(
    val root: String,
    val document: String,
    val type: String = "CHANGE_APPROVAL_PREPARE",
)

@Serializable
private data class ApprovalPreparationDocument(val operation: HostedMutationOperation, val planIdentity: String)

@Serializable
private data class MutationRequest(
    val root: String,
    val type: HostedMutationOperation,
    val document: String,
    val approval: String,
)

@Serializable private data class ReadRequest(val root: String, val type: ExistingIdeReadOperation, val document: String)

fun ExistingIdeOperation.encodeControlRequest(root: CanonicalRoot): ByteArray {
    val path = root.path.toString()
    val encoded =
        when (this) {
            ExistingIdeOperation.Status -> controlJson.encodeToString(DescribeRequest(path))
            is ExistingIdeOperation.Classes -> controlJson.encodeToString(ClassLookupRequest(path, name.value))
            is ExistingIdeOperation.Supertype -> controlJson.encodeToString(SupertypeRequest(path, name.value))
            is ExistingIdeOperation.Plan -> controlJson.encodeToString(PlanRequest(path, request.document))
            is ExistingIdeOperation.ApprovalPreparation ->
                controlJson.encodeToString(
                    ApprovalPreparationRequest(
                        path,
                        controlJson.encodeToString(ApprovalPreparationDocument(kind, identity.value)),
                    )
                )
            is ExistingIdeOperation.ApprovedMutation ->
                controlJson.encodeToString(MutationRequest(path, kind, request.document, assertion.value))
            is ExistingIdeOperation.Read -> controlJson.encodeToString(ReadRequest(path, kind, request.document))
        }
    return encoded.toByteArray(Charsets.UTF_8)
}
