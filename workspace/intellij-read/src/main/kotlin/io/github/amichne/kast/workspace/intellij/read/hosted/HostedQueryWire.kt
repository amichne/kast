package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.Gson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure

/** Detached transport projection. Encoding may only run after the service has released its reads. */
object HostedQueryWire {
    fun encode(result: HostedQueryResult): String = Gson().toJson(when (result) {
        is HostedQueryResult.Rejected -> mapOf(
            "schemaVersion" to 1, "outcome" to "rejected", "failure" to result.failure.code(),
            "detail" to result.failure.detail(), "stage" to result.stage.name,
        )
        is HostedQueryResult.Published -> mapOf(
            "schemaVersion" to 1, "outcome" to "published", "stage" to HostedQueryStage.RESULT_DETACHED.name,
            "publication" to "request_local_same_source_epoch",
            "workspaceRoot" to result.publication.model.canonicalRoot.value,
            "host" to mapOf("ideBuild" to result.publication.model.compatibility.ideBuild.value,
                "kotlinBuild" to result.publication.model.compatibility.kotlinPluginBuild.value),
            "content" to "saved_committed_ide_vfs",
            "scope" to "cached_gradle_source_folders",
            "kind" to "inheritors",
            "supertype" to result.publication.relation.supertype.document(),
            "inheritor" to result.publication.relation.inheritor.document(),
        )
    })
}

