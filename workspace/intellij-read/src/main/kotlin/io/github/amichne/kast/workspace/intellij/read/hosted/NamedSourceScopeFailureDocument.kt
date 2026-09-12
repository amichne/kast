package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.GradleModuleOwnershipFailure
import io.github.amichne.kast.workspace.intellij.read.IdeRootMappingFailure
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Closed detail variants for the named scope boundary, including bounded identities and both classifications. */
@Serializable
internal sealed interface NamedSourceScopeFailureDocument {
    @Serializable
    @SerialName("IDE_ROOT_UNMAPPED")
    data class Unmapped(val reason: UnmappedRootReason, val module: IdentityDocument, val root: IdentityDocument) :
        NamedSourceScopeFailureDocument

    @Serializable
    @SerialName("IDE_ROOT_INCOHERENT")
    data class Incoherent(val reason: IncoherentRootReason, val module: IdentityDocument, val root: IdentityDocument) :
        NamedSourceScopeFailureDocument

    @Serializable
    @SerialName("ROOT_CLASSIFICATION_MISMATCH")
    data class ClassificationMismatch(
        val module: IdentityDocument,
        val root: IdentityDocument,
        val ide: ClassificationDocument,
        val gradle: ClassificationDocument,
    ) : NamedSourceScopeFailureDocument

    @Serializable
    @SerialName("OWNER_UNAVAILABLE")
    data class OwnerUnavailable(val module: IdentityDocument, val reason: GradleModuleOwnershipFailure) :
        NamedSourceScopeFailureDocument
}

@Serializable
internal enum class UnmappedRootReason {
    SOURCE_FOLDER_UNAVAILABLE,
    SOURCE_FOLDER_OBSERVATION_FAILED,
    GRADLE_OWNER_MISSING,
}

@Serializable
internal enum class IncoherentRootReason {
    UNSUPPORTED_ROOT_TYPE,
    SOURCE_PROPERTIES_UNAVAILABLE,
}

@Serializable internal data class IdentityDocument(val value: String, val truncated: Boolean)

@Serializable
internal data class ClassificationDocument(
    val kind: WorkspaceSourceRootKind,
    val provenance: WorkspaceSourceRootProvenance,
)

internal val hostedFailureJson = Json {
    encodeDefaults = true
    classDiscriminator = "cause"
}

internal fun NamedGradleSourceScopeFailure.wireCause(): JsonElement =
    when (this) {
        NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE -> Json.encodeToJsonElement("MODEL_UNAVAILABLE")
        NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT -> Json.encodeToJsonElement("IDE_ROOT_INCOHERENT")
        NamedGradleSourceScopeFailure.OWNER_UNAVAILABLE -> Json.encodeToJsonElement("OWNER_UNAVAILABLE")
        NamedGradleSourceScopeFailure.CAPTURE_LIMIT -> Json.encodeToJsonElement("CAPTURE_LIMIT")
        NamedGradleSourceScopeFailure.PROJECT_UNAVAILABLE -> Json.encodeToJsonElement("PROJECT_UNAVAILABLE")
        NamedGradleSourceScopeFailure.INDEXING -> Json.encodeToJsonElement("INDEXING")
        is NamedGradleSourceScopeFailure.ModelRejected -> Json.encodeToJsonElement("MODEL_REJECTED")
        is NamedGradleSourceScopeFailure.ObservationFailed -> Json.encodeToJsonElement(StageDetail(stage.name))
        is NamedGradleSourceScopeFailure.ModuleOwnership ->
            hostedFailureJson.encodeToJsonElement<NamedSourceScopeFailureDocument>(
                NamedSourceScopeFailureDocument.OwnerUnavailable(
                    IdentityDocument(module.value, module.truncated),
                    cause,
                )
            )
        is NamedGradleSourceScopeFailure.RootMapping -> hostedFailureJson.encodeToJsonElement(cause.document())
    }

private fun IdeRootMappingFailure.document(): NamedSourceScopeFailureDocument {
    val module = IdentityDocument(evidence.module.value, evidence.module.truncated)
    val root = IdentityDocument(evidence.root.value, evidence.root.truncated)
    return when (this) {
        is IdeRootMappingFailure.SourceFolderObservationFailed ->
            NamedSourceScopeFailureDocument.Unmapped(UnmappedRootReason.SOURCE_FOLDER_OBSERVATION_FAILED, module, root)
        is IdeRootMappingFailure.SourceFolderUnavailable ->
            NamedSourceScopeFailureDocument.Unmapped(UnmappedRootReason.SOURCE_FOLDER_UNAVAILABLE, module, root)
        is IdeRootMappingFailure.GradleOwnerMissing ->
            NamedSourceScopeFailureDocument.Unmapped(UnmappedRootReason.GRADLE_OWNER_MISSING, module, root)
        is IdeRootMappingFailure.UnsupportedRootType ->
            NamedSourceScopeFailureDocument.Incoherent(IncoherentRootReason.UNSUPPORTED_ROOT_TYPE, module, root)
        is IdeRootMappingFailure.SourcePropertiesUnavailable ->
            NamedSourceScopeFailureDocument.Incoherent(IncoherentRootReason.SOURCE_PROPERTIES_UNAVAILABLE, module, root)
        is IdeRootMappingFailure.RootClassificationMismatch ->
            NamedSourceScopeFailureDocument.ClassificationMismatch(
                module = module,
                root = root,
                ide = ClassificationDocument(ide.kind, ide.provenance),
                gradle = ClassificationDocument(gradle.kind, gradle.provenance),
            )
    }
}
