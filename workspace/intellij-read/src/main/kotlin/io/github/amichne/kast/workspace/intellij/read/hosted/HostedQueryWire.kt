package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.Gson
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Detached transport projection. Encoding may only run after the service has released its reads. */
object HostedQueryWire {
    fun encode(result: HostedIndexResult, limits: ReadLimits = ReadLimits.Default): String =
        when (result) {
            is HostedIndexResult.Rejected -> encode(HostedQueryResult.Rejected(result.failure, result.stage))
            is HostedIndexResult.Published ->
                Gson()
                    .toJson(
                        mapOf(
                            "schemaVersion" to 1,
                            "outcome" to "published",
                            "stage" to HostedQueryStage.RESULT_DETACHED.name,
                            "publication" to "request_local_same_source_epoch",
                            "workspaceRoot" to result.publication.model.canonicalRoot.value,
                            "host" to
                                mapOf(
                                    "ideBuild" to result.publication.model.compatibility.ideBuild.value,
                                    "kotlinBuild" to result.publication.model.compatibility.kotlinPluginBuild.value,
                                ),
                            "content" to "saved_committed_ide_vfs",
                            "scope" to "cached_gradle_source_folders",
                            "kind" to "classes",
                            "name" to result.publication.classes.lookup.name.value,
                            "indexAuthority" to "existing_ide_kotlin_stub_index",
                            "declarations" to result.publication.classes.declarations.map { it.document() },
                        )
                    )
                    .let { document ->
                        if (
                            document.toByteArray(Charsets.UTF_8).size <=
                                limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value
                        )
                            document
                        else
                            encode(
                                HostedQueryResult.Rejected(
                                    HostedQueryFailure.RESULT_LIMIT_EXCEEDED,
                                    HostedQueryStage.RESULT_DETACHED,
                                )
                            )
                    }
        }

    fun encode(result: HostedQueryResult): String =
        when (result) {
            is HostedQueryResult.Rejected ->
                hostedFailureJson.encodeToString(
                    HostedQueryRejectionDocument(
                        failure = result.failure.code(),
                        detail = result.failure.detail(),
                        stage = result.stage.name,
                    )
                )
            is HostedQueryResult.Published ->
                Gson()
                    .toJson(
                        mapOf(
                            "schemaVersion" to 1,
                            "outcome" to "published",
                            "stage" to HostedQueryStage.RESULT_DETACHED.name,
                            "publication" to "request_local_same_source_epoch",
                            "workspaceRoot" to result.publication.model.canonicalRoot.value,
                            "host" to
                                mapOf(
                                    "ideBuild" to result.publication.model.compatibility.ideBuild.value,
                                    "kotlinBuild" to result.publication.model.compatibility.kotlinPluginBuild.value,
                                ),
                            "content" to "saved_committed_ide_vfs",
                            "scope" to "cached_gradle_source_folders",
                            "kind" to "inheritors",
                            "supertype" to result.publication.relation.supertype.document(),
                            "inheritor" to result.publication.relation.inheritor.document(),
                        )
                    )
        }
}

@Serializable
internal data class HostedQueryRejectionDocument(
    val failure: String,
    /** The schema-defined diagnostic union is a string, typed object, or finite enum list. */
    val detail: JsonElement,
    val stage: String,
    val schemaVersion: Int = 1,
    val outcome: String = "rejected",
)

private fun HostedCompilerDeclaration.document(): Map<String, Any> {
    // Reuse the canonical wire proof, including its identity/signature agreement check.
    val qualified =
        when (val parsed = ProtocolText.parse(symbol.signature.qualifiedIdentity.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("Bounded compiler identity failed protocol projection")
        }
    val compiler =
        when (
            val projected = CompilerSymbolEvidenceDocument.fromSignature(CompilerSignatureDocument.ClassLike(qualified))
        ) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> error("Proven class signature failed protocol projection")
        }
    check(compiler.identity.value == symbol.compilerIdentity.value)
    return mapOf(
        "file" to symbol.file.stableValue,
        "compilerIdentity" to compiler.identity.value,
        "signature" to mapOf("kind" to "class_like", "qualifiedIdentity" to qualified.value),
        "canonicalSignature" to symbol.signature.canonicalEncoding().value,
        "documentStamp" to content.documentStamp.value,
        "vfsStamp" to content.vfsStamp.value,
        "module" to module.name.value,
        "gradleBuildRoot" to module.owner.buildRoot.value,
        "gradleProject" to module.owner.projectIdentity.value,
        "sourceRoot" to sourceRoot.location.value,
        "sourceKind" to sourceRoot.kind.name,
        "provenanceAuthority" to "cached_source_folder_flag",
    )
}

