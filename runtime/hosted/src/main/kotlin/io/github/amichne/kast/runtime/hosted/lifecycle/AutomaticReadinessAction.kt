package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRecovery
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument

internal enum class AutomaticReadinessAction {
    Ready,
    ReloadModel,
    Wait,
    UnsavedDocuments,
}

internal fun HostedReadinessDocument.automaticAction(): AutomaticReadinessAction =
    when (this) {
        HostedReadinessDocument.AdmissionReady -> AutomaticReadinessAction.Ready
        is HostedReadinessDocument.Unavailable ->
            when (rejection.recovery) {
                is HostedReadRecovery.GradleModel -> AutomaticReadinessAction.ReloadModel
                is HostedReadRecovery.SaveSource -> AutomaticReadinessAction.UnsavedDocuments
                else -> AutomaticReadinessAction.Wait
            }
    }
