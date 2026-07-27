package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal inline fun <T> runIdeaReadAction(crossinline action: () -> T): T =
    ApplicationManager.getApplication().runReadAction<T> { action() }

internal suspend inline fun <T> timedReadAction(
    telemetry: IdeaBackendTelemetry,
    scope: IdeaTelemetryScope,
    name: String,
    crossinline block: () -> T,
): T {
    val waitStart = System.nanoTime()
    return readAction {
        val holdStart = System.nanoTime()
        val waitNanos = holdStart - waitStart
        try {
            block()
        } finally {
            val holdNanos = System.nanoTime() - holdStart
            telemetry.recordReadAction(scope, name, waitNanos, holdNanos)
        }
    }
}