internal fun HostedQueryFailure.code(): String =
    when (this) {
        is HostedQueryFailure.Configuration -> "CONFIGURATION_REJECTED"
        HostedQueryFailure.RETIRED -> "RETIRED"
        HostedQueryFailure.WRONG_ENDPOINT -> "WRONG_ENDPOINT"
        HostedQueryFailure.WRONG_PROJECT -> "WRONG_PROJECT"
        HostedQueryFailure.BUSY -> "BUSY"
        HostedQueryFailure.STALE_REQUEST -> "STALE_REQUEST"
        HostedQueryFailure.INVALID_SELECTION -> "INVALID_SELECTION"
        HostedQueryFailure.DECLARATION_NOT_FOUND -> "DECLARATION_NOT_FOUND"
        HostedQueryFailure.AMBIGUOUS_DECLARATION -> "AMBIGUOUS_DECLARATION"
        HostedQueryFailure.DECLARATION_IDENTITY_MISMATCH -> "DECLARATION_IDENTITY_MISMATCH"
        HostedQueryFailure.PROJECT_UNAVAILABLE -> "PROJECT_UNAVAILABLE"
        HostedQueryFailure.INDEXING -> "INDEXING"
        HostedQueryFailure.WRONG_THREAD -> "WRONG_THREAD"
        HostedQueryFailure.DIRTY_DOCUMENTS -> "DIRTY_DOCUMENTS"
        HostedQueryFailure.UNCOMMITTED_DOCUMENTS -> "UNCOMMITTED_DOCUMENTS"
        HostedQueryFailure.CONTENT_MOVED -> "CONTENT_MOVED"
        HostedQueryFailure.MODEL_MOVED -> "MODEL_MOVED"
        HostedQueryFailure.UNSUPPORTED_MODEL -> "UNSUPPORTED_MODEL"
        HostedQueryFailure.UNSUPPORTED_DECLARATION -> "UNSUPPORTED_DECLARATION"
        HostedQueryFailure.UNRESOLVED_SUPERTYPE -> "UNRESOLVED_SUPERTYPE"
        HostedQueryFailure.FILE_UNAVAILABLE -> "FILE_UNAVAILABLE"
        HostedQueryFailure.FILE_TOO_LARGE -> "FILE_TOO_LARGE"
        HostedQueryFailure.RESULT_LIMIT_EXCEEDED -> "RESULT_LIMIT_EXCEEDED"
        HostedQueryFailure.OUTSIDE_SCOPE -> "OUTSIDE_SCOPE"
        HostedQueryFailure.AMBIGUOUS_SCOPE -> "AMBIGUOUS_SCOPE"
        HostedQueryFailure.READ_PREEMPTED -> "READ_PREEMPTED"
        HostedQueryFailure.CANCELLED -> "CANCELLED"
        HostedQueryFailure.BUDGET_EXCEEDED -> "BUDGET_EXCEEDED"
        is HostedQueryFailure.Platform -> "PLATFORM_FAILURE"
        is HostedQueryFailure.ProjectAdmission -> "PROJECT_ADMISSION_REJECTED"
        is HostedQueryFailure.ModelCapture -> "MODEL_CAPTURE_REJECTED"
        is HostedQueryFailure.ReadEpoch -> "READ_EPOCH_REJECTED"
        is HostedQueryFailure.Freshness -> "FRESHNESS_REJECTED"
        is HostedQueryFailure.LiveAuthority -> "LIVE_AUTHORITY_REJECTED"
        is HostedQueryFailure.NamedSourceScope -> "NAMED_SOURCE_SCOPE_REJECTED"
    }

