package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** A fresh admission observation, never a semantic read capability or promise of query success. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface HostedReadinessDocument {
    @Serializable @SerialName("admission_ready") data object AdmissionReady : HostedReadinessDocument

    @Serializable
    @SerialName("unavailable")
    data class Unavailable(val rejection: HostedQueryRejectionDocument) : HostedReadinessDocument
}

/** No semantic executor, permit, epoch, cache, import, indexing wait, or compiler call. */
internal fun observeHostedReadiness(observe: () -> ExistingProjectValidation): HostedReadinessDocument =
    when (val validation = observe()) {
        ExistingProjectValidation.Validated -> HostedReadinessDocument.AdmissionReady
        is ExistingProjectValidation.Rejected ->
            HostedQueryFailure.ProjectAdmission(validation.failure)
                .readinessRejection(HostedQueryStage.PROJECT_ADMISSION)
    }

internal fun HostedQueryFailure.readinessRejection(stage: HostedQueryStage): HostedReadinessDocument.Unavailable =
    HostedReadinessDocument.Unavailable(
        HostedQueryRejectionDocument(
            failure = code(),
            detail = detail(),
            stage = stage.name,
            recovery = recovery(),
        )
    )