private fun HostedCompilerDeclaration.document(): Map<String, Any> {
    // Reuse the canonical wire proof, including its identity/signature agreement check.
    val qualified = when (val parsed = ProtocolText.parse(symbol.signature.qualifiedIdentity.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("Bounded compiler identity failed protocol projection")
    }
    val compiler = when (val projected = CompilerSymbolEvidenceDocument.fromSignature(CompilerSignatureDocument.ClassLike(qualified))) {
        is Refinement.Refined -> projected.value
        is Refinement.Rejected -> error("Proven class signature failed protocol projection")
    }
    check(compiler.identity.value == symbol.compilerIdentity.value)
    return mapOf(
        "file" to symbol.file.stableValue,
        "compilerIdentity" to compiler.identity.value,
        "signature" to mapOf("kind" to "class_like", "qualifiedIdentity" to qualified.value),
        "canonicalSignature" to symbol.signature.canonicalEncoding().value,
        "documentStamp" to content.documentStamp.value, "vfsStamp" to content.vfsStamp.value,
        "module" to module.name.value,
        "gradleBuildRoot" to module.owner.buildRoot.value,
        "gradleProject" to module.owner.projectIdentity.value,
        "sourceRoot" to sourceRoot.location.value,
        "sourceKind" to sourceRoot.kind.name,
        "provenanceAuthority" to "cached_source_folder_flag",
    )
}

internal fun HostedQueryFailure.code(): String = when (this) {
    HostedQueryFailure.RETIRED -> "RETIRED"
    HostedQueryFailure.WRONG_ENDPOINT -> "WRONG_ENDPOINT"
    HostedQueryFailure.WRONG_PROJECT -> "WRONG_PROJECT"
    HostedQueryFailure.BUSY -> "BUSY"
    HostedQueryFailure.STALE_REQUEST -> "STALE_REQUEST"
    HostedQueryFailure.INVALID_SELECTION -> "INVALID_SELECTION"
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
}

/** Closed, bounded diagnostic data; never exception text or compiler/source objects. */
private fun HostedQueryFailure.detail(): Any = when (this) {
    is HostedQueryFailure.Platform -> mapOf("cause" to cause.name)
    is HostedQueryFailure.ModelCapture -> cause.failures.map { it.name }
    is HostedQueryFailure.ProjectAdmission -> when (val failure = cause) {
        ExistingProjectAdmissionFailure.ProjectDisposed -> "PROJECT_DISPOSED"
        ExistingProjectAdmissionFailure.ProjectNotOpen -> "PROJECT_NOT_OPEN"
        ExistingProjectAdmissionFailure.ProjectNotInitialized -> "PROJECT_NOT_INITIALIZED"
        ExistingProjectAdmissionFailure.ProjectRootUnavailable -> "PROJECT_ROOT_UNAVAILABLE"
        ExistingProjectAdmissionFailure.ProjectRootMismatch -> "PROJECT_ROOT_MISMATCH"
        ExistingProjectAdmissionFailure.GradleModelUnavailable -> "GRADLE_MODEL_UNAVAILABLE"
        ExistingProjectAdmissionFailure.GradleModelIncomplete -> "GRADLE_MODEL_INCOMPLETE"
        ExistingProjectAdmissionFailure.DumbMode -> "DUMB_MODE"
        ExistingProjectAdmissionFailure.K2Unavailable -> "K2_UNAVAILABLE"
        ExistingProjectAdmissionFailure.HostIdentityUnavailable -> "HOST_IDENTITY_UNAVAILABLE"
        ExistingProjectAdmissionFailure.RetainedAuthorityMismatch -> "RETAINED_AUTHORITY_MISMATCH"
        is ExistingProjectAdmissionFailure.HostIncompatible -> mapOf("stage" to "HOST_COMPATIBILITY", "field" to when (val cause = failure.cause) {
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Malformed -> cause.field.name
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Mismatch -> cause.mismatch.field.name
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.UnknownCapability,
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.UnsupportedCapability,
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.DuplicateCapability -> "CAPABILITIES"
        })
        is ExistingProjectAdmissionFailure.ObservationFailed -> mapOf("stage" to failure.stage.name)
    }
    // These closed causes contain only repository-owned enum/object variants, never platform data.
    is HostedQueryFailure.ReadEpoch -> cause.wireCause()
    is HostedQueryFailure.Freshness -> cause.wireCause()
    else -> emptyMap<String, String>()
}

private fun io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.wireCause(): Any = when (this) {
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.WrongThread -> "WRONG_THREAD"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectDisposed -> "PROJECT_DISPOSED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectNotOpen -> "PROJECT_NOT_OPEN"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectNotInitialized -> "PROJECT_NOT_INITIALIZED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectRootUnavailable -> "PROJECT_ROOT_UNAVAILABLE"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ProjectRootMalformed -> "PROJECT_ROOT_MALFORMED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.DumbMode -> "DUMB_MODE"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleModelUnavailable -> "GRADLE_MODEL_UNAVAILABLE"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleModelIncomplete -> "GRADLE_MODEL_INCOMPLETE"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleModelAmbiguous -> "GRADLE_MODEL_AMBIGUOUS"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleRootUnavailable -> "GRADLE_ROOT_UNAVAILABLE"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.GradleRootMalformed -> "GRADLE_ROOT_MALFORMED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ImportTimestampsIncoherent -> "IMPORT_TIMESTAMPS_INCOHERENT"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.VfsBatchLimitExceeded -> "VFS_BATCH_LIMIT_EXCEEDED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.VfsPathMalformed -> "VFS_PATH_MALFORMED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.SignalExhausted -> "SIGNAL_EXHAUSTED"
    io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ReadPreempted -> "READ_PREEMPTED"
    is io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure.ObservationFailed -> mapOf("cause" to "OBSERVATION_FAILED", "stage" to stage.name)
}

private fun io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.wireCause(): Any = when (this) {
    io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.ProjectDisposed -> "PROJECT_DISPOSED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.DumbMode -> "DUMB_MODE"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Moved -> "MOVED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Incomparable -> "INCOMPARABLE"
    is io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Unavailable -> cause.wireCause()
}

private fun io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.wireCause(): Any = when (this) {
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.WrongThread -> "WRONG_THREAD"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectNotOpen -> "PROJECT_NOT_OPEN"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectNotInitialized -> "PROJECT_NOT_INITIALIZED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectRootUnavailable -> "PROJECT_ROOT_UNAVAILABLE"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ProjectRootMalformed -> "PROJECT_ROOT_MALFORMED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleModelUnavailable -> "GRADLE_MODEL_UNAVAILABLE"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleModelIncomplete -> "GRADLE_MODEL_INCOMPLETE"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleModelAmbiguous -> "GRADLE_MODEL_AMBIGUOUS"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleRootUnavailable -> "GRADLE_ROOT_UNAVAILABLE"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.GradleRootMalformed -> "GRADLE_ROOT_MALFORMED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ImportTimestampsIncoherent -> "IMPORT_TIMESTAMPS_INCOHERENT"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.VfsBatchLimitExceeded -> "VFS_BATCH_LIMIT_EXCEEDED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.VfsPathMalformed -> "VFS_PATH_MALFORMED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.SignalExhausted -> "SIGNAL_EXHAUSTED"
    io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ReadPreempted -> "READ_PREEMPTED"
    is io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause.ObservationFailed -> mapOf("cause" to "OBSERVATION_FAILED", "stage" to stage.name)
}