/** Closed, bounded diagnostic data; never exception text or compiler/source objects. */
internal fun HostedQueryFailure.detail(): JsonElement =
    when (this) {
        is HostedQueryFailure.Configuration ->
            when (val failure = cause) {
                io.github.amichne.kast.kernel.ReadLimitFailure.UnknownParameter ->
                    Json.encodeToJsonElement(CauseDetail("UNKNOWN_PARAMETER"))
                is io.github.amichne.kast.kernel.ReadLimitFailure.InvalidValue ->
                    Json.encodeToJsonElement(ParameterDetail(failure.kind.name, failure.parameter.environmentKey))
                is io.github.amichne.kast.kernel.ReadLimitFailure.InconsistentBounds ->
                    Json.encodeToJsonElement(
                        BoundsDetail("INCONSISTENT_BOUNDS", failure.inner.environmentKey, failure.outer.environmentKey)
                    )
            }
        is HostedQueryFailure.Platform -> Json.encodeToJsonElement(CauseDetail(cause.name))
        is HostedQueryFailure.ModelCapture -> Json.encodeToJsonElement(cause.failures.map { it.name })
        is HostedQueryFailure.ProjectAdmission -> cause.detail()
        // These closed causes contain only repository-owned enum/object variants, never platform data.
        is HostedQueryFailure.ReadEpoch -> cause.wireCause()
        is HostedQueryFailure.Freshness -> cause.wireCause()
        is HostedQueryFailure.LiveAuthority -> Json.encodeToJsonElement(cause.name)
        is HostedQueryFailure.NamedSourceScope -> cause.wireCause()
        else -> Json.encodeToJsonElement(EmptyDetail())
    }

private fun ExistingProjectAdmissionFailure.detail(): JsonElement =
    when (val failure = this) {
        ExistingProjectAdmissionFailure.ProjectDisposed -> Json.encodeToJsonElement("PROJECT_DISPOSED")
        ExistingProjectAdmissionFailure.ProjectNotOpen -> Json.encodeToJsonElement("PROJECT_NOT_OPEN")
        ExistingProjectAdmissionFailure.ProjectNotInitialized -> Json.encodeToJsonElement("PROJECT_NOT_INITIALIZED")
        ExistingProjectAdmissionFailure.ProjectRootUnavailable -> Json.encodeToJsonElement("PROJECT_ROOT_UNAVAILABLE")
        ExistingProjectAdmissionFailure.ProjectRootMismatch -> Json.encodeToJsonElement("PROJECT_ROOT_MISMATCH")
        ExistingProjectAdmissionFailure.GradleModelUnavailable -> Json.encodeToJsonElement("GRADLE_MODEL_UNAVAILABLE")
        ExistingProjectAdmissionFailure.GradleModelIncomplete -> Json.encodeToJsonElement("GRADLE_MODEL_INCOMPLETE")
        ExistingProjectAdmissionFailure.DumbMode -> Json.encodeToJsonElement("DUMB_MODE")
        ExistingProjectAdmissionFailure.K2Unavailable -> Json.encodeToJsonElement("K2_UNAVAILABLE")
        ExistingProjectAdmissionFailure.HostIdentityUnavailable -> Json.encodeToJsonElement("HOST_IDENTITY_UNAVAILABLE")
        ExistingProjectAdmissionFailure.RetainedAuthorityMismatch ->
            Json.encodeToJsonElement("RETAINED_AUTHORITY_MISMATCH")
        is ExistingProjectAdmissionFailure.HostIncompatible ->
            Json.encodeToJsonElement(
                CompatibilityDetail(
                    stage = "HOST_COMPATIBILITY",
                    field =
                        when (val cause = failure.cause) {
                            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Malformed ->
                                cause.field.name
                            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Mismatch ->
                                cause.mismatch.field.name
                            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.UnknownCapability,
                            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.UnsupportedCapability,
                            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.DuplicateCapability ->
                                "CAPABILITIES"
                        },
                )
            )
        is ExistingProjectAdmissionFailure.ObservationFailed ->
            Json.encodeToJsonElement(StageDetail(failure.stage.name))
    }

private fun io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.wireCause(): JsonElement =
    when (this) {
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.WrongThread ->
            Json.encodeToJsonElement("WRONG_THREAD")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectDisposed ->
            Json.encodeToJsonElement("PROJECT_DISPOSED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectNotOpen ->
            Json.encodeToJsonElement("PROJECT_NOT_OPEN")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectNotInitialized ->
            Json.encodeToJsonElement("PROJECT_NOT_INITIALIZED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectRootUnavailable ->
            Json.encodeToJsonElement("PROJECT_ROOT_UNAVAILABLE")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectRootMalformed ->
            Json.encodeToJsonElement("PROJECT_ROOT_MALFORMED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.DumbMode ->
            Json.encodeToJsonElement("DUMB_MODE")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleModelUnavailable ->
            Json.encodeToJsonElement("GRADLE_MODEL_UNAVAILABLE")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleModelIncomplete ->
            Json.encodeToJsonElement("GRADLE_MODEL_INCOMPLETE")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleModelAmbiguous ->
            Json.encodeToJsonElement("GRADLE_MODEL_AMBIGUOUS")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleRootUnavailable ->
            Json.encodeToJsonElement("GRADLE_ROOT_UNAVAILABLE")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleRootMalformed ->
            Json.encodeToJsonElement("GRADLE_ROOT_MALFORMED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ImportTimestampsIncoherent ->
            Json.encodeToJsonElement("IMPORT_TIMESTAMPS_INCOHERENT")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.VfsBatchLimitExceeded ->
            Json.encodeToJsonElement("VFS_BATCH_LIMIT_EXCEEDED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.VfsPathMalformed ->
            Json.encodeToJsonElement("VFS_PATH_MALFORMED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.SignalExhausted ->
            Json.encodeToJsonElement("SIGNAL_EXHAUSTED")
        io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ReadPreempted ->
            Json.encodeToJsonElement("READ_PREEMPTED")
        is io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ObservationFailed ->
            Json.encodeToJsonElement(ObservationDetail("OBSERVATION_FAILED", stage.name))
    }

private fun io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.wireCause(): JsonElement =
    when (this) {
        io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.ProjectDisposed ->
            Json.encodeToJsonElement("PROJECT_DISPOSED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.DumbMode ->
            Json.encodeToJsonElement("DUMB_MODE")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Moved ->
            Json.encodeToJsonElement("MOVED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Incomparable ->
            Json.encodeToJsonElement("INCOMPARABLE")
        is io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Unavailable -> cause.wireCause()
    }

private fun io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.wireCause(): JsonElement =
    when (this) {
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.WrongThread ->
            Json.encodeToJsonElement("WRONG_THREAD")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectNotOpen ->
            Json.encodeToJsonElement("PROJECT_NOT_OPEN")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectNotInitialized ->
            Json.encodeToJsonElement("PROJECT_NOT_INITIALIZED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectRootUnavailable ->
            Json.encodeToJsonElement("PROJECT_ROOT_UNAVAILABLE")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectRootMalformed ->
            Json.encodeToJsonElement("PROJECT_ROOT_MALFORMED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleModelUnavailable ->
            Json.encodeToJsonElement("GRADLE_MODEL_UNAVAILABLE")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleModelIncomplete ->
            Json.encodeToJsonElement("GRADLE_MODEL_INCOMPLETE")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleModelAmbiguous ->
            Json.encodeToJsonElement("GRADLE_MODEL_AMBIGUOUS")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleRootUnavailable ->
            Json.encodeToJsonElement("GRADLE_ROOT_UNAVAILABLE")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleRootMalformed ->
            Json.encodeToJsonElement("GRADLE_ROOT_MALFORMED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ImportTimestampsIncoherent ->
            Json.encodeToJsonElement("IMPORT_TIMESTAMPS_INCOHERENT")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.VfsBatchLimitExceeded ->
            Json.encodeToJsonElement("VFS_BATCH_LIMIT_EXCEEDED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.VfsPathMalformed ->
            Json.encodeToJsonElement("VFS_PATH_MALFORMED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.SignalExhausted ->
            Json.encodeToJsonElement("SIGNAL_EXHAUSTED")
        io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ReadPreempted ->
            Json.encodeToJsonElement("READ_PREEMPTED")
        is io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ObservationFailed ->
            Json.encodeToJsonElement(ObservationDetail("OBSERVATION_FAILED", stage.name))
    }
